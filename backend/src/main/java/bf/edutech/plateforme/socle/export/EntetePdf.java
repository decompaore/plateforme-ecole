package bf.edutech.plateforme.socle.export;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import bf.edutech.plateforme.socle.documents.EnteteOfficiel;

/**
 * Dessine l'en-tête officiel commun à tous les documents PDF.
 * <pre>
 *   BURKINA FASO                                          [logo]
 *   Devise nationale                               Lycée technique
 *   ----------                                     Année 2026-2027
 *   Ministère …
 *   Direction régionale …
 *   Direction provinciale …
 * </pre>
 * Sans rattachement, seul le nom de l'établissement (et son logo) figure, à gauche.
 */
public final class EntetePdf {

    private static final float HAUTEUR_LOGO = 48;
    private static final float LARGEUR_LOGO = 96;

    private EntetePdf() {
    }

    /**
     * Dessine l'en-tête sous {@code yHaut} et renvoie l'ordonnée sous le bloc le plus bas.
     *
     * @param complements lignes ajoutées sous le nom de l'établissement (adresse, année…)
     */
    public static float dessiner(PDDocument doc, PDPageContentStream f, EnteteOfficiel e, float x, float yHaut,
            float largeur, List<String> complements) throws IOException {
        PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDFont italique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        float yGauche = yHaut;
        float yDroite = yHaut;

        // Colonne de droite : logo, puis établissement et compléments, alignés à droite
        float largeurDroite = e.officiel() ? largeur * 0.42f : largeur * 0.5f;
        float xDroite = x + largeur - largeurDroite;
        PDImageXObject image = image(doc, e.logo());
        if (image != null) {
            float echelle = Math.min(HAUTEUR_LOGO / image.getHeight(), LARGEUR_LOGO / image.getWidth());
            float l = image.getWidth() * echelle;
            float h = image.getHeight() * echelle;
            f.drawImage(image, x + largeur - l, yHaut - h + 8, l, h);
            yDroite = yHaut - h - 4;
        }

        if (!e.officiel()) {
            // Pas de rattachement : l'établissement à gauche, comme auparavant
            yGauche = lignes(f, gras, 10, e.etablissement(), x, yGauche, largeur - (image != null ? LARGEUR_LOGO + 10 : 0),
                    Alignement.GAUCHE);
            for (String c : complements) {
                yGauche = lignes(f, normal, 8, c, x, yGauche, largeur * 0.6f, Alignement.GAUCHE);
            }
            return Math.min(yGauche, yDroite);
        }

        float largeurGauche = largeur * 0.52f;
        if (e.pays() != null) {
            yGauche = lignes(f, gras, 9, e.pays().toUpperCase(Locale.FRENCH), x, yGauche, largeurGauche, Alignement.CENTRE);
        }
        if (e.devise() != null && !e.devise().isBlank()) {
            yGauche = lignes(f, italique, 7.5f, e.devise(), x, yGauche, largeurGauche, Alignement.CENTRE);
        }
        if (e.pays() != null) {
            float milieu = x + largeurGauche / 2;
            f.moveTo(milieu - 22, yGauche + 4);
            f.lineTo(milieu + 22, yGauche + 4);
            f.stroke();
            yGauche -= 4;
        }
        for (String autorite : e.autorites()) {
            yGauche = lignes(f, normal, 7.5f, autorite, x, yGauche, largeurGauche, Alignement.CENTRE);
        }
        yDroite = lignes(f, gras, 10, e.etablissement(), xDroite, yDroite, largeurDroite, Alignement.DROITE);
        for (String c : complements) {
            yDroite = lignes(f, normal, 8, c, xDroite, yDroite, largeurDroite, Alignement.DROITE);
        }
        return Math.min(yGauche, yDroite);
    }

    /** Vérifie qu'une image peut être imprimée (logo téléversé). */
    public static void verifierImage(byte[] contenu) {
        try (PDDocument doc = new PDDocument()) {
            if (image(doc, contenu) == null) {
                throw new IllegalArgumentException("Image vide");
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Image illisible : utilisez un fichier PNG ou JPEG");
        }
    }

    private static PDImageXObject image(PDDocument doc, byte[] contenu) {
        if (contenu == null || contenu.length == 0) {
            return null;
        }
        try {
            return PDImageXObject.createFromByteArray(doc, contenu, "logo");
        } catch (IOException | RuntimeException e) {
            return null; // un logo illisible ne doit jamais empêcher un document
        }
    }

    private enum Alignement {
        GAUCHE, CENTRE, DROITE
    }

    private static float lignes(PDPageContentStream f, PDFont police, float taille, String texte, float x, float y,
            float largeur, Alignement alignement) throws IOException {
        float courant = y;
        List<String> morceaux = texte == null ? new ArrayList<>() : ExportTableaux.couper(police, taille, texte, largeur);
        for (String l : morceaux) {
            float lt = police.getStringWidth(l) / 1000 * taille;
            float lx = switch (alignement) {
                case GAUCHE -> x;
                case CENTRE -> x + (largeur - lt) / 2;
                case DROITE -> x + largeur - lt;
            };
            f.beginText();
            f.setFont(police, taille);
            f.newLineAtOffset(lx, courant);
            f.showText(l);
            f.endText();
            courant -= taille + 2.5f;
        }
        return courant;
    }
}
