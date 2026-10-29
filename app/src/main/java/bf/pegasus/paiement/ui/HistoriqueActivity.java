package bf.pegasus.paiement.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import bf.pegasus.paiement.R;
import bf.pegasus.paiement.metier.Operation;
import bf.pegasus.paiement.metier.Portefeuille;

/** Écran « Historique » : toutes les opérations, groupées par jour, avec filtre. */
public final class HistoriqueActivity extends BaseActivity {

    private enum Filtre { TOUT, ENVOYES, RECUS }

    private Filtre filtre = Filtre.TOUT;
    private final List<Object> lignes = new ArrayList<>(); // String = en-tête de jour, Operation = ligne
    private boolean masque;
    private TextView[] boutonsFiltre;
    private View vide;

    private final BaseAdapter adaptateur = new BaseAdapter() {
        @Override
        public int getCount() {
            return lignes.size();
        }

        @Override
        public Object getItem(int position) {
            return lignes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return lignes.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View recyclee, ViewGroup parent) {
            Object element = lignes.get(position);
            if (element instanceof String) {
                TextView jour = (TextView) (recyclee != null ? recyclee
                        : getLayoutInflater().inflate(R.layout.item_jour, parent, false));
                jour.setText((String) element);
                return jour;
            }
            View ligne = recyclee;
            if (ligne == null) {
                ligne = getLayoutInflater().inflate(R.layout.item_operation, parent, false);
                ligne.setBackgroundResource(R.drawable.bg_carte_16);
                int h = getResources().getDimensionPixelSize(R.dimen.marge_carte_h);
                ligne.setPadding(h, ligne.getPaddingTop(), h, ligne.getPaddingBottom());
            }
            LigneOperation.lier(ligne, (Operation) element, masque, true);
            return ligne;
        }
    };

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (isFinishing()) {
            return;
        }
        setContentView(R.layout.activity_historique);
        View racine = findViewById(R.id.racine);
        bordABord(racine, racine, false);
        barreTitre(R.string.historique);

        ListView liste = findViewById(R.id.liste);
        vide = findViewById(R.id.vide);
        liste.setAdapter(adaptateur);

        boutonsFiltre = new TextView[]{findViewById(R.id.filtreTout), findViewById(R.id.filtreEnvoyes),
                findViewById(R.id.filtreRecus)};
        for (int i = 0; i < boutonsFiltre.length; i++) {
            final Filtre f = Filtre.values()[i];
            boutonsFiltre[i].setOnClickListener(v -> {
                filtre = f;
                recharger();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isFinishing()) {
            recharger();
        }
    }

    private void recharger() {
        Portefeuille p = app().depot().lecture();
        masque = app().soldeMasque();

        long totalRecu = 0;
        long totalEnvoye = 0;
        lignes.clear();
        String jourPrecedent = null;
        for (Operation op : p.operations()) {
            if (op.type == Operation.Type.ENVOI) {
                totalEnvoye += op.montant;
            } else if (op.type == Operation.Type.RECEPTION) {
                totalRecu += op.montant;
            }
            boolean garde = filtre == Filtre.TOUT
                    || (filtre == Filtre.ENVOYES && op.type == Operation.Type.ENVOI)
                    || (filtre == Filtre.RECUS && op.type != Operation.Type.ENVOI);
            if (!garde) {
                continue;
            }
            String jour = Format.jour(op.horodatage);
            if (!jour.equals(jourPrecedent)) {
                lignes.add(jour);
                jourPrecedent = jour;
            }
            lignes.add(op);
        }
        ((TextView) findViewById(R.id.totalRecu)).setText(masque ? Format.MASQUE : "+" + Format.fcfa(totalRecu));
        ((TextView) findViewById(R.id.totalEnvoye)).setText(masque ? Format.MASQUE : "−" + Format.fcfa(totalEnvoye));

        for (int i = 0; i < boutonsFiltre.length; i++) {
            boolean actif = Filtre.values()[i] == filtre;
            boutonsFiltre[i].setSelected(actif);
            boutonsFiltre[i].setBackgroundResource(actif ? R.drawable.bg_segment_actif : 0);
            boutonsFiltre[i].setTextColor(getColor(actif ? R.color.texte : R.color.texte_moyen));
        }
        vide.setVisibility(lignes.isEmpty() ? View.VISIBLE : View.GONE);
        adaptateur.notifyDataSetChanged();
    }
}
