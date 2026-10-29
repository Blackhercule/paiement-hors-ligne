package bf.pegasus.paiement.reseau;

/** L'autre téléphone ne respecte pas le protocole de transfert. */
public final class ProtocoleException extends Exception {
    public ProtocoleException(String message) {
        super(message);
    }
}
