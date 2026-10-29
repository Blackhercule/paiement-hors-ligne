package bf.pegasus.paiement.ui;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import bf.pegasus.paiement.metier.Operation;

/** Mise en forme des montants, dates et identifiants pour l'affichage. */
public final class Format {

    public static final String MASQUE = "••••••";
    private static final char ESPACE_INSECABLE = ' ';
    private static final Locale FRANCAIS = Locale.FRENCH;

    private Format() {
    }

    /** 12500 → « 12 500 » (espace insécable). */
    public static String nombre(long n) {
        String chiffres = Long.toString(Math.abs(n));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chiffres.length(); i++) {
            if (i > 0 && (chiffres.length() - i) % 3 == 0) {
                sb.append(ESPACE_INSECABLE);
            }
            sb.append(chiffres.charAt(i));
        }
        return (n < 0 ? "−" : "") + sb;
    }

    public static String fcfa(long n) {
        return nombre(n) + ESPACE_INSECABLE + "FCFA";
    }

    /** « +5 000 » ou « −2 500 » selon le sens de l'opération. */
    public static String signe(Operation op) {
        return (op.type == Operation.Type.ENVOI ? "−" : "+") + nombre(op.montant);
    }

    public static String titre(Operation op) {
        switch (op.type) {
            case ENVOI:
                return "Envoyé à " + nomOuIdentifiant(op.pairNom, op.pairAppareil);
            case RECEPTION:
                return "Reçu de " + nomOuIdentifiant(op.pairNom, op.pairAppareil);
            default:
                return "Solde de démonstration";
        }
    }

    public static String statut(Operation op) {
        if (op.type == Operation.Type.DEMO) {
            return "Démo";
        }
        switch (op.statut) {
            case EN_ATTENTE:
                return "En attente";
            case RECU:
                return "Reçu";
            default:
                return "Confirmé";
        }
    }

    public static String nomOuIdentifiant(String nom, String appareil) {
        return nom == null || nom.isEmpty() ? "ID " + identifiantCourt(appareil) : nom;
    }

    /** 8c41a2…07de → « 8C41…07DE ». */
    public static String identifiantCourt(String appareil) {
        if (appareil == null || appareil.length() < 8) {
            return appareil == null ? "" : appareil.toUpperCase(Locale.ROOT);
        }
        return (appareil.substring(0, 4) + "…" + appareil.substring(appareil.length() - 4)).toUpperCase(Locale.ROOT);
    }

    /** « Awa Sawadogo » → « AS ». */
    public static String initiales(String nom) {
        if (nom == null || nom.trim().isEmpty()) {
            return "?";
        }
        String[] mots = nom.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mots.length && sb.length() < 2; i++) {
            sb.appendCodePoint(mots[i].codePointAt(0));
        }
        return sb.toString().toUpperCase(FRANCAIS);
    }

    /** « Aujourd'hui », « Hier » ou « Lundi 26 octobre ». */
    public static String jour(long horodatage) {
        int ecart = joursDepuis(horodatage);
        if (ecart == 0) {
            return "Aujourd'hui";
        }
        if (ecart == 1) {
            return "Hier";
        }
        String j = new SimpleDateFormat("EEEE d MMMM", FRANCAIS).format(new Date(horodatage));
        return j.substring(0, 1).toUpperCase(FRANCAIS) + j.substring(1);
    }

    public static String heure(long horodatage) {
        return new SimpleDateFormat("HH:mm", FRANCAIS).format(new Date(horodatage));
    }

    /** « Aujourd'hui · 14:32 », « Hier · 17:48 » ou « 26 oct. · 16:20 ». */
    public static String quand(long horodatage) {
        int ecart = joursDepuis(horodatage);
        String jour = ecart == 0 ? "Aujourd'hui" : ecart == 1 ? "Hier"
                : new SimpleDateFormat("d MMM", FRANCAIS).format(new Date(horodatage));
        return jour + " · " + heure(horodatage);
    }

    private static int joursDepuis(long horodatage) {
        Calendar maintenant = Calendar.getInstance();
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(horodatage);
        if (maintenant.get(Calendar.YEAR) == date.get(Calendar.YEAR)) {
            return maintenant.get(Calendar.DAY_OF_YEAR) - date.get(Calendar.DAY_OF_YEAR);
        }
        maintenant.set(Calendar.HOUR_OF_DAY, 0);
        maintenant.set(Calendar.MINUTE, 0);
        maintenant.set(Calendar.SECOND, 0);
        maintenant.set(Calendar.MILLISECOND, 0);
        long ecart = maintenant.getTimeInMillis() - horodatage;
        return ecart <= 0 ? 0 : (int) (ecart / 86_400_000L) + 1;
    }
}
