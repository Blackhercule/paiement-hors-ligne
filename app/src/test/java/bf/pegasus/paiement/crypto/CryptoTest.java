package bf.pegasus.paiement.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.security.GeneralSecurityException;

import org.junit.Test;

public class CryptoTest {

    @Test
    public void aesGcmChiffreEtDechiffre() throws Exception {
        byte[] cle = Octets.aleatoires(32);
        byte[] paquet = ChiffrementAes.chiffrer(cle, Octets.utf8("montant=2500"), Octets.utf8("aad"));
        assertFalse("le texte clair ne doit pas apparaître",
                Octets.texte(paquet).contains("montant"));
        assertEquals("montant=2500", Octets.texte(ChiffrementAes.dechiffrer(cle, paquet, Octets.utf8("aad"))));
    }

    @Test
    public void aesGcmRefuseUneMauvaiseCle() throws Exception {
        byte[] paquet = ChiffrementAes.chiffrer(Octets.aleatoires(32), Octets.utf8("secret"), null);
        doitEchouer(() -> ChiffrementAes.dechiffrer(Octets.aleatoires(32), paquet, null));
    }

    @Test
    public void aesGcmDetecteUnOctetModifie() throws Exception {
        byte[] cle = Octets.aleatoires(32);
        byte[] paquet = ChiffrementAes.chiffrer(cle, Octets.utf8("montant=2500"), null);
        paquet[paquet.length - 5] ^= 1;
        doitEchouer(() -> ChiffrementAes.dechiffrer(cle, paquet, null));
    }

    @Test
    public void aesGcmVerifieLesDonneesAssociees() throws Exception {
        byte[] cle = Octets.aleatoires(32);
        byte[] paquet = ChiffrementAes.chiffrer(cle, Octets.utf8("x"), Octets.utf8("P>R|0"));
        doitEchouer(() -> ChiffrementAes.dechiffrer(cle, paquet, Octets.utf8("P>R|1")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aesExigeUneCleDe256Bits() throws Exception {
        ChiffrementAes.chiffrer(Octets.aleatoires(16), Octets.utf8("x"), null);
    }

    /** Vecteur de test officiel n°1 de la RFC 5869. */
    @Test
    public void hkdfRespecteLaRfc5869() throws Exception {
        byte[] ikm = Octets.deHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] sel = Octets.deHex("000102030405060708090a0b0c");
        byte[] info = Octets.deHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] attendu = Octets.deHex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865");
        assertArrayEquals(attendu, Hkdf.deriver(sel, ikm, info, 42));
    }

    @Test
    public void ecdhDonneLaMemeCleDesDeuxCotes() throws Exception {
        EchangeCles a = new EchangeCles();
        EchangeCles b = new EchangeCles();
        byte[] sel = Octets.aleatoires(8);
        byte[] cleA = a.cleSession(b.clePublique(), sel, "ctx");
        byte[] cleB = b.cleSession(a.clePublique(), sel, "ctx");
        assertEquals(32, cleA.length);
        assertArrayEquals(cleA, cleB);
    }

    @Test
    public void ecdhAvecUnAutreTelephoneDonneUneAutreCle() throws Exception {
        EchangeCles a = new EchangeCles();
        EchangeCles b = new EchangeCles();
        EchangeCles intrus = new EchangeCles();
        byte[] sel = Octets.aleatoires(8);
        assertFalse(Octets.egaux(a.cleSession(b.clePublique(), sel, "ctx"),
                intrus.cleSession(a.clePublique(), sel, "ctx")));
    }

    @Test
    public void ecdhRefuseUneClePubliqueInvalide() throws Exception {
        EchangeCles a = new EchangeCles();
        doitEchouer(() -> a.cleSession("pas-une-cle", new byte[8], "ctx"));
        doitEchouer(() -> a.cleSession(Octets.base64(Octets.aleatoires(91)), new byte[8], "ctx"));
    }

    @Test
    public void canalAcheminePlusieursMessagesDansLesDeuxSens() throws Exception {
        byte[] cle = Octets.aleatoires(32);
        CanalChiffre payeur = new CanalChiffre(cle, CanalChiffre.Role.PAYEUR);
        CanalChiffre receveur = new CanalChiffre(cle, CanalChiffre.Role.RECEVEUR);
        assertEquals("un", receveur.ouvrir(payeur.sceller("un")));
        assertEquals("deux", receveur.ouvrir(payeur.sceller("deux")));
        assertEquals("reçu", payeur.ouvrir(receveur.sceller("reçu")));
    }

    @Test
    public void canalRejetteUnMessageRejoue() throws Exception {
        byte[] cle = Octets.aleatoires(32);
        CanalChiffre payeur = new CanalChiffre(cle, CanalChiffre.Role.PAYEUR);
        CanalChiffre receveur = new CanalChiffre(cle, CanalChiffre.Role.RECEVEUR);
        String paiement = payeur.sceller("paiement 2500");
        receveur.ouvrir(paiement);
        doitEchouer(() -> receveur.ouvrir(paiement));
    }

    @Test
    public void canalRejetteUnMessageRenvoyeASonExpediteur() throws Exception {
        CanalChiffre payeur = new CanalChiffre(Octets.aleatoires(32), CanalChiffre.Role.PAYEUR);
        String m = payeur.sceller("paiement");
        doitEchouer(() -> payeur.ouvrir(m));
    }

    @Test
    public void canalRejetteLesMessagesDansLeDesordre() throws Exception {
        byte[] cle = Octets.aleatoires(32);
        CanalChiffre payeur = new CanalChiffre(cle, CanalChiffre.Role.PAYEUR);
        CanalChiffre receveur = new CanalChiffre(cle, CanalChiffre.Role.RECEVEUR);
        payeur.sceller("premier");
        String second = payeur.sceller("second");
        doitEchouer(() -> receveur.ouvrir(second));
    }

    interface Action {
        void executer() throws Exception;
    }

    static void doitEchouer(Action a) throws Exception {
        try {
            a.executer();
            fail("une erreur de sécurité était attendue");
        } catch (GeneralSecurityException attendue) {
            // c'est le comportement voulu
        }
    }
}
