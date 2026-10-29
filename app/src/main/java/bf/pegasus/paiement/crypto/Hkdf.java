package bf.pegasus.paiement.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA-256 (RFC 5869) : transforme le secret brut issu de l'échange ECDH
 * en une clé AES-256 propre, liée à la session.
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int TAILLE_HASH = 32;

    private Hkdf() {
    }

    public static byte[] deriver(byte[] sel, byte[] secret, byte[] info, int longueur)
            throws GeneralSecurityException {
        if (longueur <= 0 || longueur > 255 * TAILLE_HASH) {
            throw new IllegalArgumentException("Longueur HKDF invalide");
        }
        Mac mac = Mac.getInstance(HMAC);

        // 1. Extraction : PRK = HMAC(sel, secret)
        byte[] s = (sel == null || sel.length == 0) ? new byte[TAILLE_HASH] : sel;
        mac.init(new SecretKeySpec(s, HMAC));
        byte[] prk = mac.doFinal(secret);

        // 2. Expansion : T(i) = HMAC(PRK, T(i-1) | info | i)
        mac.init(new SecretKeySpec(prk, HMAC));
        byte[] resultat = new byte[longueur];
        byte[] t = new byte[0];
        int position = 0;
        for (int i = 1; position < longueur; i++) {
            mac.update(t);
            if (info != null) {
                mac.update(info);
            }
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, longueur - position);
            System.arraycopy(t, 0, resultat, position, n);
            position += n;
        }
        Octets.effacer(prk);
        Octets.effacer(t);
        return resultat;
    }
}
