package bf.pegasus.paiement.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import bf.pegasus.paiement.R;
import bf.pegasus.paiement.metier.Operation;
import bf.pegasus.paiement.metier.Portefeuille;
import bf.pegasus.paiement.reseau.InfosConnexion;
import bf.pegasus.paiement.reseau.PointAcces;
import bf.pegasus.paiement.reseau.SessionReceveur;

/**
 * Écran « Recevoir » : crée le point d'accès local, ouvre le serveur et affiche le QR code.
 * Un seul payeur peut se connecter ; le solde est crédité dès réception du paiement.
 */
public final class RecevoirActivity extends BaseActivity implements SessionReceveur.Vue {

    private static final int DEMANDE_PERMISSION = 2;
    private static final int TAILLE_QR_PX = 720;

    private final ExecutorService taches = Executors.newSingleThreadExecutor();

    private TextView pastilleStatut;
    private View carteQr;
    private ImageView imageQr;
    private View chargementQr;
    private View carteRecu;
    private View carteErreur;
    private TextView texteErreur;
    private Button boutonErreur;
    private TextView explication;
    private TextView texteReseau;
    private Button boutonNouvelleSession;

    private PointAcces pointAcces;
    private volatile SessionReceveur session;
    private String[] reseau; // {ssid, mot de passe, sécurité}

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (isFinishing()) {
            return;
        }
        setContentView(R.layout.activity_recevoir);
        View racine = findViewById(R.id.racine);
        bordABord(racine, racine, false);
        barreTitre(R.string.recevoir);

        pastilleStatut = findViewById(R.id.pastilleStatut);
        carteQr = findViewById(R.id.carteQr);
        imageQr = findViewById(R.id.imageQr);
        chargementQr = findViewById(R.id.chargementQr);
        carteRecu = findViewById(R.id.carteRecu);
        carteErreur = findViewById(R.id.carteErreur);
        texteErreur = findViewById(R.id.texteErreur);
        boutonErreur = findViewById(R.id.boutonErreur);
        explication = findViewById(R.id.explication);
        texteReseau = findViewById(R.id.texteReseau);
        boutonNouvelleSession = findViewById(R.id.boutonNouvelleSession);

        Portefeuille p = app().depot().lecture();
        ((TextView) findViewById(R.id.nomRecevoir)).setText(
                p.nom().isEmpty() ? getString(R.string.mon_portefeuille) : p.nom());
        ((TextView) findViewById(R.id.idRecevoir)).setText(
                getString(R.string.identifiant_court, Format.identifiantCourt(p.appareil())));

