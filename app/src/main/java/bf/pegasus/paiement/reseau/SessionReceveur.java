package bf.pegasus.paiement.reseau;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.List;

import bf.pegasus.paiement.crypto.CanalChiffre;
import bf.pegasus.paiement.crypto.EchangeCles;
import bf.pegasus.paiement.crypto.Octets;
import bf.pegasus.paiement.metier.Depot;
import bf.pegasus.paiement.metier.ErreurPaiement;
import bf.pegasus.paiement.metier.Message;
import bf.pegasus.paiement.metier.Operation;
import bf.pegasus.paiement.metier.Portefeuille;

/**
 * Côté receveur : ouvre le serveur, attend UN payeur, vérifie et crédite ses paiements.
 * Une session = une clé publique temporaire et un numéro de session neufs.
 */
public final class SessionReceveur implements Closeable {

    /** Ce que l'écran « Recevoir » affiche. Les appels arrivent hors du fil principal. */
    public interface Vue {
        void payeurConnecte(String nomPayeur);

        void paiementRecu(Operation operation);

        /** Paiement renvoyé après une coupure : déjà crédité, donc pas recrédité. */
        void paiementDejaRecu(String idPaiement);

        void paiementRefuse(String motif);

        /** Un autre téléphone a tenté de se connecter : refusé. */
        void connexionRefusee();

        /** @param raison {@code null} si la session s'est terminée normalement */
        void terminee(String raison);
    }

    private final Depot depot;
    private final Vue vue;
    private final EchangeCles cles;
    private final String session;
    private ServeurReception serveur;
    private volatile ConnexionPair pair;
    private volatile boolean fermee;

    public SessionReceveur(Depot depot, Vue vue) throws GeneralSecurityException {
        this.depot = depot;
        this.vue = vue;
        this.cles = new EchangeCles();
        this.session = Octets.hex(Octets.aleatoires(8));
    }

    /**
     * Ouvre le serveur TCP et prépare le contenu du QR code.
     *
     * @param adresses adresses IP de ce téléphone sur son point d'accès
     */
    public InfosConnexion demarrer(String ssid, String motDePasse, String securite, List<String> adresses)
            throws IOException {
        serveur = new ServeurReception(0, new ServeurReception.Ecouteur() {
            @Override
            public void pairAccepte(final Socket socket) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        dialoguer(socket);
                    }
                }, "phl-receveur").start();
            }

            @Override
            public void connexionRefusee() {
                vue.connexionRefusee();
            }
        });
        Portefeuille p = depot.lecture();
        return new InfosConnexion(ssid, motDePasse, securite, serveur.port(), adresses,
                cles.clePublique(), session, p.nom(), p.appareil());
    }

    private void dialoguer(Socket socket) {
        String raison = null;
        ConnexionPair c = null;
        try {
            c = new ConnexionPair(socket);
            pair = c;
            socket.setSoTimeout(ProtocoleTransfert.DELAI_POIGNEE_MS);

            // 1. « bonjour » en clair : clé publique temporaire du payeur
            String ligne = c.lireLigne();
            if (ligne == null) {
                throw new ProtocoleException("Le payeur s'est déconnecté.");
            }
            Message bonjour = Message.decoder(ligne);
            if (!"bonjour".equals(bonjour.type()) || !session.equals(bonjour.exiger("sid"))) {
                throw new ProtocoleException("Session inconnue.");
            }
            String clePayeur = bonjour.exiger("pk");
            byte[] cle = cles.cleSession(clePayeur, Octets.deHex(session),
                    ProtocoleTransfert.contexte(clePayeur, cles.clePublique()));
            c.activerChiffrement(new CanalChiffre(cle, CanalChiffre.Role.RECEVEUR));
            Octets.effacer(cle);

            // 2. « presentation » chiffrée : prouve que le payeur a bien calculé la même clé
            Message presentation = c.recevoir();
            if (presentation == null || !"presentation".equals(presentation.type())) {
                throw new ProtocoleException("Présentation absente.");
            }
            String appareilPayeur = presentation.exiger("app");
            if (!Octets.estHex(appareilPayeur, 16)) {
                throw new ProtocoleException("Identifiant du payeur invalide.");
            }
            String nomPayeur = Portefeuille.nettoyerNom(presentation.exiger("nom"));
            Portefeuille moi = depot.lecture();
            c.envoyer(Message.de("bienvenue").avec("app", moi.appareil()).avec("nom", moi.nom()));
            socket.setSoTimeout(0);
            vue.payeurConnecte(nomPayeur);

            // 3. Paiements, jusqu'à « fin »
            while (true) {
                Message m = c.recevoir();
                if (m == null || "fin".equals(m.type())) {
                    break;
                }
                if (!"paiement".equals(m.type())) {
                    throw new ProtocoleException("Message inattendu.");
                }
                traiterPaiement(c, m, appareilPayeur, nomPayeur);
            }
        } catch (GeneralSecurityException e) {
            raison = "Message rejeté : clé incorrecte ou message modifié.";
        } catch (Message.FormatInvalide | ProtocoleException e) {
            raison = "Échange invalide : " + e.getMessage();
        } catch (IOException e) {
            raison = "Connexion interrompue.";
        } finally {
            if (c != null) {
                c.close();
            }
            pair = null;
        }
        if (!fermee) {
            vue.terminee(raison);
        }
    }

    private void traiterPaiement(ConnexionPair c, Message m, final String appareilPayeur, final String nomPayeur)
            throws IOException, GeneralSecurityException, Message.FormatInvalide {
        final String id = m.exiger("id");
        final long montant = m.exigerNombre("montant");
        if (!appareilPayeur.equals(m.exiger("de"))) {
            c.envoyer(Message.de("refus").avec("id", id).avec("motif", "identite"));
            vue.paiementRefuse("identite");
            return;
        }
        Portefeuille.Reception r;
        try {
            // Crédit ET journal anti-rejeu enregistrés dans le coffre AVANT d'envoyer le reçu
            r = depot.modifier(new Depot.Modification<Portefeuille.Reception>() {
                @Override
                public Portefeuille.Reception appliquer(Portefeuille p) {
                    return p.crediter(id, appareilPayeur, nomPayeur, montant, System.currentTimeMillis());
                }
            });
        } catch (ErreurPaiement e) {
            throw new IOException(e.getMessage());
        }
        switch (r.etat) {
            case ACCEPTE:
                c.envoyer(Message.de("recu").avec("id", id));
                vue.paiementRecu(r.operation);
                break;
            case DEJA_RECU:
                c.envoyer(Message.de("recu").avec("id", id));
                vue.paiementDejaRecu(id);
                break;
            default:
                c.envoyer(Message.de("refus").avec("id", id).avec("motif", r.motif));
                vue.paiementRefuse(r.motif);
                break;
        }
    }

    /** Ferme la session : serveur arrêté, connexion fermée, clé de session effacée. */
    @Override
    public void close() {
        fermee = true;
        if (serveur != null) {
            serveur.close();
        }
        ConnexionPair c = pair;
        if (c != null) {
            c.close();
        }
    }
}
