package bf.pegasus.paiement.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement AES-256-GCM.
 *
 * <p>GCM chiffre le message ET vérifie qu'il n'a pas été modifié : un seul octet changé
 * et le déchiffrement échoue ({@link AEADBadTagException}). Chaque message reçoit un nonce
 * aléatoire de 96 bits, placé devant le texte chiffré.</p>
 */
public final class ChiffrementAes {

    public static final int TAILLE_CLE = 32;      // 256 bits
    public static final int TAILLE_NONCE = 12;    // 96 bits
    private static final int TAILLE_TAG_BITS = 128;
    private static final String ALGORITHME = "AES/GCM/NoPadding";

    private ChiffrementAes() {
    }

    /** @return nonce (12 octets) suivi du texte chiffré et de son étiquette d'authentification */
    public static byte[] chiffrer(byte[] cle, byte[] clair, byte[] donneesAssociees)
            throws GeneralSecurityException {
        verifierCle(cle);
        byte[] nonce = Octets.aleatoires(TAILLE_NONCE);
        Cipher c = Cipher.getInstance(ALGORITHME);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cle, "AES"), new GCMParameterSpec(TAILLE_TAG_BITS, nonce));
        if (donneesAssociees != null) {
            c.updateAAD(donneesAssociees);
        }
        byte[] chiffre = c.doFinal(clair);
        byte[] paquet = new byte[nonce.length + chiffre.length];
        System.arraycopy(nonce, 0, paquet, 0, nonce.length);
        System.arraycopy(chiffre, 0, paquet, nonce.length, chiffre.length);
        return paquet;
    }

    /** @throws AEADBadTagException si la clé est fausse ou si le paquet a été modifié */
    public static byte[] dechiffrer(byte[] cle, byte[] paquet, byte[] donneesAssociees)
            throws GeneralSecurityException {
        verifierCle(cle);
        if (paquet == null || paquet.length < TAILLE_NONCE + TAILLE_TAG_BITS / 8) {
            throw new AEADBadTagException("Paquet trop court");
        }
        Cipher c = Cipher.getInstance(ALGORITHME);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cle, "AES"),
                new GCMParameterSpec(TAILLE_TAG_BITS, paquet, 0, TAILLE_NONCE));
        if (donneesAssociees != null) {
            c.updateAAD(donneesAssociees);
        }
        return c.doFinal(paquet, TAILLE_NONCE, paquet.length - TAILLE_NONCE);
    }

    private static void verifierCle(byte[] cle) {
        if (cle == null || cle.length != TAILLE_CLE) {
            throw new IllegalArgumentException("La clé AES doit faire 256 bits");
        }
    }
}