        findViewById(R.id.boutonFermer).setOnClickListener(v -> finish());
        boutonNouvelleSession.setOnClickListener(v -> nouvelleSession());
        demarrer();
    }

    // ------------------------------------------------------------------ 1. Point d'accès

    private String permissionNecessaire() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void demarrer() {
        carteErreur.setVisibility(View.GONE);
        statut(getString(R.string.preparation_point_acces), false);
        if (checkSelfPermission(permissionNecessaire()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permissionNecessaire()}, DEMANDE_PERMISSION);
            return;
        }
        if (Build.VERSION.SDK_INT < 33 && !localisationActivee()) {
            erreur(getString(R.string.localisation_requise), getString(R.string.activer_localisation),
                    v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
            return;
        }
        if (pointAcces == null) {
            pointAcces = new PointAcces(this);
        }
        pointAcces.demarrer(new PointAcces.Rappel() {
            @Override
            public void demarre(String ssid, String motDePasse, String securite) {
                reseau = new String[]{ssid, motDePasse, securite};
                texteReseau.setText(ssid);
                preparerSession();
            }

            @Override
            public void echec(String message) {
                erreur(message, getString(R.string.reessayer), v -> demarrer());
            }

            @Override
            public void arrete() {
                if (!isFinishing()) {
                    erreur(getString(R.string.point_acces_arrete), getString(R.string.reessayer), v -> demarrer());
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] resultats) {
        super.onRequestPermissionsResult(code, permissions, resultats);
        if (code != DEMANDE_PERMISSION) {
            return;
        }
        if (resultats.length > 0 && resultats[0] == PackageManager.PERMISSION_GRANTED) {
            demarrer();
        } else {
            erreur(getString(Build.VERSION.SDK_INT >= 33 ? R.string.permission_appareils_proches
                    : R.string.permission_localisation_receveur), getString(R.string.reessayer), v -> demarrer());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // retour des réglages de localisation : on relance
        if (carteErreur != null && carteErreur.getVisibility() == View.VISIBLE && reseau == null
                && Build.VERSION.SDK_INT < 33 && localisationActivee()
                && checkSelfPermission(permissionNecessaire()) == PackageManager.PERMISSION_GRANTED) {
            demarrer();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean localisationActivee() {
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        }
        return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    // ------------------------------------------------------------------ 2. Session et QR code

    /** Nouvelle session : nouvelle clé publique temporaire, nouveau numéro, nouveau QR. */
    private void preparerSession() {
        final String[] r = reseau;
        if (r == null) {
            return;
        }
        statut(getString(R.string.preparation_qr), false);
        carteQr.setVisibility(View.VISIBLE);
        imageQr.setImageDrawable(null);
        chargementQr.setVisibility(View.VISIBLE);
        carteRecu.setVisibility(View.GONE);
        explication.setVisibility(View.VISIBLE);
        boutonNouvelleSession.setVisibility(View.GONE);
        taches.execute(() -> {
            try {
                List<String> adresses = attendreAdresses();
                SessionReceveur s = new SessionReceveur(app().depot(), this);
                InfosConnexion infos = s.demarrer(r[0], r[1], r[2], adresses);
                session = s;
                final Bitmap qr = Qr.generer(infos.encoder(), TAILLE_QR_PX);
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    chargementQr.setVisibility(View.GONE);
                    imageQr.setImageBitmap(qr);
                    statut(getString(R.string.attente_payeur), false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> erreur(getString(R.string.erreur_session, e.getMessage()),
                        getString(R.string.reessayer), v -> preparerSession()));
            }
        });
    }

    /** L'adresse du point d'accès apparaît quelques instants après son démarrage. */
    private static List<String> attendreAdresses() throws InterruptedException {
        List<String> adresses = PointAcces.adressesLocales();
        for (int i = 0; i < 10 && adresses.isEmpty(); i++) {
            Thread.sleep(300);
            adresses = PointAcces.adressesLocales();
        }
        return adresses;
    }

    private void nouvelleSession() {
        final SessionReceveur ancienne = session;
        session = null;
        if (ancienne != null) {
            taches.execute(ancienne::close);
        }
        preparerSession();
    }

    // ------------------------------------------------------------------ 3. Événements de la session

    @Override
    public void payeurConnecte(final String nomPayeur) {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                statut(getString(R.string.payeur_connecte, nomPayeur.isEmpty() ? getString(R.string.le_payeur)
                        : nomPayeur), true);
            }
        });
    }

    @Override
    public void paiementRecu(final Operation op) {
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            carteQr.setVisibility(View.GONE);
            explication.setVisibility(View.GONE);
            carteRecu.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.montantRecu)).setText("+" + Format.fcfa(op.montant));
            ((TextView) findViewById(R.id.deQui)).setText(
                    getString(R.string.de_qui, Format.nomOuIdentifiant(op.pairNom, op.pairAppareil)));
            statut(getString(R.string.paiement_recu), true);
        });
    }

    @Override
    public void paiementDejaRecu(String idPaiement) {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                message(getString(R.string.paiement_deja_recu));
            }
        });
    }

    @Override
    public void paiementRefuse(final String motif) {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                message(getString(R.string.paiement_refuse, motif));
            }
        });
    }

    @Override
    public void connexionRefusee() {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                message(getString(R.string.intrus_refuse));
            }
        });
    }

    @Override
    public void terminee(final String raison) {
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            carteQr.setVisibility(View.GONE);
            explication.setVisibility(View.GONE);
            statut(raison == null ? getString(R.string.session_terminee)
                    : getString(R.string.session_interrompue, raison), raison == null);
            boutonNouvelleSession.setVisibility(View.VISIBLE);
        });
    }

    // ------------------------------------------------------------------ outils

    private void statut(String texte, boolean positif) {
        pastilleStatut.setText(texte);
        pastilleStatut.setBackgroundResource(positif ? R.drawable.bg_pastille_menthe : R.drawable.bg_pastille_ambre);
        pastilleStatut.setTextColor(getColor(positif ? R.color.vert_fonce : R.color.ambre_texte));
    }

    private void erreur(String texte, String action, View.OnClickListener quandClic) {
        if (isFinishing()) {
            return;
        }
        statut(getString(R.string.point_acces_indisponible), false);
        carteQr.setVisibility(View.GONE);
        carteErreur.setVisibility(View.VISIBLE);
        texteErreur.setText(texte);
        boutonErreur.setText(action);
        boutonErreur.setOnClickListener(quandClic);
    }

    @Override
    protected void onDestroy() {
        final SessionReceveur s = session;
        session = null;
        if (s != null) {
            taches.execute(s::close);
        }
        taches.shutdown();
        if (pointAcces != null) {
            pointAcces.arreter();
        }
        super.onDestroy();
    }
}
