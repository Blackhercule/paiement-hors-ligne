package bf.pegasus.paiement.reseau;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import bf.pegasus.paiement.crypto.CanalChiffre;
import bf.pegasus.paiement.crypto.EchangeCles;
import bf.pegasus.paiement.crypto.Octets;
import bf.pegasus.paiement.metier.Depot;
import bf.pegasus.paiement.metier.ErreurPaiement;
import bf.pegasus.paiement.metier.Falsification;
import bf.pegasus.paiement.metier.Message;
import bf.pegasus.paiement.metier.Operation;
import bf.pegasus.paiement.metier.Portefeuille;

/**
 * Côté payeur : se connecte au receveur lu dans le QR code, établit le canal chiffré,
 * renvoie les paiements restés en attente, puis paie.
 */
public final class SessionPayeur implements Closeable {

    /** Ce que l'écran « Envoyer » affiche. Les appels arrivent hors du fil principal. */
    public interface Vue {
        void paiementConfirme(Operation operation);

        void paiementRefuse(String idPaiement, String motif);

        /** @param raison {@code null} si la session s'est terminée normalement */
        void terminee(String raison);
    }

    /** Le solde a été débité mais l'ordre de paiement n'a pas pu partir : il reste « en attente ». */
    public static final class EnvoiInterrompu extends IOException {
        public final transient Operation operation;

        EnvoiInterrompu(Operation operation, IOException cause) {
            super("Connexion perdue pendant l'envoi.", cause);
            this.operation = operation;
        }
    }

    /** Crée la socket (sur Android : liée au réseau Wi-Fi du receveur). */
    public interface FabriqueSocket {
        Socket creer() throws IOException;
    }

    private final Depot depot;
    private final InfosConnexion receveur;
    private final Vue vue;
    private volatile ConnexionPair pair;
    private volatile boolean fermee;

    public SessionPayeur(Depot depot, InfosConnexion receveur, Vue vue) {
        this.depot = depot;
        this.receveur = receveur;
        this.vue = vue;
    }

    public InfosConnexion receveur() {
        return receveur;
    }

    /**
     * Connexion et poignée de main chiffrée. Bloquant : à appeler hors du fil principal.
     *
     * @param adresses adresses à essayer dans l'ordre (passerelle du Wi-Fi, puis celles du QR)
     * @return le nombre de paiements en attente renvoyés à ce receveur
     */
    public int connecter(List<String> adresses, FabriqueSocket fabrique, int delaiMs)
            throws IOException, GeneralSecurityException, ProtocoleException {
        Set<String> aEssayer = new LinkedHashSet<>(adresses);
        aEssayer.addAll(receveur.adresses);
        Socket socket = null;
        IOException derniere = null;
        for (String adresse : aEssayer) {
            if (fermee) {
                throw new IOException("Session fermée.");
            }
            Socket essai = fabrique.creer();
            try {
                essai.connect(new InetSocketAddress(InetAddress.getByName(adresse), receveur.port), delaiMs);
                socket = essai;
                break;
            } catch (IOException e) {
                derniere = e;
                fermerSansErreur(essai);
            }
        }
        if (socket == null) {
            throw new IOException("Receveur injoignable.", derniere);
        }

        ConnexionPair c = new ConnexionPair(socket);
        pair = c;
        try {
            socket.setSoTimeout(ProtocoleTransfert.DELAI_POIGNEE_MS);
            EchangeCles cles = new EchangeCles();
            c.ecrireLigne(Message.de("bonjour").avec("v", ProtocoleTransfert.VERSION)
                    .avec("sid", receveur.session).avec("pk", cles.clePublique()).encoder());
            byte[] cle = cles.cleSession(receveur.clePublique, Octets.deHex(receveur.session),
                    ProtocoleTransfert.contexte(cles.clePublique(), receveur.clePublique));
            c.activerChiffrement(new CanalChiffre(cle, CanalChiffre.Role.PAYEUR));
            Octets.effacer(cle);

            Portefeuille moi = depot.lecture();
            c.envoyer(Message.de("presentation").avec("app", moi.appareil()).avec("nom", moi.nom()));
            Message bienvenue;
            try {
                bienvenue = c.recevoir();
            } catch (IOException e) {
                bienvenue = null;
            }
            if (bienvenue == null) {
                throw new ProtocoleException("Le receveur a refusé la connexion.");
            }
            if (!"bienvenue".equals(bienvenue.type()) || !receveur.appareil.equals(bienvenue.exiger("app"))) {
                throw new ProtocoleException("Identité du receveur incohérente.");
            }
            socket.setSoTimeout(0);
        } catch (Message.FormatInvalide e) {
            fermerConnexion();
            throw new ProtocoleException(e.getMessage());
        } catch (IOException | GeneralSecurityException | ProtocoleException | RuntimeException e) {
            fermerConnexion();
            throw e;
        }

        Thread ecoute = new Thread(new Runnable() {
            @Override
            public void run() {
                ecouter();
            }
        }, "phl-payeur");
        ecoute.start();

        // Paiements restés en attente pour ce receveur : renvoyés avec le MÊME identifiant
        List<Operation> enAttente = depot.lecture().enAttentePour(receveur.appareil);
        for (Operation op : enAttente) {
            envoyerPaiement(op);
        }
        return enAttente.size();
    }

