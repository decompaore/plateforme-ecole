package bf.edutech.plateforme.socle.images;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Normalisation des images déposées (v0.44) : photos d'identité et signatures.
 * <ul>
 * <li>Le type est reconnu aux premiers octets (PNG ou JPEG), jamais au nom du fichier.</li>
 * <li>L'image est décodée puis réencodée : un fichier qui n'est pas réellement une image est refusé, et
 * rien d'autre que les pixels n'est conservé (pas de métadonnées, ni position GPS d'un téléphone).</li>
 * <li>Elle est réduite pour tenir dans un cadre (une photo d'identité n'a pas besoin de 12 mégapixels).</li>
 * </ul>
 * Le navigateur redresse et réduit déjà la photo avant l'envoi ; ce contrôle reste la garantie du serveur.
 */
public final class Images {

    /** Taille d'un fichier déposé avant traitement : 5 Mo (photo d'un téléphone). */
    public static final int TAILLE_MAX = 5 * 1024 * 1024;

    /** Côté le plus long d'une image décodée : au-delà, l'image est refusée (protection mémoire). */
    private static final int PIXELS_MAX = 8000;

    public record Image(String type, byte[] contenu, int largeur, int hauteur) {
    }

    private Images() {
    }

    /** Photo d'identité : JPEG, tient dans {@code largeurMax} × {@code hauteurMax} pixels. */
    public static Image photo(byte[] contenu, int largeurMax, int hauteurMax) {
        BufferedImage source = lire(contenu);
        BufferedImage reduite = reduire(source, largeurMax, hauteurMax, false);
        return new Image("image/jpeg", jpeg(reduite, 0.85f), reduite.getWidth(), reduite.getHeight());
    }

    /** Signature : PNG (la transparence est gardée), tient dans {@code largeurMax} × {@code hauteurMax} pixels. */
    public static Image signature(byte[] contenu, int largeurMax, int hauteurMax) {
        BufferedImage source = lire(contenu);
        BufferedImage reduite = reduire(source, largeurMax, hauteurMax, true);
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            ImageIO.write(reduite, "png", sortie);
            return new Image("image/png", sortie.toByteArray(), reduite.getWidth(), reduite.getHeight());
        } catch (IOException e) {
            throw new IllegalArgumentException("Image illisible : utilisez un fichier PNG ou JPEG");
        }
    }

    /** Type d'après les premiers octets. */
    public static String type(byte[] o) {
        if (o != null && o.length >= 8 && (o[0] & 0xFF) == 0x89 && o[1] == 'P' && o[2] == 'N' && o[3] == 'G') {
            return "image/png";
        }
        if (o != null && o.length >= 3 && (o[0] & 0xFF) == 0xFF && (o[1] & 0xFF) == 0xD8 && (o[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        throw new IllegalArgumentException("Format non pris en charge : utilisez une photo JPEG ou une image PNG");
    }

    private static BufferedImage lire(byte[] contenu) {
        if (contenu == null || contenu.length == 0) {
            throw new IllegalArgumentException("Fichier vide");
        }
        if (contenu.length > TAILLE_MAX) {
            throw new IllegalArgumentException("Image trop lourde : 5 Mo au plus");
        }
        type(contenu);
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(contenu));
        } catch (IOException | RuntimeException e) {
            image = null;
        }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IllegalArgumentException("Image illisible : utilisez une photo JPEG ou une image PNG");
        }
        if (image.getWidth() > PIXELS_MAX || image.getHeight() > PIXELS_MAX) {
            throw new IllegalArgumentException("Image trop grande : " + PIXELS_MAX + " pixels de côté au plus");
        }
        return image;
    }

    private static BufferedImage reduire(BufferedImage source, int largeurMax, int hauteurMax, boolean transparence) {
        double echelle = Math.min(1.0, Math.min((double) largeurMax / source.getWidth(), (double) hauteurMax / source.getHeight()));
        int l = Math.max(1, (int) Math.round(source.getWidth() * echelle));
        int h = Math.max(1, (int) Math.round(source.getHeight() * echelle));
        BufferedImage cible = new BufferedImage(l, h, transparence ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cible.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (transparence) {
                g.setComposite(AlphaComposite.Src);
            } else {
                // Fond blanc sous une image transparente (une photo n'a pas de transparence)
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, l, h);
            }
            g.drawImage(source, 0, 0, l, h, null);
        } finally {
            g.dispose();
        }
        return cible;
    }

    private static byte[] jpeg(BufferedImage image, float qualite) {
        Iterator<ImageWriter> ecrivains = ImageIO.getImageWritersByFormatName("jpeg");
        if (!ecrivains.hasNext()) {
            throw new IllegalStateException("Aucun encodeur JPEG disponible");
        }
        ImageWriter ecrivain = ecrivains.next();
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream();
                ImageOutputStream flux = ImageIO.createImageOutputStream(sortie)) {
            ecrivain.setOutput(flux);
            ImageWriteParam p = ecrivain.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(qualite);
            ecrivain.write(null, new IIOImage(image, null, null), p);
            flux.flush();
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Image illisible : utilisez une photo JPEG ou une image PNG");
        } finally {
            ecrivain.dispose();
        }
    }
}
