package bf.pegasus.paiement.ui;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** Génère l'image du QR code (bibliothèque ZXing, fonctionne sans réseau). */
final class Qr {

    private static final int ENCRE = 0xFF0F1F1A;
    private static final int FOND = 0xFFFFFFFF;

    private Qr() {
    }

    static Bitmap generer(String texte, int taille) throws WriterException {
        Map<EncodeHintType, Object> options = new EnumMap<>(EncodeHintType.class);
        options.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        options.put(EncodeHintType.MARGIN, 1);
        options.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrice = new QRCodeWriter().encode(texte, BarcodeFormat.QR_CODE, taille, taille, options);
        int l = matrice.getWidth();
        int h = matrice.getHeight();
        int[] pixels = new int[l * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < l; x++) {
                pixels[y * l + x] = matrice.get(x, y) ? ENCRE : FOND;
            }
        }
        Bitmap image = Bitmap.createBitmap(l, h, Bitmap.Config.ARGB_8888);
        image.setPixels(pixels, 0, l, 0, 0, l, h);
        return image;
    }
}
