package bf.pegasus.paiement.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.AEADBadTagException;

/**
 * Canal chiffré AES-256-GCM entre le payeur et le receveur.
 *
 * <p>Chaque message est lié à son sens (payeur vers receveur, ou l'inverse) et à son numéro
 * d'ordre, passés en « données associées » à GCM. Un message rejoué, renvoyé à son expéditeur
 * ou reçu dans le désordre est donc rejeté.</p>
 */
public final class CanalChiffre {

    public enum Role { PAYEUR, RECEVEUR }

    private final byte[] cle;
    private final String sensEnvoi;
    private final String sensReception;
    private long numeroEnvoi;
    private long numeroReception;

    public CanalChiffre(byte[] cle, Role role) {
        if (cle == null || cle.length != ChiffrementAes.TAILLE_CLE) {
            throw new IllegalArgumentException("Clé de session invalide");
        }
        this.cle = cle.clone();
        this.sensEnvoi = role == Role.PAYEUR ? "P>R" : "R>P";
        this.sensReception = role == Role.PAYEUR ? "R>P" : "P>R";
    }

    public synchronized String sceller(String clair) throws GeneralSecurityException {
        byte[] paquet = ChiffrementAes.chiffrer(cle, Octets.utf8(clair), donneesAssociees(sensEnvoi, numeroEnvoi));
        numeroEnvoi++;
        return Octets.base64(paquet);
    }

    /** @throws AEADBadTagException si le message est faux, modifié, rejoué ou dans le désordre */
    public synchronized String ouvrir(String scelle) throws GeneralSecurityException {
        byte[] paquet;
        try {
            paquet = Octets.deBase64(scelle);
        } catch (IllegalArgumentException e) {
            throw new AEADBadTagException("Message illisible");
        }
        byte[] clair = ChiffrementAes.dechiffrer(cle, paquet, donneesAssociees(sensReception, numeroReception));
        numeroReception++;
        return Octets.texte(clair);
    }

    /** Efface la clé de session : appelé à la fin de chaque session. */
    public synchronized void effacer() {
        Octets.effacer(cle);
    }

    private static byte[] donneesAssociees(String sens, long numero) {
        return Octets.utf8("PHL1|" + sens + "|" + numero);
    }
}
