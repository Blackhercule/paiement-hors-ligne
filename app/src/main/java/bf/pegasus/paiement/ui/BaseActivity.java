package bf.pegasus.paiement.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;
import android.widget.Toast;

import bf.pegasus.paiement.R;

/** Base de tous les écrans : blocage si l'application est falsifiée, affichage bord à bord. */
public abstract class BaseActivity extends Activity {

    protected App app() {
        return (App) getApplication();
    }

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (!(this instanceof FalsifieeActivity) && app().motifFalsification() != null) {
            FalsifieeActivity.ouvrir(this, app().motifFalsification());
            finish();
        }
    }

    /** Barre de titre commune : bouton retour et titre. */
    protected void barreTitre(int titre) {
        TextView t = findViewById(R.id.titreEcran);
        t.setText(titre);
        findViewById(R.id.boutonRetour).setOnClickListener(v -> finish());
    }

    protected void message(String texte) {
        Toast.makeText(this, texte, Toast.LENGTH_LONG).show();
    }

    /**
     * Le contenu passe sous la barre d'état ; on le décale de la bonne hauteur.
     *
     * @param racine       vue racine de l'écran (reçoit la marge du bas)
     * @param haut         vue du haut (reçoit la marge de la barre d'état)
     * @param enteteSombre vrai si le haut de l'écran est foncé (icônes système claires)
     */
    @SuppressWarnings("deprecation")
    protected void bordABord(final View racine, final View haut, boolean enteteSombre) {
        Window fenetre = getWindow();
        fenetre.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            fenetre.setDecorFitsSystemWindows(false);
            fenetre.setNavigationBarColor(Color.TRANSPARENT);
            WindowInsetsController controleur = fenetre.getInsetsController();
            if (controleur != null) {
                int clair = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                controleur.setSystemBarsAppearance(enteteSombre ? 0 : clair, clair);
                controleur.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            int drapeaux = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!enteteSombre) {
                drapeaux |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 27) {
                drapeaux |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            fenetre.getDecorView().setSystemUiVisibility(drapeaux);
        }
        final int hautInitial = haut.getPaddingTop();
        final int basInitial = racine.getPaddingBottom();
        racine.setOnApplyWindowInsetsListener((v, insets) -> {
            int dessus;
            int dessous;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                dessus = i.top;
                dessous = i.bottom;
            } else {
                dessus = insets.getSystemWindowInsetTop();
                dessous = 0; // avant Android 11, la barre de navigation ne recouvre pas l'écran
            }
            haut.setPadding(haut.getPaddingLeft(), hautInitial + dessus, haut.getPaddingRight(),
                    haut.getPaddingBottom());
            racine.setPadding(racine.getPaddingLeft(), racine.getPaddingTop(), racine.getPaddingRight(),
                    basInitial + dessous);
            return insets;
        });
        racine.requestApplyInsets();
    }
}
