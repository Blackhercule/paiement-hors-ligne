package bf.pegasus.paiement.reseau;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Connexion du payeur au point d'accès du receveur.
 * <ul>
 *   <li>Android 10 et plus : {@code WifiNetworkSpecifier}. Android affiche une fenêtre
 *       « Se connecter à l'appareil ? » que l'utilisateur valide.</li>
 *   <li>Android 8 et 9 : ancienne API ({@code WifiConfiguration}).</li>
 * </ul>
 * Les sockets sont ensuite liées à ce réseau Wi-Fi, même si les données mobiles sont actives.
 */
public final class ConnexionWifi {

    public interface Rappel {
        /** @param passerelles adresses du receveur vues depuis ce réseau */
        void connecte(Network reseau, List<String> passerelles);

        void echec(String message);
    }

    private static final int DELAI_MS = 60_000;

    private final ConnectivityManager connectivite;
    private final WifiManager wifi;
    private final Handler fil = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback rappelReseau;
    private int idAncienReseau = -1;
    private boolean termine;

    public ConnexionWifi(Context contexte) {
        Context app = contexte.getApplicationContext();
        connectivite = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    }

    public void connecter(InfosConnexion receveur, Rappel rappel) {
        termine = false;
        if (Build.VERSION.SDK_INT >= 29) {
            connecterAndroid10(receveur, rappel);
        } else {
            connecterAndroid8(receveur, rappel);
        }
    }

    /** Fabrique de sockets liées au réseau Wi-Fi du receveur. */
    public static SessionPayeur.FabriqueSocket fabrique(final Network reseau) {
        return new SessionPayeur.FabriqueSocket() {
            @Override
            public Socket creer() throws IOException {
                Socket s = new Socket();
                if (reseau != null) {
                    reseau.bindSocket(s);
                }
                return s;
            }
        };
    }

    @TargetApi(29)
    private void connecterAndroid10(InfosConnexion receveur, final Rappel rappel) {
        WifiNetworkSpecifier.Builder specification = new WifiNetworkSpecifier.Builder().setSsid(receveur.ssid);
        switch (receveur.securite) {
            case "WPA3":
                specification.setWpa3Passphrase(receveur.motDePasse);
                break;
            case "OWE":
                if (Build.VERSION.SDK_INT >= 30) {
                    specification.setIsEnhancedOpen(true);
                }
                break;
            case "OUVERT":
                break;
            default:
                specification.setWpa2Passphrase(receveur.motDePasse);
                break;
        }
        NetworkRequest demande = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specification.build())
                .build();
        rappelReseau = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(final Network reseau) {
                fil.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!termine) {
                            termine = true;
                            rappel.connecte(reseau, passerelles(reseau));
                        }
                    }
                });
            }

            @Override
            public void onUnavailable() {
                fil.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!termine) {
                            termine = true;
                            rappel.echec("Connexion au réseau du receveur impossible "
                                    + "(refusée, ou réseau introuvable).");
                        }
                    }
                });
            }
        };
        try {
            connectivite.requestNetwork(demande, rappelReseau, fil, DELAI_MS);
        } catch (RuntimeException e) {
            rappelReseau = null;
            rappel.echec("Android refuse la demande de connexion Wi-Fi : " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private void connecterAndroid8(final InfosConnexion receveur, final Rappel rappel) {
        if (!wifi.isWifiEnabled()) {
            wifi.setWifiEnabled(true);
        }
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + receveur.ssid + "\"";
        if ("OUVERT".equals(receveur.securite) || "OWE".equals(receveur.securite)) {
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            conf.preSharedKey = "\"" + receveur.motDePasse + "\"";
        }
        final int id = wifi.addNetwork(conf);
        if (id == -1) {
            rappel.echec("Impossible d'ajouter le réseau Wi-Fi du receveur.");
            return;
        }
        idAncienReseau = id;
        wifi.disconnect();
        wifi.enableNetwork(id, true);
        wifi.reconnect();

        final long limite = SystemClock.elapsedRealtime() + DELAI_MS / 2;
        fil.post(new Runnable() {
            @Override
            public void run() {
                if (termine) {
                    return;
                }
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null && info.getNetworkId() == id && info.getIpAddress() != 0) {
                    for (Network n : connectivite.getAllNetworks()) {
                        NetworkCapabilities c = connectivite.getNetworkCapabilities(n);
                        if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            termine = true;
                            List<String> adresses = passerelles(n);
                            android.net.DhcpInfo infoDhcp = wifi.getDhcpInfo();
                            String dhcp = infoDhcp == null ? null : ipv4(infoDhcp.gateway);
                            if (dhcp != null && !adresses.contains(dhcp)) {
                                adresses.add(dhcp);
                            }
                            rappel.connecte(n, adresses);
                            return;
                        }
                    }
                }
                if (SystemClock.elapsedRealtime() > limite) {
                    termine = true;
                    rappel.echec("Le téléphone ne s'est pas connecté au point d'accès du receveur.");
                    return;
                }
                fil.postDelayed(this, 500);
            }
        });
    }

    /** Adresse du receveur : la passerelle (serveur DHCP) du réseau Wi-Fi. */
    private List<String> passerelles(Network reseau) {
        List<String> resultat = new ArrayList<>();
        LinkProperties lp = connectivite.getLinkProperties(reseau);
        if (lp == null) {
            return resultat;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            InetAddress dhcp = lp.getDhcpServerAddress();
            if (dhcp instanceof Inet4Address) {
                resultat.add(dhcp.getHostAddress());
            }
        }
        for (RouteInfo route : lp.getRoutes()) {
            InetAddress passerelle = route.getGateway();
            if (passerelle instanceof Inet4Address && !passerelle.isAnyLocalAddress()) {
                String ip = passerelle.getHostAddress();
                if (!resultat.contains(ip)) {
                    resultat.add(ip);
                }
            }
        }
        return resultat;
    }

    private static String ipv4(int adresse) {
        if (adresse == 0) {
            return null;
        }
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                adresse & 0xFF, (adresse >> 8) & 0xFF, (adresse >> 16) & 0xFF, (adresse >> 24) & 0xFF);
    }

    /** Libère le réseau Wi-Fi du receveur (fin de session). */
    @SuppressWarnings("deprecation")
    public void liberer() {
        termine = true;
        fil.removeCallbacksAndMessages(null);
        if (rappelReseau != null) {
            try {
                connectivite.unregisterNetworkCallback(rappelReseau);
            } catch (IllegalArgumentException ignoree) {
                // déjà libéré
            }
            rappelReseau = null;
        }
        if (idAncienReseau != -1) {
            wifi.removeNetwork(idAncienReseau);
            idAncienReseau = -1;
        }
    }
}
