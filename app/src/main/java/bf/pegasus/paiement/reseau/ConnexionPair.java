package bf.pegasus.paiement.reseau;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import bf.pegasus.paiement.crypto.CanalChiffre;
import bf.pegasus.paiement.metier.Message;

/**
 * Connexion TCP avec l'autre téléphone : une ligne de texte par message.
 * Après la poignée de main, chaque ligne est chiffrée par le {@link CanalChiffre}.
 */
final class ConnexionPair implements Closeable {

    /** Une ligne plus longue est refusée (protection contre l'épuisement de la mémoire). */
    static final int TAILLE_MAX_LIGNE = 8 * 1024;

    private final Socket socket;
    private final InputStream entree;
    private final OutputStream sortie;
    private volatile CanalChiffre canal;

    ConnexionPair(Socket socket) throws IOException {
        this.socket = socket;
        this.entree = new BufferedInputStream(socket.getInputStream());
        this.sortie = socket.getOutputStream();
    }

    Socket socket() {
        return socket;
    }

    void activerChiffrement(CanalChiffre c) {
        this.canal = c;
    }

    /** @return la ligne lue, ou {@code null} si l'autre téléphone a fermé la connexion */
    String lireLigne() throws IOException {
        ByteArrayOutputStream tampon = new ByteArrayOutputStream();
        while (true) {
            int b = entree.read();
            if (b == -1) {
                return tampon.size() == 0 ? null : tampon.toString("UTF-8");
            }
            if (b == '\n') {
                return tampon.toString("UTF-8");
            }
            if (tampon.size() >= TAILLE_MAX_LIGNE) {
                throw new IOException("Message trop long");
            }
            tampon.write(b);
        }
    }

    synchronized void ecrireLigne(String ligne) throws IOException {
        sortie.write((ligne + "\n").getBytes(StandardCharsets.UTF_8));
        sortie.flush();
    }

    void envoyer(Message m) throws IOException, GeneralSecurityException {
        ecrireLigne(canal.sceller(m.encoder()));
    }

    /** @return le message déchiffré, ou {@code null} si la connexion est fermée */
    Message recevoir() throws IOException, GeneralSecurityException, Message.FormatInvalide {
        String ligne = lireLigne();
        if (ligne == null) {
            return null;
        }
        return Message.decoder(canal.ouvrir(ligne));
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignoree) {
            // déjà fermée
        }
        CanalChiffre c = canal;
        if (c != null) {
            c.effacer();
        }
    }
}
