package bf.pegasus.paiement.securite;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import bf.pegasus.paiement.BuildConfig;
import bf.pegasus.paiement.crypto.Octets;
import bf.pegasus.paiement.metier.Falsification;

/**
 * Contrôle d'intégrité : l'application lit l'empreinte SHA-256 du certificat qui l'a signée
 * et la compare à l'empreinte officielle intégrée au code ({@code BuildConfig}).
 *
 * <p>Si quelqu'un modifie l'application (par exemple pour changer un solde) puis la
 * re-signe avec une autre clé, l'empreinte ne correspond plus : « Application falsifiée ».</p>
 */
public final class VerificateurIntegrite {

    private VerificateurIntegrite() {
    }

    /** Empreinte SHA-256 du certificat de signature, en hexadécimal majuscule. */
    @SuppressWarnings("deprecation")
    public static String empreinteSignature(Context contexte) throws Falsification {
        try {
            PackageManager pm = contexte.getPackageManager();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = pm.getPackageInfo(contexte.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signature = info.signingInfo;
                if (signature == null) {
                    throw new Falsification("Application non signée.");
                }
                signatures = signature.getApkContentsSigners();
            } else {
                PackageInfo info = pm.getPackageInfo(contexte.getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length != 1) {
                throw new Falsification("Signature de l'application inattendue.");
            }
            byte[] empreinte = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            return Octets.hex(empreinte).toUpperCase(Locale.ROOT);
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            throw new Falsification("Signature de l'application illisible.");
        }
    }

    public static void verifier(Context contexte) throws Falsification {
        String trouvee = empreinteSignature(contexte);
        if (!Octets.egaux(Octets.utf8(trouvee), Octets.utf8(BuildConfig.EMPREINTE_OFFICIELLE))) {
            throw new Falsification("La signature de cette application n'est pas valide.");
        }
    }

    /** Empreinte lisible : AB:CD:EF:… (8 premiers octets). */
    public static String courte(String empreinte) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < empreinte.length() && i < 16; i += 2) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(empreinte, i, i + 2);
        }
        return sb.append(empreinte.length() > 16 ? "…" : "").toString();
    }
}
