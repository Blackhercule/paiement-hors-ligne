package bf.pegasus.paiement.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import bf.pegasus.paiement.R;
import bf.pegasus.paiement.securite.VerificateurIntegrite;

/**
 * « Application falsifiée » : affiché quand la signature ou les données sont invalides.
 * Le portefeuille n'est pas ouvert et aucun transfert n'est possible.
 */
public final class FalsifieeActivity extends BaseActivity {

    private static final String MOTIF = "motif";

    static void ouvrir(Activity depuis, String motif) {
        Intent intention = new Intent(depuis, FalsifieeActivity.class)
                .putExtra(MOTIF, motif)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        depuis.startActivity(intention);
    }

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        setContentView(R.layout.activity_falsifiee);
        View racine = findViewById(R.id.racine);
        bordABord(racine, racine, false);

        String motif = getIntent().getStringExtra(MOTIF);
        if (motif == null) {
            motif = app().motifFalsification();
        }
        ((TextView) findViewById(R.id.motif)).setText(motif == null ? getString(R.string.motif_inconnu) : motif);
        ((TextView) findViewById(R.id.quand)).setText(Format.quand(System.currentTimeMillis()));
        String empreinte = app().empreinte();
        ((TextView) findViewById(R.id.empreinte)).setText(
                empreinte.isEmpty() ? getString(R.string.illisible) : VerificateurIntegrite.courte(empreinte));
        findViewById(R.id.boutonFermerApp).setOnClickListener(v -> finishAndRemoveTask());
    }
}
