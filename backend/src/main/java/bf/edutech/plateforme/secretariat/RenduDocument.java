package bf.edutech.plateforme.secretariat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.export.EntetePdf;

/**
 * Document administratif au format A4 (PDFBox, Helvetica) : en-tête officiel, numéro, titre, rubriques (objet,
 * destinataires…), texte en paragraphes sur une ou plusieurs pages, bloc de signature à droite (fonction, signature
 * numérisée du signataire si elle existe, nom). Un brouillon porte la mention « PROJET — NON SIGNÉ ».
 */
@Component
class RenduDocument {

    record Contenu(String numero, String titre, List<String[]> rubriques, String corps, LocalDate date,
            String fonction, String signataire, byte[] signature, boolean projet, String pied) {
    }

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final float MARGE = 50;
    private static final float TAILLE = 11;
    private static final float INTERLIGNE = 15;

    private final EntetesOfficiels entetes;

    RenduDocument(EntetesOfficiels entetes) {
        this.entetes = entetes;
    }

    byte[] rendre(Contenu c) {
        PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDFont italique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        PDRectangle format = PDRectangle.A4;
        float largeur = format.getWidth() - 2 * MARGE;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Page p = new Page(doc, format);
            float y = EntetePdf.dessiner(doc, p.flux, entetes.courant(), MARGE, format.getHeight() - 40, largeur,
                    List.of()) - 18;
            if (c.numero() != null) {
                texte(p.flux, normal, 10, "N° " + c.numero(), MARGE, y);
            }
            if (c.date() != null) {
                String d = "Le " + c.date().format(JOUR);
                texte(p.flux, normal, 10, d, MARGE + largeur - largeurDe(normal, 10, d), y);
            }
            y -= 34;
            for (String ligne : couper(gras, 14, propre(c.titre()).toUpperCase(Locale.FRENCH), largeur)) {
                texte(p.flux, gras, 14, ligne, MARGE + (largeur - largeurDe(gras, 14, ligne)) / 2, y);
                y -= 20;
            }
            if (c.projet()) {
                String m = "PROJET — NON SIGNÉ";
                texte(p.flux, italique, 10, m, MARGE + (largeur - largeurDe(italique, 10, m)) / 2, y);
                y -= 16;
            }
            y -= 8;
            for (String[] r : c.rubriques()) {
                if (r[1] == null || r[1].isBlank()) {
                    continue;
                }
                String libelle = propre(r[0]) + " : ";
                float l = largeurDe(gras, TAILLE, libelle);
                List<String> lignes = couper(normal, TAILLE, propre(r[1]), largeur - l);
                for (int i = 0; i < lignes.size(); i++) {
                    y = p.place(y, INTERLIGNE);
                    if (i == 0) {
                        texte(p.flux, gras, TAILLE, libelle, MARGE, y);
                    }
                    texte(p.flux, normal, TAILLE, lignes.get(i), MARGE + l, y);
                    y -= INTERLIGNE;
                }
            }
            y -= 10;
            for (String paragraphe : propre(c.corps() == null ? "" : c.corps()).split("\\R", -1)) {
                if (paragraphe.isBlank()) {
                    y -= INTERLIGNE / 2;
                    continue;
                }
                for (String ligne : couper(normal, TAILLE, paragraphe, largeur)) {
                    y = p.place(y, INTERLIGNE);
                    texte(p.flux, normal, TAILLE, ligne, MARGE, y);
                    y -= INTERLIGNE;
                }
                y -= 4;
            }
            // Bloc de signature, à droite
            y = p.place(y - 24, 110);
            float xSignature = MARGE + largeur * 0.55f;
            float lSignature = largeur * 0.45f;
            if (c.fonction() != null) {
                for (String ligne : couper(gras, TAILLE, propre(c.fonction()), lSignature)) {
                    texte(p.flux, gras, TAILLE, ligne, xSignature + (lSignature - largeurDe(gras, TAILLE, ligne)) / 2, y);
                    y -= INTERLIGNE;
                }
            }
            if (c.signature() != null && !c.projet()) {
                try {
                    PDImageXObject image = PDImageXObject.createFromByteArray(doc, c.signature(), "signature");
                    float echelle = Math.min(54f / image.getHeight(), 150f / image.getWidth());
                    float l = image.getWidth() * echelle;
                    float h = image.getHeight() * echelle;
                    p.flux.drawImage(image, xSignature + (lSignature - l) / 2, y - h, l, h);
                    y -= h + 6;
                } catch (IOException | IllegalArgumentException e) {
                    y -= 50;
                }
            } else {
                y -= 50;
            }
            if (c.signataire() != null) {
                String s = propre(c.signataire());
                texte(p.flux, gras, TAILLE, s, xSignature + (lSignature - largeurDe(gras, TAILLE, s)) / 2, y);
            }
            if (c.pied() != null) {
                texte(p.flux, italique, 8, propre(c.pied()), MARGE, 30);
            }
            p.flux.close();
            doc.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Page courante ; une nouvelle s'ouvre quand la place manque. */
    private static final class Page {
        private final PDDocument doc;
        private final PDRectangle format;
        PDPageContentStream flux;

        Page(PDDocument doc, PDRectangle format) throws IOException {
            this.doc = doc;
            this.format = format;
            ouvrir();
        }

        private void ouvrir() throws IOException {
            PDPage page = new PDPage(format);
            doc.addPage(page);
            flux = new PDPageContentStream(doc, page);
        }

        /** Ordonnée où écrire une ligne de hauteur {@code h} (page suivante si besoin). */
        float place(float y, float h) throws IOException {
            if (y - h >= 50) {
                return y;
            }
            flux.close();
            ouvrir();
            return format.getHeight() - 60;
        }
    }

    private static void texte(PDPageContentStream f, PDFont police, float taille, String s, float x, float y)
            throws IOException {
        f.beginText();
        f.setFont(police, taille);
        f.newLineAtOffset(x, y);
        f.showText(s);
        f.endText();
    }

    static float largeurDe(PDFont police, float taille, String s) {
        try {
            return police.getStringWidth(s) / 1000 * taille;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Coupe un texte en lignes qui tiennent dans {@code largeur} (mots trop longs coupés). */
    static List<String> couper(PDFont police, float taille, String texte, float largeur) {
        List<String> lignes = new ArrayList<>();
        StringBuilder ligne = new StringBuilder();
        for (String mot : texte.strip().split("\\s+")) {
            String essai = ligne.isEmpty() ? mot : ligne + " " + mot;
            if (largeurDe(police, taille, essai) <= largeur) {
                ligne.setLength(0);
                ligne.append(essai);
                continue;
            }
            if (!ligne.isEmpty()) {
                lignes.add(ligne.toString());
                ligne.setLength(0);
            }
            while (largeurDe(police, taille, mot) > largeur && mot.length() > 1) {
                int n = mot.length() - 1;
                while (n > 1 && largeurDe(police, taille, mot.substring(0, n)) > largeur) {
                    n--;
                }
                lignes.add(mot.substring(0, n));
                mot = mot.substring(n);
            }
            ligne.append(mot);
        }
        if (!ligne.isEmpty()) {
            lignes.add(ligne.toString());
        }
        return lignes;
    }

    /** Remplace les caractères que la police standard ne sait pas écrire (émojis…). */
    static String propre(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == '\t') {
                b.append("    ");
            } else if (cp == '\r' || cp == '\n') {
                b.appendCodePoint(cp);
            } else if (cp < 32) {
                // caractère de contrôle ignoré
            } else if (cp == 0x2018 || cp == 0x2019) {
                b.append('\'');
            } else if (cp == 0x201C || cp == 0x201D) {
                b.append('"');
            } else if (cp == 0x2014 || cp == 0x2013 || cp == 0x2026 || cp == 0x20AC || cp == 0x0153 || cp == 0x0152
                    || cp == 0xAB || cp == 0xBB || cp == 0xA0 || cp == 0x202F) {
                b.appendCodePoint(cp == 0x202F ? 0xA0 : cp);
            } else if (cp > 0xFF) {
                b.append('?');
            } else {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }
}
