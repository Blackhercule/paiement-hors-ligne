package bf.pegasus.paiement.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import bf.pegasus.paiement.R;
import bf.pegasus.paiement.metier.ErreurPaiement;
import bf.pegasus.paiement.metier.Falsification;
import bf.pegasus.paiement.metier.Message;
import bf.pegasus.paiement.metier.Operation;
import bf.pegasus.paiement.reseau.ConnexionWifi;
import bf.pegasus.paiement.reseau.InfosConnexion;
import bf.pegasus.paiement.reseau.ProtocoleException;
import bf.pegasus.paiement.reseau.SessionPayeur;

/**
 * Écran « Envoyer » : scan du QR code du receveur, connexion à son point d'accès,
 * saisie du montant, paiement chiffré puis attente du reçu.
 */
public final class EnvoyerActivity extends BaseActivity implements SessionPayeur.Vue {

    private static final int DEMANDE_PERMISSION = 1;
    private static final int DELAI_CONNEXION_MS = 4_000;
    private static final long DELAI_RECU_MS = 15_000;
    private static final int CHIFFRES_MAX = 7;

    private final Handler fil = new Handler(Looper.getMainLooper());
    private final ExecutorService taches = Executors.newSingleThreadExecutor();
    private final StringBuilder saisie = new StringBuilder();
    private final Runnable recuEnRetard = () -> afficherResultat(false, null);

    private View etatScan;
    private View etatConnexion;
    private View etatMontant;
    private View etatAttente;
    private View etatResultat;
    private TextView erreurScan;
    private TextView texteConnexion;
    private TextView montant;
    private TextView infoSolde;
    private Button boutonEnvoyer;

    private ConnexionWifi wifi;
    private volatile SessionPayeur session;
    private InfosConnexion receveur;
    private Operation enCours;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (isFinishing()) {
            return;
        }
        setContentView(R.layout.activity_envoyer);
        View racine = findViewById(R.id.racine);
        bordABord(racine, racine, false);
        barreTitre(R.string.envoyer);

        etatScan = findViewById(R.id.etatScan);
        etatConnexion = findViewById(R.id.etatConnexion);
        etatMontant = findViewById(R.id.etatMontant);
        etatAttente = findViewById(R.id.etatAttente);
        etatResultat = findViewById(R.id.etatResultat);
        erreurScan = findViewById(R.id.erreurScan);
        texteConnexion = findViewById(R.id.texteConnexion);
        montant = findViewById(R.id.montant);
        infoSolde = findViewById(R.id.infoSolde);
        boutonEnvoyer = findViewById(R.id.boutonEnvoyer);

        findViewById(R.id.boutonScanner).setOnClickListener(v -> demarrerScan());
        findViewById(R.id.boutonAnnulerConnexion).setOnClickListener(v -> finish());
        findViewById(R.id.boutonTerminer).setOnClickListener(v -> finish());
        boutonEnvoyer.setOnClickListener(v -> payer());

