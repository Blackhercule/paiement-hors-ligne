package bf.pegasus.paiement.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import bf.pegasus.paiement.R;
import bf.pegasus.paiement.metier.Operation;

/** Remplit une ligne d'opération (layout {@code item_operation}). */
final class LigneOperation {

    private LigneOperation() {
    }

    static void lier(View ligne, Operation op, boolean masque, boolean heureSeule) {
        Context c = ligne.getContext();
        View rond = ligne.findViewById(R.id.rondOperation);
        ImageView icone = ligne.findViewById(R.id.iconeOperation);
        TextView titre = ligne.findViewById(R.id.titreOperation);
        TextView detail = ligne.findViewById(R.id.detailOperation);
        TextView montant = ligne.findViewById(R.id.montantOperation);
        TextView statut = ligne.findViewById(R.id.statutOperation);

        int fondRond;
        int iconeRes;
        int couleurIcone;
        switch (op.type) {
            case ENVOI:
                fondRond = R.drawable.bg_rond_gris;
                iconeRes = R.drawable.ic_fleche_envoi;
                couleurIcone = R.color.texte;
                break;
            case RECEPTION:
                fondRond = R.drawable.bg_rond_menthe;
                iconeRes = R.drawable.ic_fleche_recu;
                couleurIcone = R.color.vert;
                break;
            default:
                fondRond = R.drawable.bg_rond_bleu;
                iconeRes = R.drawable.ic_plus;
                couleurIcone = R.color.bleu_texte;
                break;
        }
        rond.setBackgroundResource(fondRond);
        icone.setImageResource(iconeRes);
        icone.setImageTintList(ColorStateList.valueOf(c.getColor(couleurIcone)));

        titre.setText(Format.titre(op));
        detail.setText(heureSeule ? Format.heure(op.horodatage) : Format.quand(op.horodatage));
        montant.setText(masque ? "••••" : Format.signe(op));
        montant.setTextColor(c.getColor(op.type == Operation.Type.ENVOI ? R.color.texte : R.color.vert));

        statut.setText(Format.statut(op));
        if (op.type == Operation.Type.DEMO) {
            statut.setBackgroundResource(R.drawable.bg_pastille_bleu);
            statut.setTextColor(c.getColor(R.color.bleu_texte));
        } else if (op.statut == Operation.Statut.EN_ATTENTE) {
            statut.setBackgroundResource(R.drawable.bg_pastille_ambre);
            statut.setTextColor(c.getColor(R.color.ambre_texte));
        } else {
            statut.setBackgroundResource(R.drawable.bg_pastille_menthe);
            statut.setTextColor(c.getColor(R.color.vert_fonce));
        }
        ligne.setContentDescription(Format.titre(op) + ", " + (masque ? "montant masqué" : Format.signe(op)
                + " francs CFA") + ", " + Format.statut(op) + ", " + Format.quand(op.horodatage));
    }
}
