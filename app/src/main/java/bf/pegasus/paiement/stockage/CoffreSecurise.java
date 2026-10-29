package bf.pegasus.paiement.stockage;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import bf.pegasus.paiement.crypto.Octets;
import bf.pegasus.paiement.metier.Depot;
import bf.pegasus.paiement.metier.Falsification;

/**
 * Coffre du portefeuille : solde, historique et journal anti-rejeu, chiffrés en AES-256-GCM.
 *
 * <p>La clé AES est créée DANS l'Android Keystore (puce sécurisée TEE, ou StrongBox quand le
 * téléphone en a une) : elle ne peut pas être extraite du téléphone. Toute modification du
 * fichier est détectée par GCM. L'écriture est atomique : en cas de coupure, l'ancien
 * fichier reste intact.</p>
 */
public final class CoffreSecurise implements Depot.Stockage {

    private static final String MAGASIN = "AndroidKeyStore";
    private static final String ALIAS = "pegasus_coffre";
    private static final byte[] DONNEES_ASSOCIEES = Octets.utf8("PHL1-coffre-v1");
    private static final int TAILLE_IV = 12;

    private final Context contexte;
    private final AtomicFile fichier;

    public CoffreSecurise(Context contexte) {
        this.contexte = contexte.getApplicationContext();
        this.fichier = new AtomicFile(new File(this.contexte.getFilesDir(), "coffre.bin"));
    }

    @Override
    public String lire() throws IOException, Falsification {
        boolean fichierPresent = fichier.getBaseFile().exists();
        boolean clePresente = clePresente();
        if (!fichierPresent && !clePresente) {
            return null; // tout premier lancement
        }
        if (!fichierPresent) {
            throw new Falsification("Le portefeuille a été supprimé.");
        }
        if (!clePresente) {
            throw new Falsification("La clé du coffre est introuvable sur ce téléphone.");
        }
        byte[] donnees = fichier.readFully();
        if (donnees.length < TAILLE_IV + 16) {
            throw new Falsification("Coffre endommagé.");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, cle(), new GCMParameterSpec(128, donnees, 0, TAILLE_IV));
            c.updateAAD(DONNEES_ASSOCIEES);
            return Octets.texte(c.doFinal(donnees, TAILLE_IV, donnees.length - TAILLE_IV));
        } catch (AEADBadTagException e) {
            throw new Falsification("Les données du portefeuille ont été modifiées.");
        } catch (GeneralSecurityException e) {
            throw new IOException("Déchiffrement du coffre impossible : " + e.getMessage(), e);
        }
    }

    @Override
    public void ecrire(String contenu) throws IOException {
        byte[] sortie;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cle()); // le Keystore choisit lui-même un IV neuf
            c.updateAAD(DONNEES_ASSOCIEES);
            byte[] chiffre = c.doFinal(Octets.utf8(contenu));
            byte[] iv = c.getIV();
            if (iv == null || iv.length != TAILLE_IV) {
                throw new GeneralSecurityException("IV inattendu");
            }
            sortie = new byte[iv.length + chiffre.length];
            System.arraycopy(iv, 0, sortie, 0, iv.length);
            System.arraycopy(chiffre, 0, sortie, iv.length, chiffre.length);
        } catch (GeneralSecurityException e) {
            throw new IOException("Chiffrement du coffre impossible : " + e.getMessage(), e);
        }
        FileOutputStream flux = fichier.startWrite();
        try {
            flux.write(sortie);
            fichier.finishWrite(flux);
        } catch (IOException e) {
            fichier.failWrite(flux);
            throw e;
        }
    }

    private static KeyStore magasin() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(MAGASIN);
        ks.load(null);
        return ks;
    }

    private static boolean clePresente() throws IOException {
        try {
            return magasin().containsAlias(ALIAS);
        } catch (GeneralSecurityException e) {
            throw new IOException("Keystore inaccessible : " + e.getMessage(), e);
        }
    }

    private SecretKey cle() throws GeneralSecurityException, IOException {
        KeyStore ks = magasin();
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        boolean strongBox = Build.VERSION.SDK_INT >= 28
                && contexte.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
        if (strongBox) {
            try {
                return genererCle(true);
            } catch (ProviderException e) {
                // StrongBoxUnavailableException (Android 9+) : on se rabat sur la zone sécurisée (TEE)
            }
        }
        return genererCle(false);
    }

    private static SecretKey genererCle(boolean strongBox) throws GeneralSecurityException {
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (strongBox && Build.VERSION.SDK_INT >= 28) {
            spec.setIsStrongBoxBacked(true);
        }
        KeyGenerator generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, MAGASIN);
        generateur.init(spec.build());
        return generateur.generateKey();
    }
}
