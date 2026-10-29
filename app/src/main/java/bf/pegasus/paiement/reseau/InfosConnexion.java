package bf.pegasus.paiement.reseau;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bf.pegasus.paiement.crypto.Octets;
import bf.pegasus.paiement.metier.Message;
import bf.pegasus.paiement.metier.Portefeuille;

/**
 * Contenu du QR code affiché par le receveur : tout ce qu'il faut au payeur pour se connecter
 * (réseau Wi-Fi, port, clé publique temporaire, numéro de session).
 *
 * <p>Le QR est lu en face-à-face : c'est le canal de confiance. Un tiers ne peut pas
 * remplacer la clé publique du receveur sans remplacer le QR code lui-même.</p>
 */
public final class InfosConnexion {

    public static final String PREFIXE = "PHL1:";

    public final String ssid;
    public final String motDePasse;
    /** WPA2, WPA3, OWE ou OUVERT. */
    public final String securite;
    public final int port;
    /** Adresses IP possibles du receveur sur son point d'accès. */
    public final List<String> adresses;
    public final String clePublique;
    /** Numéro de session : 16 caractères hexadécimaux, neuf à chaque session. */
    public final String session;
    public final String nom;
    /** Identifiant du téléphone receveur. */
    public final String appareil;

    public InfosConnexion(String ssid, String motDePasse, String securite, int port, List<String> adresses,
                          String clePublique, String session, String nom, String appareil) {
        this.ssid = ssid;
        this.motDePasse = motDePasse == null ? "" : motDePasse;
        this.securite = securite == null ? "WPA2" : securite;
        this.port = port;
        this.adresses = Collections.unmodifiableList(new ArrayList<>(adresses));
        this.clePublique = clePublique;
        this.session = session;
        this.nom = Portefeuille.nettoyerNom(nom);
        this.appareil = appareil;
    }

    public String encoder() {
        return PREFIXE + new Message()
                .avec("ssid", ssid)
                .avec("mdp", motDePasse)
                .avec("secu", securite)
                .avec("port", port)
                .avec("ip", joindre(adresses))
                .avec("pk", clePublique)
                .avec("sid", session)
                .avec("nom", nom)
                .avec("app", appareil)
                .encoder();
    }

    public static InfosConnexion decoder(String texte) throws Message.FormatInvalide {
        if (texte == null || !texte.startsWith(PREFIXE)) {
            throw new Message.FormatInvalide("Ce QR code ne vient pas de l'application.");
        }
        Message m = Message.decoder(texte.substring(PREFIXE.length()));
        String ssid = m.exiger("ssid");
        if (ssid.isEmpty() || ssid.length() > 32) {
            throw new Message.FormatInvalide("Nom de réseau invalide");
        }
        String securite = m.exiger("secu");
        if (!securite.equals("WPA2") && !securite.equals("WPA3") && !securite.equals("OWE")
                && !securite.equals("OUVERT")) {
            throw new Message.FormatInvalide("Type de sécurité inconnu");
        }
        long port = m.exigerNombre("port");
        if (port < 1 || port > 65535) {
            throw new Message.FormatInvalide("Port invalide");
        }
        List<String> adresses = new ArrayList<>();
        for (String a : m.exiger("ip").split(",")) {
            if (!a.isEmpty()) {
                if (!estIpv4(a)) {
                    throw new Message.FormatInvalide("Adresse invalide");
                }
                adresses.add(a);
            }
        }
        String cle = m.exiger("pk");
        if (cle.isEmpty() || cle.length() > 200) {
            throw new Message.FormatInvalide("Clé publique invalide");
        }
        String session = m.exiger("sid");
        String appareil = m.exiger("app");
        if (!Octets.estHex(session, 16) || !Octets.estHex(appareil, 16)) {
            throw new Message.FormatInvalide("Identifiants invalides");
        }
        return new InfosConnexion(ssid, m.exiger("mdp"), securite, (int) port, adresses, cle, session,
                m.exiger("nom"), appareil);
    }

    /** Vrai pour une adresse IPv4 écrite en chiffres (aucune résolution DNS). */
    public static boolean estIpv4(String s) {
        String[] parties = s.split("\\.", -1);
        if (parties.length != 4) {
            return false;
        }
        for (String p : parties) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String joindre(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
