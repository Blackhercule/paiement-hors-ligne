package bf.pegasus.paiement.metier;

/** Paiement impossible pour une raison normale (solde insuffisant, montant vide...). */
public final class ErreurPaiement extends Exception {
    public ErreurPaiement(String message) {
        super(message);
    }
}
