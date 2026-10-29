package bf.pegasus.paiement.ui;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;

import bf.pegasus.paiement.metier.Depot;
import bf.pegasus.paiement.metier.Falsification;
import bf.pegasus.paiement.metier.Portefeuille;
import bf.pegasus.paiement.securite.VerificateurIntegrite;
import bf.pegasus.paiement.stockage.CoffreSecurise;

/**
 * Démarrage de l'application : contrôle de la signature, puis ouverture du coffre.
 * Si l'un des deux échoue, le portefeuille n'est pas ouvert et tous les écrans
 * redirigent vers « Application falsifiée ».
 */
public final class App extends Application {

    private Depot depot;
    private volatile String motifFalsification;
    private String empreinte = "";

    @Override
    public void onCreate() {
        super.onCreate();
        final Context contexte = this;
        try {
            empreinte = VerificateurIntegrite.empreinteSignature(this);
            VerificateurIntegrite.verifier(this);
            depot = Depot.ouvrir(new CoffreSecurise(this), new Depot.ControleIntegrite() {
                @Override
                public void verifier() throws Falsification {
                    VerificateurIntegrite.verifier(contexte);
                }
            }, Portefeuille.SOLDE_DEMO);
        } catch (Falsification f) {
            motifFalsification = f.getMessage();
        } catch (IOException | RuntimeException e) {
            motifFalsification = "Stockage sécurisé inaccessible : " + e.getMessage();
        }
    }

    public Depot depot() {
        return depot;
    }

    /** @return {@code null} si l'application est intègre */
    public String motifFalsification() {
        return motifFalsification;
    }

    public void signalerFalsification(String motif) {
        motifFalsification = motif;
    }

    public String empreinte() {
        return empreinte;
    }

    public SharedPreferences preferences() {
        return getSharedPreferences("pegasus", MODE_PRIVATE);
    }

    public boolean soldeMasque() {
        return preferences().getBoolean("solde_masque", false);
    }

    public void masquerSolde(boolean masque) {
        preferences().edit().putBoolean("solde_masque", masque).apply();
    }
}