        int[] touches = {R.id.touche0, R.id.touche1, R.id.touche2, R.id.touche3, R.id.touche4,
                R.id.touche5, R.id.touche6, R.id.touche7, R.id.touche8, R.id.touche9, R.id.touche000};
        for (int id : touches) {
            final Button touche = findViewById(id);
            touche.setOnClickListener(v -> taper(touche.getText().toString()));
        }
        findViewById(R.id.toucheEffacer).setOnClickListener(v -> {
            if (saisie.length() > 0) {
                saisie.deleteCharAt(saisie.length() - 1);
                majMontant();
            }
        });
        findViewById(R.id.toucheEffacer).setOnLongClickListener(v -> {
            saisie.setLength(0);
            majMontant();
            return true;
        });
        montrer(etatScan);
    }

    // ------------------------------------------------------------------ 1. Scan du QR code

    private void demarrerScan() {
        erreurScan.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT < 33
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, DEMANDE_PERMISSION);
            return;
        }
        lancerScanner();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] resultats) {
        super.onRequestPermissionsResult(code, permissions, resultats);
        if (code != DEMANDE_PERMISSION) {
            return;
        }
        if (resultats.length > 0 && resultats[0] == PackageManager.PERMISSION_GRANTED) {
            lancerScanner();
        } else {
            erreurScan(getString(R.string.permission_localisation_payeur));
        }
    }

    private void lancerScanner() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(getString(R.string.scanner_consigne))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int code, int resultat, Intent donnees) {
        IntentResult scan = IntentIntegrator.parseActivityResult(code, resultat, donnees);
        if (scan == null) {
            super.onActivityResult(code, resultat, donnees);
            return;
        }
        if (scan.getContents() == null) {
            return; // scan annulé
        }
        try {
            receveur = InfosConnexion.decoder(scan.getContents());
        } catch (Message.FormatInvalide e) {
            erreurScan(e.getMessage());
            return;
        }
        if (receveur.appareil.equals(app().depot().lecture().appareil())) {
            erreurScan(getString(R.string.propre_qr));
            return;
        }
        connecterWifi();
    }

    private void erreurScan(String texte) {
        montrer(etatScan);
        erreurScan.setText(texte);
        erreurScan.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ 2. Connexion

    private void connecterWifi() {
        montrer(etatConnexion);
        texteConnexion.setText(getString(R.string.connexion_a, Format.nomOuIdentifiant(receveur.nom, receveur.appareil)));
        wifi = new ConnexionWifi(this);
        wifi.connecter(receveur, new ConnexionWifi.Rappel() {
            @Override
            public void connecte(Network reseau, List<String> passerelles) {
                ouvrirSession(reseau, passerelles);
            }

            @Override
            public void echec(String message) {
                echecConnexion(message);
            }
        });
    }

    private void ouvrirSession(final Network reseau, final List<String> passerelles) {
        if (isFinishing()) {
            return;
        }
        texteConnexion.setText(R.string.canal_chiffre);
        final SessionPayeur s = new SessionPayeur(app().depot(), receveur, this);
        session = s;
        taches.execute(() -> {
            try {
                final int renvoyes = s.connecter(passerelles, ConnexionWifi.fabrique(reseau), DELAI_CONNEXION_MS);
                runOnUiThread(() -> connecte(renvoyes));
            } catch (IOException e) {
                runOnUiThread(() -> echecConnexion(getString(R.string.receveur_injoignable)));
            } catch (GeneralSecurityException | ProtocoleException e) {
                runOnUiThread(() -> echecConnexion(e.getMessage()));
            }
        });
    }

    private void connecte(int renvoyes) {
        if (isFinishing()) {
            return;
        }
        String nom = Format.nomOuIdentifiant(receveur.nom, receveur.appareil);
        ((TextView) findViewById(R.id.avatarReceveur)).setText(Format.initiales(receveur.nom));
        ((TextView) findViewById(R.id.nomReceveur)).setText(nom);
        ((TextView) findViewById(R.id.detailReceveur)).setText(
                getString(R.string.connecte_en_direct, Format.identifiantCourt(receveur.appareil)));
        if (renvoyes > 0) {
            message(getResources().getQuantityString(R.plurals.paiements_renvoyes, renvoyes, renvoyes, nom));
        }
        saisie.setLength(0);
        majMontant();
        montrer(etatMontant);
    }

    private void echecConnexion(String texte) {
        if (isFinishing()) {
            return;
        }
        fermerSession();
        erreurScan(texte);
    }

    // ------------------------------------------------------------------ 3. Montant

    private void taper(String chiffres) {
        if (saisie.length() == 0 && chiffres.startsWith("0")) {
            return;
        }
        if (saisie.length() + chiffres.length() > CHIFFRES_MAX) {
            return;
        }
        saisie.append(chiffres);
        majMontant();
    }

    private long montantSaisi() {
        return saisie.length() == 0 ? 0 : Long.parseLong(saisie.toString());
    }

    private void majMontant() {
        long m = montantSaisi();
        long solde = app().depot().lecture().solde();
        montant.setText(Format.nombre(m));
        if (m > solde) {
            infoSolde.setText(app().soldeMasque() ? getString(R.string.solde_insuffisant)
                    : getString(R.string.solde_insuffisant_detail, Format.fcfa(solde)));
            infoSolde.setTextColor(getColor(R.color.rouge));
            boutonEnvoyer.setEnabled(false);
            boutonEnvoyer.setText(R.string.solde_insuffisant);
        } else {
            infoSolde.setText(getString(R.string.solde_apres,
                    app().soldeMasque() ? Format.MASQUE + " FCFA" : Format.fcfa(solde - m)));
            infoSolde.setTextColor(getColor(R.color.texte_doux));
            boutonEnvoyer.setEnabled(m > 0);
            boutonEnvoyer.setText(m > 0 ? getString(R.string.envoyer_montant, Format.fcfa(m))
                    : getString(R.string.saisir_montant));
        }
    }

    // ------------------------------------------------------------------ 4. Paiement

    private void payer() {
        final SessionPayeur s = session;
        final long m = montantSaisi();
        if (s == null || m <= 0) {
            return;
        }
        boutonEnvoyer.setEnabled(false);
        taches.execute(() -> {
            try {
                final Operation op = s.payer(m);
                runOnUiThread(() -> envoye(op));
            } catch (Falsification f) {
                app().signalerFalsification(f.getMessage());
                runOnUiThread(() -> {
                    FalsifieeActivity.ouvrir(this, f.getMessage());
                    finish();
                });
            } catch (ErreurPaiement e) {
                runOnUiThread(() -> {
                    message(e.getMessage());
                    majMontant();
                });
            } catch (SessionPayeur.EnvoiInterrompu e) {
                runOnUiThread(() -> {
                    enCours = e.operation;
                    afficherResultat(false, null);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    message(getString(R.string.erreur_enregistrement, e.getMessage()));
                    majMontant();
                });
            }
        });
    }

    private void envoye(Operation op) {
        enCours = op;
        ((TextView) findViewById(R.id.texteAttente)).setText(
                getString(R.string.paiement_envoye, Format.fcfa(op.montant)));
        montrer(etatAttente);
        fil.postDelayed(recuEnRetard, DELAI_RECU_MS);
    }

    @Override
    public void paiementConfirme(final Operation op) {
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            if (enCours != null && enCours.id.equals(op.id)) {
                // reçu à temps, ou arrivé après l'écran « en attente » : on confirme
                fil.removeCallbacks(recuEnRetard);
                enCours = op;
                afficherResultat(true, null);
            } else {
                // un ancien paiement en attente, renvoyé à la connexion, vient d'être confirmé
                message(getString(R.string.ancien_paiement_confirme, Format.fcfa(op.montant)));
            }
        });
    }

    @Override
    public void paiementRefuse(final String idPaiement, final String motif) {
        runOnUiThread(() -> {
            if (!isDestroyed() && enCours != null && enCours.id.equals(idPaiement)) {
                fil.removeCallbacks(recuEnRetard);
                afficherResultat(false, getString(R.string.paiement_refuse, motif));
            }
        });
    }

    @Override
    public void terminee(final String raison) {
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            if (etatAttente.getVisibility() == View.VISIBLE) {
                fil.removeCallbacks(recuEnRetard);
                afficherResultat(false, null);
            } else if (etatMontant.getVisibility() == View.VISIBLE) {
                echecConnexion(raison != null ? raison : getString(R.string.receveur_a_ferme));
            }
        });
    }

    /** Écran final : paiement confirmé, ou resté en attente (il sera renvoyé). */
    private void afficherResultat(boolean confirme, String precision) {
        Operation op = enCours;
        if (op == null || isFinishing()) {
            return;
        }
        View rond = findViewById(R.id.rondResultat);
        ImageView icone = findViewById(R.id.iconeResultat);
        TextView statut = findViewById(R.id.detailStatut);
        String nom = Format.nomOuIdentifiant(receveur.nom, receveur.appareil);
        if (confirme) {
            rond.setBackgroundResource(R.drawable.bg_rond_menthe);
            icone.setImageResource(R.drawable.ic_check);
            icone.setImageTintList(ColorStateList.valueOf(getColor(R.color.vert)));
            ((TextView) findViewById(R.id.titreResultat)).setText(R.string.paiement_confirme);
            ((TextView) findViewById(R.id.texteResultat)).setText(getString(R.string.paiement_confirme_detail, nom));
            statut.setText(R.string.statut_confirme);
            statut.setBackgroundResource(R.drawable.bg_pastille_menthe);
            statut.setTextColor(getColor(R.color.vert_fonce));
        } else {
            rond.setBackgroundResource(R.drawable.bg_rond_ambre);
            icone.setImageResource(R.drawable.ic_historique);
            icone.setImageTintList(ColorStateList.valueOf(getColor(R.color.ambre_texte)));
            ((TextView) findViewById(R.id.titreResultat)).setText(R.string.paiement_en_attente);
            ((TextView) findViewById(R.id.texteResultat)).setText(
                    precision != null ? precision : getString(R.string.paiement_en_attente_detail));
            statut.setText(R.string.statut_en_attente);
            statut.setBackgroundResource(R.drawable.bg_pastille_ambre);
            statut.setTextColor(getColor(R.color.ambre_texte));
        }
        ((TextView) findViewById(R.id.montantResultat)).setText(Format.signe(op) + " FCFA");
        ((TextView) findViewById(R.id.detailNom)).setText(nom);
        long solde = app().depot().lecture().solde();
        ((TextView) findViewById(R.id.detailSolde)).setText(
                app().soldeMasque() ? Format.MASQUE + " FCFA" : Format.fcfa(solde));
        montrer(etatResultat);
    }

    // ------------------------------------------------------------------ outils

    private void montrer(View etat) {
        for (View v : new View[]{etatScan, etatConnexion, etatMontant, etatAttente, etatResultat}) {
            v.setVisibility(v == etat ? View.VISIBLE : View.GONE);
        }
    }

    /** Ferme la session (hors du fil principal : elle envoie « fin ») puis libère le Wi-Fi. */
    private void fermerSession() {
        final SessionPayeur s = session;
        final ConnexionWifi w = wifi;
        session = null;
        wifi = null;
        taches.execute(() -> {
            if (s != null) {
                s.close();
            }
            if (w != null) {
                w.liberer();
            }
        });
    }

    @Override
    protected void onDestroy() {
        fil.removeCallbacksAndMessages(null);
        fermerSession();
        taches.shutdown();
        super.onDestroy();
    }
}
