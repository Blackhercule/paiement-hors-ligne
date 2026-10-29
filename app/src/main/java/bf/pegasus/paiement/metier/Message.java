package bf.pegasus.paiement.metier;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Format texte très simple pour les messages, le QR code et le coffre :
 * {@code type=paiement&montant=2500&nom=Awa%20Sawadogo}. Les valeurs sont encodées
 * comme dans une URL, donc n'importe quel texte passe sans ambiguïté.
 */
public final class Message {

    /** Le texte reçu ne respecte pas le format attendu. */
    public static final class FormatInvalide extends Exception {
        public FormatInvalide(String message) {
            super(message);
        }
    }

    private final Map<String, String> champs = new LinkedHashMap<>();

    public static Message de(String type) {
        return new Message().avec("type", type);
    }

    public Message avec(String cle, String valeur) {
        if (!cleValide(cle)) {
            throw new IllegalArgumentException("Clé invalide : " + cle);
        }
        if (valeur == null) {
            throw new IllegalArgumentException("Valeur absente pour " + cle);
        }
        champs.put(cle, valeur);
        return this;
    }

    public Message avec(String cle, long valeur) {
        return avec(cle, Long.toString(valeur));
    }

    public String type() {
        return champs.get("type");
    }

    /** @return la valeur, ou {@code null} si le champ est absent */
    public String get(String cle) {
        return champs.get(cle);
    }

    public String exiger(String cle) throws FormatInvalide {
        String v = champs.get(cle);
        if (v == null) {
            throw new FormatInvalide("Champ manquant : " + cle);
        }
        return v;
    }

    public long exigerNombre(String cle) throws FormatInvalide {
        try {
            return Long.parseLong(exiger(cle));
        } catch (NumberFormatException e) {
            throw new FormatInvalide("Nombre invalide : " + cle);
        }
    }

    public String encoder() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : champs.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(encoderValeur(e.getValue()));
        }
        return sb.toString();
    }

    public static Message decoder(String texte) throws FormatInvalide {
        if (texte == null) {
            throw new FormatInvalide("Message vide");
        }
        Message m = new Message();
        if (texte.isEmpty()) {
            return m;
        }
        for (String morceau : texte.split("&", -1)) {
            int egal = morceau.indexOf('=');
            if (egal <= 0) {
                throw new FormatInvalide("Champ mal formé");
            }
            String cle = morceau.substring(0, egal);
            if (!cleValide(cle)) {
                throw new FormatInvalide("Clé invalide");
            }
            if (m.champs.containsKey(cle)) {
                throw new FormatInvalide("Champ en double : " + cle);
            }
            try {
                m.champs.put(cle, URLDecoder.decode(morceau.substring(egal + 1), "UTF-8"));
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                throw new FormatInvalide("Valeur mal encodée : " + cle);
            }
        }
        return m;
    }

    private static String encoderValeur(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("UTF-8 est toujours disponible", e);
        }
    }

    private static boolean cleValide(String cle) {
        if (cle == null || cle.isEmpty() || cle.length() > 20) {
            return false;
        }
        for (int i = 0; i < cle.length(); i++) {
            char c = cle.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
