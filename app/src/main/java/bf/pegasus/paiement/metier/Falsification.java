package bf.pegasus.paiement.metier;

/**
 * L'application ou ses données ont été modifiées : signature invalide, coffre altéré,
 * solde incohérent. L'application affiche alors « Application falsifiée » et se bloque.
 */
public final class Falsification extends Exception {
    public Falsification(String message) {
        super(message);
    }
}
