package bf.pegasus.paiement.metier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import bf.pegasus.paiement.crypto.Octets;

public class PortefeuilleTest {

    private static final long T = 1_790_000_000_000L;

    private static Portefeuille nouveau() {
        return Portefeuille.creer(Octets.hex(Octets.aleatoires(8)), Portefeuille.SOLDE_DEMO, T);
    }

    @Test
    public void unNouveauPortefeuilleALeSoldeDeDemonstration() throws Exception {
        Portefeuille p = nouveau();
        assertEquals(50_000, p.solde());
        assertEquals(Operation.Type.DEMO, p.operations().get(0).type);
        p.verifierIntegrite();
    }

    @Test
    public void lEnvoiVerifieLeSoldePuisDebite() throws Exception {
        Portefeuille a = nouveau();
        Portefeuille b = nouveau();
        Operation op = a.debiter(b.appareil(), "Fatou", 2_500, T);
        assertEquals(47_500, a.solde());
        assertEquals(Operation.Statut.EN_ATTENTE, op.statut);
        assertEquals(op, a.operations().get(0));
        a.verifierIntegrite();
    }

    @Test
    public void lesEnvoisImpossiblesSontRefusesSansToucherAuSolde() {
        Portefeuille a = nouveau();
        refuse(a, "autre0123456789a", 0);
        refuse(a, "0123456789abcdef", 60_000);
        refuse(a, "0123456789abcdef", Portefeuille.MONTANT_MAX + 1);
        refuse(a, a.appareil(), 100);
        assertEquals(50_000, a.solde());
    }

    @Test
    public void laReceptionCreditePuisLeRecuConfirme() throws Exception {
        Portefeuille a = nouveau();
        Portefeuille b = nouveau();
        Operation envoi = a.debiter(b.appareil(), "B", 3_000, T);
        Portefeuille.Reception r = b.crediter(envoi.id, a.appareil(), "A", 3_000, T);
        assertEquals(Portefeuille.Reception.Etat.ACCEPTE, r.etat);
        assertEquals(53_000, b.solde());
        assertEquals(Operation.Statut.CONFIRME, a.confirmer(envoi.id).statut);
        assertEquals(Operation.Statut.CONFIRME, a.operations().get(0).statut);
        assertEquals(100_000, a.solde() + b.solde());
    }

    @Test
    public void unPaiementRejoueNestJamaisCrediteDeuxFois() throws Exception {
        Portefeuille a = nouveau();
        Portefeuille b = nouveau();
        Operation envoi = a.debiter(b.appareil(), "B", 3_000, T);
        b.crediter(envoi.id, a.appareil(), "A", 3_000, T);
        Portefeuille.Reception encore = b.crediter(envoi.id, a.appareil(), "A", 3_000, T);
        assertEquals(Portefeuille.Reception.Etat.DEJA_RECU, encore.etat);
        assertEquals(53_000, b.solde());
        assertEquals(2, b.operations().size());
    }

    @Test
    public void unPaiementInvalideEstRefuse() {
        Portefeuille b = nouveau();
        assertEquals(Portefeuille.Reception.Etat.REFUSE,
                b.crediter(Octets.identifiant(), "0123456789abcdef", "X", -5, T).etat);
        assertEquals(Portefeuille.Reception.Etat.REFUSE,
                b.crediter("pas-un-identifiant", "0123456789abcdef", "X", 100, T).etat);
        assertEquals(Portefeuille.Reception.Etat.REFUSE,
                b.crediter(Octets.identifiant(), b.appareil(), "Moi", 100, T).etat);
        assertEquals(50_000, b.solde());
    }

    @Test
    public void lesEnvoisSansRecuSontRetrouvesPourEtreRenvoyes() throws Exception {
        Portefeuille a = nouveau();
        Operation o1 = a.debiter("0123456789abcdef", "B", 1_000, T);
        Operation o2 = a.debiter("0123456789abcdef", "B", 2_000, T + 1);
        a.debiter("fedcba9876543210", "C", 500, T + 2);
        a.confirmer(o1.id);
        assertEquals(1, a.enAttentePour("0123456789abcdef").size());
        assertEquals(o2.id, a.enAttentePour("0123456789abcdef").get(0).id);
        assertNull(a.confirmer(Octets.identifiant()));
    }

