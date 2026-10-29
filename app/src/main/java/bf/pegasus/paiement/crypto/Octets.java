package bf.pegasus.paiement.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Petites fonctions utilitaires sur les octets : aléatoire, hexadécimal, base64, effacement. */
public final class Octets {

    private static final SecureRandom ALEA = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Octets() {
    }

    /** Octets aléatoires issus d'un générateur cryptographique. */
    public static byte[] aleatoires(int taille) {
        byte[] b = new byte[taille];
        ALEA.nextBytes(b);
        return b;
    }

    /** Identifiant unique de 128 bits (32 caractères hexadécimaux). */
    public static String identifiant() {
        return hex(aleatoires(16));
    }

    public static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[2 * i] = HEX[(b[i] >> 4) & 0xF];
            c[2 * i + 1] = HEX[b[i] & 0xF];
        }
        return new String(c);
    }

    public static byte[] deHex(String s) {
        if (s == null || s.length() % 2 != 0) {
            throw new IllegalArgumentException("Hexadécimal invalide");
        }
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int fort = Character.digit(s.charAt(2 * i), 16);
            int faible = Character.digit(s.charAt(2 * i + 1), 16);
            if (fort < 0 || faible < 0) {
                throw new IllegalArgumentException("Hexadécimal invalide");
            }
            b[i] = (byte) ((fort << 4) | faible);
        }
        return b;
    }

    /** Vrai si la chaîne contient exactement {@code longueur} caractères hexadécimaux minuscules. */
    public static boolean estHex(String s, int longueur) {
        if (s == null || s.length() != longueur) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** Base64 « URL » sans remplissage : sûr dans un QR code et dans une ligne de texte. */
    public static String base64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** @throws IllegalArgumentException si le texte n'est pas du base64 valide */
    public static byte[] deBase64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String texte(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Écrase une clé ou un secret en mémoire dès qu'il ne sert plus. */
    public static void effacer(byte[] b) {
        if (b != null) {
            Arrays.fill(b, (byte) 0);
        }
    }

    /** Comparaison en temps constant (évite les attaques par mesure du temps). */
    public static boolean egaux(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
