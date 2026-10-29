package bf.pegasus.paiement.reseau;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import bf.pegasus.paiement.crypto.EchangeCles;
import bf.pegasus.paiement.metier.Depot;
import bf.pegasus.paiement.metier.ErreurPaiement;
import bf.pegasus.paiement.metier.Falsification;
import bf.pegasus.paiement.metier.Operation;
import bf.pegasus.paiement.metier.Portefeuille;

/**
 * Deux « téléphones » dans le même programme, reliés par une vraie connexion TCP locale :
 * on teste le protocole complet, chiffrement compris.
 */
public class TransfertReseauTest {

    private static final List<String> LOCAL = Collections.singletonList("127.0.0.1");
    private static final SessionPayeur.FabriqueSocket SOCKET = Socket::new;

    private SessionReceveur sessionB;
    private SessionPayeur sessionA;

    @After
    public void fermer() {
        if (sessionA != null) {
            sessionA.close();
        }
        if (sessionB != null) {
            sessionB.close();
        }
    }

    @Test
    public void paiementCompletEntreDeuxTelephones() throws Exception {
        Telephone a = new Telephone("Awa");
        Telephone b = new Telephone("Boureima");
        InfosConnexion qr = recevoir(b);

        assertEquals(0, payeur(a, qr).connecter(LOCAL, SOCKET, 2000));
        assertEquals("Awa", b.evenements.poll(5, TimeUnit.SECONDS));

        Operation envoi = sessionA.payer(2_500);
        assertEquals(Operation.Statut.EN_ATTENTE, envoi.statut);
        assertEquals("recu:2500", b.evenements.poll(5, TimeUnit.SECONDS));
        assertEquals("confirme:" + envoi.id, a.evenements.poll(5, TimeUnit.SECONDS));

        assertEquals(47_500, a.depot.lecture().solde());
        assertEquals(52_500, b.depot.lecture().solde());
        assertEquals(Operation.Statut.CONFIRME, a.depot.lecture().operations().get(0).statut);
        Operation recu = b.depot.lecture().operations().get(0);
        assertEquals(Operation.Type.RECEPTION, recu.type);
        assertEquals("Awa", recu.pairNom);
        assertEquals(envoi.id, recu.id);
    }