    @Test
    public void laSauvegardeEstRelueALidentique() throws Exception {
        Portefeuille a = nouveau();
        a.renommer("  Awa   Sawadogo  ");
        Operation envoi = a.debiter("0123456789abcdef", "Moussa & Fils = 1", 4_000, T);
        a.crediter(Octets.identifiant(), "fedcba9876543210", "Issouf\nOuédraogo", 1_500, T);
        Portefeuille relu = Portefeuille.charger(a.serialiser());
        assertEquals("Awa Sawadogo", relu.nom());
        assertEquals(a.solde(), relu.solde());
        assertEquals(3, relu.operations().size());
        assertEquals("Issouf Ouédraogo", relu.operations().get(0).pairNom);
        assertEquals("Moussa & Fils = 1", relu.operations().get(1).pairNom);
        assertEquals(envoi.id, relu.operations().get(1).id);
        assertTrue(relu.dejaRecu(relu.operations().get(0).id));
    }

    @Test
    public void unSoldeModifieALaMainEstDetecte() throws Exception {
        Portefeuille a = nouveau();
        String falsifie = a.serialiser().replace("solde=50000", "solde=900000");
        try {
            Portefeuille.charger(falsifie);
            fail("la falsification aurait dû être détectée");
        } catch (Falsification attendue) {
            assertTrue(attendue.getMessage().contains("solde"));
        }
    }

    @Test
    public void uneOperationEffaceeDeLHistoriqueEstDetectee() throws Exception {
        Portefeuille a = nouveau();
        a.debiter("0123456789abcdef", "B", 4_000, T);
        StringBuilder sansEnvoi = new StringBuilder();
        for (String ligne : a.serialiser().split("\n")) {
            if (!ligne.contains("type=ENVOI")) {
                sansEnvoi.append(ligne).append('\n');
            }
        }
        // (Une modification « cohérente » du solde ET de l'historique est empêchée en amont :
        // sur le téléphone, le portefeuille est chiffré en AES-256-GCM par une clé du Keystore.)
        try {
            Portefeuille.charger(sansEnvoi.toString());
            fail("la falsification aurait dû être détectée");
        } catch (Falsification attendue) {
            // l'historique ne correspond plus au solde
        }
    }

    @Test(expected = Falsification.class)
    public void unFichierIllisibleEstUneFalsification() throws Exception {
        Portefeuille.charger("n'importe quoi");
    }

    @Test
    public void lesNomsSontNettoyes() {
        assertEquals("Awa Sawadogo", Portefeuille.nettoyerNom("  Awa \t\n Sawadogo "));
        assertEquals(30, Portefeuille.nettoyerNom("0123456789012345678901234567890123456789").length());
        assertEquals("", Portefeuille.nettoyerNom(null));
    }

    @Test
    public void leDepotNeChangeRienSiLEnregistrementEchoue() throws Exception {
        final AtomicReference<String> disque = new AtomicReference<>();
        final boolean[] panne = {false};
        Depot.Stockage stockage = new Depot.Stockage() {
            @Override
            public String lire() {
                return disque.get();
            }

            @Override
            public void ecrire(String contenu) throws IOException {
                if (panne[0]) {
                    throw new IOException("disque plein");
                }
                disque.set(contenu);
            }
        };
        Depot depot = Depot.ouvrir(stockage, () -> { }, 50_000);
        panne[0] = true;
        try {
            depot.modifier(p -> p.debiter("0123456789abcdef", "B", 1_000, T));
            fail("l'enregistrement aurait dû échouer");
        } catch (IOException attendue) {
            // tout ou rien
        }
        assertEquals(50_000, depot.lecture().solde());
        assertEquals(50_000, Portefeuille.charger(disque.get()).solde());
    }

    @Test
    public void leDepotRelitLePortefeuilleEnregistre() throws Exception {
        final AtomicReference<String> disque = new AtomicReference<>();
        Depot.Stockage stockage = new Depot.Stockage() {
            @Override
            public String lire() {
                return disque.get();
            }

            @Override
            public void ecrire(String contenu) {
                disque.set(contenu);
            }
        };
        Depot d1 = Depot.ouvrir(stockage, () -> { }, 50_000);
        d1.modifier(p -> p.debiter("0123456789abcdef", "B", 7_000, T));
        Depot d2 = Depot.ouvrir(stockage, () -> { }, 50_000);
        assertEquals(43_000, d2.lecture().solde());
        assertEquals(d1.lecture().appareil(), d2.lecture().appareil());
    }

    private static void refuse(Portefeuille p, String dest, long montant) {
        try {
            p.debiter(dest, "X", montant, T);
            fail("le paiement de " + montant + " aurait dû être refusé");
        } catch (ErreurPaiement attendue) {
            // normal
        }
    }
}