    public boolean estConnecte() {
        return pair != null && !fermee;
    }

    /**
     * Paie le receveur : 1. l'application se vérifie elle-même (signature et coffre),
     * 2. le solde est vérifié puis débité, 3. l'ordre de paiement part, chiffré.
     *
     * <p>Si l'envoi échoue après le débit, l'opération reste « en attente » et sera
     * renvoyée à la prochaine connexion avec ce receveur ({@link EnvoiInterrompu}).</p>
     */
    public Operation payer(final long montant) throws ErreurPaiement, Falsification, IOException {
        if (pair == null || fermee) {
            throw new IOException("Vous n'êtes pas connecté au receveur.");
        }
        depot.verifierIntegrite();
        Operation op = depot.modifier(new Depot.Modification<Operation>() {
            @Override
            public Operation appliquer(Portefeuille p) throws ErreurPaiement {
                return p.debiter(receveur.appareil, receveur.nom, montant, System.currentTimeMillis());
            }
        });
        try {
            envoyerPaiement(op);
        } catch (IOException e) {
            throw new EnvoiInterrompu(op, e);
        }
        return op;
    }

    private void envoyerPaiement(Operation op) throws IOException {
        ConnexionPair c = pair;
        if (c == null) {
            throw new IOException("Connexion perdue.");
        }
        try {
            c.envoyer(Message.de("paiement")
                    .avec("id", op.id)
                    .avec("montant", op.montant)
                    .avec("ts", op.horodatage)
                    .avec("de", depot.lecture().appareil()));
        } catch (GeneralSecurityException e) {
            throw new IOException("Chiffrement impossible.", e);
        }
    }

    private void ecouter() {
        String raison = null;
        ConnexionPair c = pair;
        try {
            while (c != null) {
                Message m = c.recevoir();
                if (m == null) {
                    break;
                }
                if ("recu".equals(m.type())) {
                    final String id = m.exiger("id");
                    Operation op = depot.modifier(new Depot.Modification<Operation>() {
                        @Override
                        public Operation appliquer(Portefeuille p) {
                            return p.confirmer(id);
                        }
                    });
                    if (op != null) {
                        vue.paiementConfirme(op);
                    }
                } else if ("refus".equals(m.type())) {
                    vue.paiementRefuse(m.exiger("id"), m.exiger("motif"));
                } else {
                    throw new ProtocoleException("Message inattendu.");
                }
            }
        } catch (GeneralSecurityException e) {
            raison = "Message rejeté : clé incorrecte ou message modifié.";
        } catch (Message.FormatInvalide | ProtocoleException | ErreurPaiement e) {
            raison = "Échange invalide.";
        } catch (IOException e) {
            raison = "Connexion interrompue.";
        }
        fermerConnexion();
        if (!fermee) {
            vue.terminee(raison);
        }
    }

    /** Termine proprement : prévient le receveur, ferme la connexion, efface la clé. */
    @Override
    public void close() {
        fermee = true;
        ConnexionPair c = pair;
        if (c != null) {
            try {
                c.envoyer(Message.de("fin"));
            } catch (IOException | GeneralSecurityException | RuntimeException ignoree) {
                // la connexion est peut-être déjà coupée
            }
        }
        fermerConnexion();
    }

    private void fermerConnexion() {
        ConnexionPair c = pair;
        pair = null;
        if (c != null) {
            c.close();
        }
    }

    private static void fermerSansErreur(Socket s) {
        try {
            s.close();
        } catch (IOException ignoree) {
            // rien à faire
        }
    }
}
