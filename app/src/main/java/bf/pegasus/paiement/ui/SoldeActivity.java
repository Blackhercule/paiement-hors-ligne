package bf.pegasus.paiement.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

import bf.pegasus.paiement.BuildConfig;
import bf.pegasus.paiement.R;
import bf.pegasus.paiement.metier.ErreurPaiement;
import bf.pegasus.paiement.metier.Operation;
import bf.pegasus.paiement.metier.Portefeuille;
import bf.pegasus.paiement.securite.VerificateurIntegrite;

/** Écran d'accueil : solde (masquable), raccourcis et dernières opérations. */
public final class SoldeActivity extends BaseActivity {

    private static final int NB_RECENTES = 5;

    private TextView avatar;
    private TextView nomUtilisateur;
    private TextView solde;
    private TextView pastilleEtat;
    private TextView identifiant;
    private ImageButton boutonOeil;
    private LinearLayout listeRecente;
    private boolean bienvenueMontree;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (isFinishing()) {
            return;
        }
        setContentView(R.layout.activity_solde);
        bordABord(findViewById(R.id.racine), findViewById(R.id.entete), true);

        avatar = findViewById(R.id.avatar);
        nomUtilisateur = findViewById(R.id.nomUtilisateur);
        solde = findViewById(R.id.solde);
        pastilleEtat = findViewById(R.id.pastilleEtat);
        identifiant = findViewById(R.id.identifiant);
        boutonOeil = findViewById(R.id.boutonOeil);
        listeRecente = findViewById(R.id.listeRecente);

        boutonOeil.setOnClickListener(v -> {
            app().masquerSolde(!app().soldeMasque());
            rafraichir();
        });
        findViewById(R.id.boutonParametres).setOnClickListener(v -> ouvrirParametres(false));
        findViewById(R.id.actionEnvoyer).setOnClickListener(v -> startActivity(new Intent(this, EnvoyerActivity.class)));
        findViewById(R.id.actionRecevoir).setOnClickListener(v -> startActivity(new Intent(this, RecevoirActivity.class)));
        View.OnClickListener historique = v -> startActivity(new Intent(this, HistoriqueActivity.class));
        findViewById(R.id.actionHistorique).setOnClickListener(historique);
        findViewById(R.id.toutVoir).setOnClickListener(historique);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) {
            return;
        }
        if (app().motifFalsification() != null) {
            FalsifieeActivity.ouvrir(this, app().motifFalsification());
            finish();
            return;
        }
        rafraichir();
        if (!bienvenueMontree && app().depot().lecture().nom().isEmpty()) {
            bienvenueMontree = true;
            ouvrirParametres(true);
        }
    }

    private void rafraichir() {
        Portefeuille p = app().depot().lecture();
        boolean masque = app().soldeMasque();

        nomUtilisateur.setText(p.nom().isEmpty() ? getString(R.string.mon_portefeuille) : p.nom());
        avatar.setText(Format.initiales(p.nom()));
        identifiant.setText(getString(R.string.identifiant_court, Format.identifiantCourt(p.appareil())));
        solde.setText(masque ? Format.MASQUE + " FCFA" : Format.fcfa(p.solde()));
        boutonOeil.setImageResource(masque ? R.drawable.ic_oeil : R.drawable.ic_oeil_barre);
        boutonOeil.setContentDescription(getString(masque ? R.string.afficher_solde : R.string.masquer_solde));

        int enAttente = 0;
        for (Operation op : p.operations()) {
            if (op.type == Operation.Type.ENVOI && op.statut == Operation.Statut.EN_ATTENTE) {
                enAttente++;
            }
        }
        pastilleEtat.setText(enAttente == 0 ? getString(R.string.pret_sans_reseau)
                : getResources().getQuantityString(R.plurals.paiements_en_attente, enAttente, enAttente));

        listeRecente.removeAllViews();
        List<Operation> operations = p.operations();
        LayoutInflater inflateur = getLayoutInflater();
        int n = Math.min(NB_RECENTES, operations.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                inflateur.inflate(R.layout.separateur, listeRecente, true);
            }
            View ligne = inflateur.inflate(R.layout.item_operation, listeRecente, false);
            LigneOperation.lier(ligne, operations.get(i), masque, false);
            listeRecente.addView(ligne);
        }
        if (n == 0) {
            TextView vide = (TextView) inflateur.inflate(R.layout.texte_vide, listeRecente, false);
            listeRecente.addView(vide);
        }
    }

    private void ouvrirParametres(boolean bienvenue) {
        Portefeuille p = app().depot().lecture();
        View vue = getLayoutInflater().inflate(R.layout.dialog_parametres, null);
        final EditText champ = vue.findViewById(R.id.champNom);
        champ.setText(p.nom());
        champ.setSelection(champ.getText().length());
        TextView infos = vue.findViewById(R.id.infosAppareil);
        infos.setText(getString(R.string.infos_appareil, Format.identifiantCourt(p.appareil()),
                VerificateurIntegrite.courte(app().empreinte()), BuildConfig.VERSION_NAME));

        AlertDialog.Builder dialogue = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(bienvenue ? R.string.bienvenue : R.string.parametres)
                .setView(vue)
                .setPositiveButton(R.string.enregistrer, (d, quel) -> enregistrerNom(champ.getText().toString()));
        if (!bienvenue) {
            dialogue.setNegativeButton(R.string.annuler, null);
        }
        dialogue.show();
    }

    private void enregistrerNom(final String nom) {
        try {
            app().depot().modifier(p -> {
                p.renommer(nom);
                return null;
            });
        } catch (ErreurPaiement | IOException e) {
            message(getString(R.string.erreur_enregistrement, e.getMessage()));
        }
        rafraichir();
    }
}
