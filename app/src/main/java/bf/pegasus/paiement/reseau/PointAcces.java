package bf.pegasus.paiement.reseau;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Point d'accès Wi-Fi local du receveur ({@code WifiManager.startLocalOnlyHotspot}, Android 8+).
 * Le système choisit le nom du réseau et le mot de passe ; ce réseau ne donne pas accès
 * à Internet : il sert uniquement à relier les deux téléphones.
 */
public final class PointAcces {

    public interface Rappel {
        void demarre(String ssid, String motDePasse, String securite);

        void echec(String message);

        void arrete();
    }

    private final WifiManager wifi;
    private final Handler fil = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean arretDemande;

    public PointAcces(Context contexte) {
        wifi = (WifiManager) contexte.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    @SuppressLint("MissingPermission") // permissions demandées par l'écran « Recevoir »
    public void demarrer(final Rappel rappel) {
        arretDemande = false;
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    if (arretDemande) {
                        r.close();
                        return;
                    }
                    reservation = r;
                    String[] infos = lireConfiguration(r);
                    if (infos[0] == null || infos[0].isEmpty()) {
                        rappel.echec("Point d'accès créé, mais son nom est illisible.");
                        return;
                    }
                    rappel.demarre(infos[0], infos[1], infos[2]);
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    rappel.arrete();
                }

                @Override
                public void onFailed(int raison) {
                    rappel.echec(messageErreur(raison));
                }
            }, fil);
        } catch (SecurityException e) {
            rappel.echec("Permission refusée : impossible de créer le point d'accès.");
        } catch (IllegalStateException e) {
            rappel.echec("Un point d'accès est déjà en cours. Fermez cet écran puis réessayez.");
        }
    }

    public void arreter() {
        arretDemande = true;
        if (reservation != null) {
            reservation.close();
            reservation = null;
        }
    }

    /** @return {nom du réseau, mot de passe, sécurité} */
    @SuppressWarnings("deprecation")
    private static String[] lireConfiguration(WifiManager.LocalOnlyHotspotReservation r) {
        if (Build.VERSION.SDK_INT >= 30) {
            SoftApConfiguration conf = r.getSoftApConfiguration();
            String ssid;
            if (Build.VERSION.SDK_INT >= 33) {
                WifiSsid wifiSsid = conf.getWifiSsid();
                ssid = wifiSsid == null ? null : new String(wifiSsid.getBytes(), StandardCharsets.UTF_8);
            } else {
                ssid = conf.getSsid();
            }
            return new String[]{ssid, conf.getPassphrase(), securite(conf.getSecurityType())};
        }
        WifiConfiguration conf = r.getWifiConfiguration();
        if (conf == null) {
            return new String[]{null, null, "WPA2"};
        }
        return new String[]{sansGuillemets(conf.SSID), sansGuillemets(conf.preSharedKey), "WPA2"};
    }

    private static String securite(int type) {
        switch (type) {
            case 0: // SECURITY_TYPE_OPEN
                return "OUVERT";
            case 3: // SECURITY_TYPE_WPA3_SAE
                return "WPA3";
            case 4: // SECURITY_TYPE_WPA3_OWE_TRANSITION
            case 5: // SECURITY_TYPE_WPA3_OWE
                return "OWE";
            default: // WPA2_PSK, ou WPA3 en mode transition (accepte WPA2)
                return "WPA2";
        }
    }

    private static String sansGuillemets(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String messageErreur(int raison) {
        switch (raison) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "Aucun canal Wi-Fi disponible. Réessayez dans un instant.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "Coupez d'abord le partage de connexion (point d'accès mobile), puis réessayez.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "Ce téléphone interdit la création d'un point d'accès.";
            default:
                return "Le point d'accès n'a pas pu démarrer. Vérifiez que la localisation est activée "
                        + "et que le partage de connexion est coupé.";
        }
    }

    /**
     * Adresses IPv4 locales de ce téléphone, celles du point d'accès en premier.
     * Elles sont mises dans le QR code pour que le payeur trouve le receveur.
     */
    public static List<String> adressesLocales() {
        List<String> prioritaires = new ArrayList<>();
        List<String> autres = new ArrayList<>();
        try {
            java.util.Enumeration<NetworkInterface> liste = NetworkInterface.getNetworkInterfaces();
            if (liste == null) {
                return prioritaires;
            }
            for (NetworkInterface ni : Collections.list(liste)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String nom = ni.getName().toLowerCase(Locale.ROOT);
                if (nom.startsWith("rmnet") || nom.startsWith("ccmni") || nom.startsWith("dummy")
                        || nom.startsWith("tun") || nom.startsWith("ppp")) {
                    continue; // données mobiles et VPN
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        boolean pointAcces = nom.startsWith("ap") || nom.startsWith("swlan")
                                || nom.startsWith("softap") || nom.startsWith("wlan1");
                        (pointAcces ? prioritaires : autres).add(a.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            // pas d'interface lisible : le payeur utilisera la passerelle de son Wi-Fi
        }
        prioritaires.addAll(autres);
        return prioritaires.size() > 4 ? new ArrayList<>(prioritaires.subList(0, 4)) : prioritaires;
    }
}
