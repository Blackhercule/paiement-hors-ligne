package bf.pegasus.paiement.metier;

import java.io.IOException;

import bf.pegasus.paiement.crypto.Octets;

/**
 * Point d'accès unique au portefeuille, partagé par tous les écrans et les sessions réseau.
 *
 * <p>Chaque modification est faite sur une copie, enregistrée dans le coffre chiffré, puis
 * seulement rendue visible : c'est « tout ou rien ». Si l'enregistrement échoue, le solde
 * ne bouge pas.</p>
 */
public final class Depot {

    /** Là où le portefeuille est enregistré (sur Android : le coffre chiffré par le Keystore). */
    public interface Stockage {
        /** @return le contenu enregistré, ou {@code null} au tout premier lancement */
        String lire() throws IOException, Falsification;

        void ecrire(String contenu) throws IOException;
    }

    /** Contrôle de l'application elle-même (sur Android : signature de l'APK). */
    public interface ControleIntegrite {
        void verifier() throws Falsification;
    }

    /** Modification à appliquer au portefeuille. */
    public interface Modification<T> {
        T appliquer(Portefeuille p) throws ErreurPaiement;
    }

    private final Stockage stockage;
    private final ControleIntegrite controle;
    private Portefeuille courant;

    private Depot(Stockage stockage, ControleIntegrite controle, Portefeuille courant) {
        this.stockage = stockage;
        this.controle = controle;
        this.courant = courant;
    }

    /** Ouvre le portefeuille enregistré, ou en crée un (avec le solde de démonstration). */
    public static Depot ouvrir(Stockage stockage, ControleIntegrite controle, long soldeDemo)
            throws IOException, Falsification {
        String contenu = stockage.lire();
        Portefeuille p;
        if (contenu == null) {
            p = Portefeuille.creer(Octets.hex(Octets.aleatoires(8)), soldeDemo, System.currentTimeMillis());
            stockage.ecrire(p.serialiser());
        } else {
            p = Portefeuille.charger(contenu);
        }
        return new Depot(stockage, controle, p);
    }

    /** Copie du portefeuille pour l'affichage. */
    public synchronized Portefeuille lecture() {
        return courant.copie();
    }

    public synchronized <T> T modifier(Modification<T> modification) throws ErreurPaiement, IOException {
        Portefeuille copie = courant.copie();
        T resultat = modification.appliquer(copie);
        stockage.ecrire(copie.serialiser());
        courant = copie;
        return resultat;
    }

    /** Signature de l'application puis cohérence du portefeuille. */
    public void verifierIntegrite() throws Falsification {
        controle.verifier();
        synchronized (this) {
            courant.verifierIntegrite();
        }
    }
}