    @Test
    public void unSeulPayeurALaFois() throws Exception {
        Telephone a = new Telephone("Awa");
        Telephone b = new Telephone("Boureima");
        InfosConnexion qr = recevoir(b);
        payeur(a, qr).connecter(LOCAL, SOCKET, 2000);
        assertEquals("Awa", b.evenements.poll(5, TimeUnit.SECONDS));

        try (Socket intrus = new Socket("127.0.0.1", qr.port)) {
            intrus.setSoTimeout(5000);
            assertEquals("la connexion de l'intrus doit être fermée", -1, intrus.getInputStream().read());
        }
        assertEquals("intrus-refuse", b.evenements.poll(5, TimeUnit.SECONDS));

        sessionA.payer(1_000);
        assertEquals("recu:1000", b.evenements.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void unPaiementJamaisArriveEstRenvoyeALaConnexionSuivante() throws Exception {
        Telephone a = new Telephone("Awa");
        Telephone b = new Telephone("Boureima");
        InfosConnexion qr = recevoir(b);
        // Coupure juste après le débit : le paiement n'est jamais parti
        Operation perdu = a.depot.modifier(p -> p.debiter(qr.appareil, qr.nom, 1_500, System.currentTimeMillis()));
        assertEquals(48_500, a.depot.lecture().solde());

        assertEquals(1, payeur(a, qr).connecter(LOCAL, SOCKET, 2000));
        assertEquals("Awa", b.evenements.poll(5, TimeUnit.SECONDS));
        assertEquals("recu:1500", b.evenements.poll(5, TimeUnit.SECONDS));
        assertEquals("confirme:" + perdu.id, a.evenements.poll(5, TimeUnit.SECONDS));
        assertEquals(51_500, b.depot.lecture().solde());
        assertEquals(48_500, a.depot.lecture().solde());
    }

    @Test
    public void unRecuPerduNeProvoqueJamaisDeDoubleCredit() throws Exception {
        Telephone a = new Telephone("Awa");
        Telephone b = new Telephone("Boureima");
        InfosConnexion qr = recevoir(b);
        // B a reçu et crédité le paiement, mais le reçu n'est jamais arrivé chez A
        Operation envoi = a.depot.modifier(p -> p.debiter(qr.appareil, qr.nom, 1_500, System.currentTimeMillis()));
        final String idA = a.depot.lecture().appareil();
        b.depot.modifier(p -> p.crediter(envoi.id, idA, "Awa", 1_500, System.currentTimeMillis()));

        payeur(a, qr).connecter(LOCAL, SOCKET, 2000);
        assertEquals("Awa", b.evenements.poll(5, TimeUnit.SECONDS));
        assertEquals("deja-recu", b.evenements.poll(5, TimeUnit.SECONDS));
        assertEquals("confirme:" + envoi.id, a.evenements.poll(5, TimeUnit.SECONDS));
        assertEquals("pas de second crédit", 51_500, b.depot.lecture().solde());
        assertEquals(Operation.Statut.CONFIRME, a.depot.lecture().operations().get(0).statut);
    }

    @Test
    public void unFauxQrCodeEmpecheLaConnexion() throws Exception {
        Telephone a = new Telephone("Awa");
        Telephone b = new Telephone("Boureima");
        InfosConnexion vrai = recevoir(b);
        // Un fraudeur remplace la clé publique du receveur dans le QR code
        InfosConnexion faux = new InfosConnexion(vrai.ssid, vrai.motDePasse, vrai.securite, vrai.port,
                vrai.adresses, new EchangeCles().clePublique(), vrai.session, vrai.nom, vrai.appareil);
        try {
            payeur(a, faux).connecter(LOCAL, SOCKET, 2000);
            fail("la connexion aurait dû être refusée");
        } catch (ProtocoleException attendue) {
            // le receveur n'a pas pu déchiffrer la présentation
        }
        assertTrue(b.evenements.poll(5, TimeUnit.SECONDS).startsWith("fin:Message rejeté"));
        assertEquals(50_000, a.depot.lecture().solde());
        assertEquals(50_000, b.depot.lecture().solde());
    }

    @Test
    public void unMessageModifieEnRouteEstRejete() throws Exception {
        Telephone a = new Telephone("Awa");
        Telephone b = new Telephone("Boureima");
        InfosConnexion qr = recevoir(b);
        try (ProxyMalveillant proxy = new ProxyMalveillant(qr.port, 2)) {
            InfosConnexion viaProxy = new InfosConnexion(qr.ssid, qr.motDePasse, qr.securite, proxy.port(),
                    qr.adresses, qr.clePublique, qr.session, qr.nom, qr.appareil);
            payeur(a, viaProxy).connecter(LOCAL, SOCKET, 2000);
            assertEquals("Awa", b.evenements.poll(5, TimeUnit.SECONDS));
            sessionA.payer(2_000); // le proxy modifie un octet de cet ordre de paiement
            assertTrue(b.evenements.poll(5, TimeUnit.SECONDS).startsWith("fin:Message rejeté"));
        }
        assertEquals("rien n'est crédité", 50_000, b.depot.lecture().solde());
        Operation op = a.depot.lecture().operations().get(0);
        assertEquals("l'argent reste en attente, il sera renvoyé", Operation.Statut.EN_ATTENTE, op.statut);
    }

    @Test
    public void unSoldeInsuffisantNeDebiteRien() throws Exception {
        Telephone a = new Telephone("Awa");
        Telephone b = new Telephone("Boureima");
        payeur(a, recevoir(b)).connecter(LOCAL, SOCKET, 2000);
        try {
            sessionA.payer(60_000);
            fail("solde insuffisant attendu");
        } catch (ErreurPaiement attendue) {
            assertEquals("Solde insuffisant.", attendue.getMessage());
        }
        assertEquals(50_000, a.depot.lecture().solde());
        assertEquals(50_000, b.depot.lecture().solde());
    }

    @Test
    public void uneApplicationFalsifieeNePeutPasPayer() throws Exception {
        Telephone a = new Telephone("Awa", "La signature de cette application n'est pas valide.");
        Telephone b = new Telephone("Boureima");
        payeur(a, recevoir(b)).connecter(LOCAL, SOCKET, 2000);
        try {
            sessionA.payer(1_000);
            fail("falsification attendue");
        } catch (Falsification attendue) {
            assertTrue(attendue.getMessage().contains("signature"));
        }
        assertEquals(50_000, a.depot.lecture().solde());
    }

    @Test
    public void leQrCodeSeRelitEtRefuseLesQrEtrangers() throws Exception {
        Telephone b = new Telephone("Boureima");
        InfosConnexion qr = recevoir(b);
        InfosConnexion relu = InfosConnexion.decoder(qr.encoder());
        assertEquals(qr.port, relu.port);
        assertEquals(qr.clePublique, relu.clePublique);
        assertEquals("Boureima", relu.nom);
        assertEquals(LOCAL, relu.adresses);
        for (String etranger : new String[]{"https://exemple.bf", "PHL1:ssid=x", "PHL1:" + qr.encoder()}) {
            try {
                InfosConnexion.decoder(etranger);
                fail("QR refusé attendu : " + etranger);
            } catch (bf.pegasus.paiement.metier.Message.FormatInvalide attendue) {
                // normal
            }
        }
        assertFalse(InfosConnexion.estIpv4("192.168.1.300"));
        assertTrue(InfosConnexion.estIpv4("192.168.43.1"));
    }

    // ----------------------------------------------------------------- outils

    private InfosConnexion recevoir(Telephone b) throws Exception {
        sessionB = new SessionReceveur(b.depot, b);
        return sessionB.demarrer("PHL-test", "motdepasse", "WPA2", LOCAL);
    }

    private SessionPayeur payeur(Telephone a, InfosConnexion qr) {
        sessionA = new SessionPayeur(a.depot, qr, a);
        return sessionA;
    }

    /** Un téléphone simulé : son portefeuille et le journal de ce que ses écrans afficheraient. */
    static final class Telephone implements SessionReceveur.Vue, SessionPayeur.Vue {
        final Depot depot;
        final BlockingQueue<String> evenements = new LinkedBlockingQueue<>();

        Telephone(String nom) throws Exception {
            this(nom, null);
        }

        Telephone(String nom, final String falsification) throws Exception {
            final AtomicReference<String> disque = new AtomicReference<>();
            depot = Depot.ouvrir(new Depot.Stockage() {
                @Override
                public String lire() {
                    return disque.get();
                }

                @Override
                public void ecrire(String contenu) {
                    disque.set(contenu);
                }
            }, () -> {
                if (falsification != null) {
                    throw new Falsification(falsification);
                }
            }, Portefeuille.SOLDE_DEMO);
            depot.modifier(p -> {
                p.renommer(nom);
                return null;
            });
        }

        @Override public void payeurConnecte(String nomPayeur) { evenements.add(nomPayeur); }
        @Override public void paiementRecu(Operation op) { evenements.add("recu:" + op.montant); }
        @Override public void paiementDejaRecu(String id) { evenements.add("deja-recu"); }
        @Override public void paiementRefuse(String motif) { evenements.add("refus:" + motif); }
        @Override public void connexionRefusee() { evenements.add("intrus-refuse"); }
        @Override public void paiementConfirme(Operation op) { evenements.add("confirme:" + op.id); }
        @Override public void paiementRefuse(String id, String motif) { evenements.add("refus:" + motif); }

        @Override
        public void terminee(String raison) {
            if (raison != null) {
                evenements.add("fin:" + raison);
            }
        }
    }

    /** Relais placé entre les deux téléphones, qui modifie un octet de la n-ième ligne du payeur. */
    static final class ProxyMalveillant implements AutoCloseable {
        private final ServerSocket serveur = new ServerSocket(0);

        ProxyMalveillant(final int portReel, final int ligneAModifier) throws IOException {
            Thread t = new Thread(() -> {
                try (Socket versA = serveur.accept(); Socket versB = new Socket("127.0.0.1", portReel)) {
                    Thread retour = new Thread(() -> copier(sansErreur(versB), sortie(versA), -1));
                    retour.setDaemon(true);
                    retour.start();
                    copier(versA.getInputStream(), versB.getOutputStream(), ligneAModifier);
                } catch (IOException ignoree) {
                    // fin du test
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return serveur.getLocalPort();
        }

        private static void copier(InputStream in, OutputStream out, int ligneAModifier) {
            try {
                int ligne = 0;
                int position = 0;
                int b;
                while ((b = in.read()) != -1) {
                    if (ligne == ligneAModifier && position == 20) {
                        b = (b == 'A') ? 'B' : 'A';
                    }
                    out.write(b);
                    position++;
                    if (b == '\n') {
                        out.flush();
                        ligne++;
                        position = 0;
                    }
                }
            } catch (IOException ignoree) {
                // connexion fermée
            }
        }

        private static InputStream sansErreur(Socket s) {
            try {
                return s.getInputStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static OutputStream sortie(Socket s) {
            try {
                return s.getOutputStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() throws IOException {
            serveur.close();
        }
    }
}
