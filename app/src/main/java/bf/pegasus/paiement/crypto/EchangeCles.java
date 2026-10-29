package bf.pegasus.paiement.crypto;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;

/**
 * Échange de clés ECDH sur la courbe P-256, avec des clés éphémères :
 * une paire neuve à chaque session, jetée à la fin.
 *
 * <p>Les deux téléphones échangent leurs clés publiques et calculent chacun le même secret,
 * sans jamais le faire circuler. Ce secret passe ensuite par HKDF pour donner la clé AES-256.</p>
 */
public final class EchangeCles {

    private static final int TAILLE_MAX_CLE_PUBLIQUE = 200;

    private final KeyPair paire;

    public EchangeCles() throws GeneralSecurityException {
        KeyPairGenerator generateur = KeyPairGenerator.getInstance("EC");
        generateur.initialize(new ECGenParameterSpec("secp256r1"));
        paire = generateur.generateKeyPair();
    }

    /** Clé publique encodée (X.509, base64) : elle peut circuler sans danger. */
    public String clePublique() {
        return Octets.base64(paire.getPublic().getEncoded());
    }

    /**
     * Calcule la clé de session AES-256 partagée avec le pair.
     *
     * @param clePubliquePair clé publique de l'autre téléphone
     * @param sel             numéro de session (rend la clé propre à cette session)
     * @param contexte        texte qui lie la clé aux deux clés publiques échangées
     */
    public byte[] cleSession(String clePubliquePair, byte[] sel, String contexte) throws GeneralSecurityException {
        PublicKey pair = lireClePublique(clePubliquePair);
        KeyAgreement accord = KeyAgreement.getInstance("ECDH");
        accord.init(paire.getPrivate());
        accord.doPhase(pair, true);
        byte[] secret = accord.generateSecret();
        try {
            return Hkdf.deriver(sel, secret, Octets.utf8(contexte), ChiffrementAes.TAILLE_CLE);
        } finally {
            Octets.effacer(secret);
        }
    }

    static PublicKey lireClePublique(String encodee) throws GeneralSecurityException {
        byte[] octets;
        try {
            octets = Octets.deBase64(encodee);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeyException("Clé publique illisible");
        }
        if (octets.length > TAILLE_MAX_CLE_PUBLIQUE) {
            throw new InvalidKeyException("Clé publique trop longue");
        }
        PublicKey cle = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(octets));
        if (!(cle instanceof ECPublicKey)
                || ((ECPublicKey) cle).getParams().getCurve().getField().getFieldSize() != 256) {
            throw new InvalidKeyException("La clé doit être une clé P-256");
        }
        return cle;
    }
}
