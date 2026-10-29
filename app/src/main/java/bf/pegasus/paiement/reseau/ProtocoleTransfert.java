package bf.pegasus.paiement.reseau;

/**
 * Description du protocole de transfert entre deux téléphones (version 1).
 *
 * <pre>
 *  Payeur (A)                                          Receveur (B)
 *     |            [B affiche le QR : Wi-Fi, port, clé publique pkB, session]
 *     | --- bonjour  (en clair) : session, pkA ----------------> |
 *     |      les deux calculent : ECDH P-256 -> HKDF-SHA-256 -> clé AES-256
 *     | === presentation (chiffré) : identifiant, nom ==========> |  (preuve de la clé)
 *     | <== bienvenue    (chiffré) : identifiant, nom =========== |
 *     | === paiement     (chiffré) : id, montant, horodatage ===> |  A a déjà débité
 *     | <== recu         (chiffré) : id ======================== |  B a crédité et noté l'id
 *     | === fin ===============================================> |
 * </pre>
 *
 * <p>Chaque message chiffré porte son sens et son numéro d'ordre (voir {@code CanalChiffre}).
 * Un paiement resté « en attente » après une coupure est renvoyé avec le même identifiant
 * à la connexion suivante avec le même receveur : B répond par un reçu sans recréditer.</p>
 */
public final class ProtocoleTransfert {

    public static final String VERSION = "1";
    /** Délai maximum pour la poignée de main. */
    static final int DELAI_POIGNEE_MS = 15_000;

    private ProtocoleTransfert() {
    }

    /** Texte qui lie la clé de session aux deux clés publiques échangées. */
    static String contexte(String clePubliquePayeur, String clePubliqueReceveur) {
        return "PHL1|session|" + clePubliquePayeur + "|" + clePubliqueReceveur;
    }
}
