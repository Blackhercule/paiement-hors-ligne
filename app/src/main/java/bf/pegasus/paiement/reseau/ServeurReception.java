package bf.pegasus.paiement.reseau;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Serveur TCP du receveur. Il accepte UN SEUL payeur : dès qu'un téléphone est connecté,
 * toute autre tentative de connexion est fermée immédiatement.
 */
public final class ServeurReception implements Closeable {

    public interface Ecouteur {
        void pairAccepte(Socket socket);

        void connexionRefusee();
    }

    private final ServerSocket serveur;
    private final Ecouteur ecouteur;
    private volatile boolean pairDejaAccepte;
    private volatile boolean ferme;
    private int refusees;

    /** @param port 0 pour laisser le système choisir un port libre */
    public ServeurReception(int port, Ecouteur ecouteur) throws IOException {
        this.ecouteur = ecouteur;
        this.serveur = new ServerSocket();
        serveur.setReuseAddress(true);
        serveur.bind(new InetSocketAddress(port));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                accepter();
            }
        }, "phl-serveur");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return serveur.getLocalPort();
    }

    public synchronized int refusees() {
        return refusees;
    }

    private void accepter() {
        while (!ferme) {
            Socket s;
            try {
                s = serveur.accept();
            } catch (IOException e) {
                return; // serveur fermé
            }
            if (!pairDejaAccepte) {
                pairDejaAccepte = true;
                ecouteur.pairAccepte(s);
            } else {
                synchronized (this) {
                    refusees++;
                }
                try {
                    s.close();
                } catch (IOException ignoree) {
                    // rien à faire
                }
                ecouteur.connexionRefusee();
            }
        }
    }

    @Override
    public void close() {
        ferme = true;
        try {
            serveur.close();
        } catch (IOException ignoree) {
            // déjà fermé
        }
    }
}
