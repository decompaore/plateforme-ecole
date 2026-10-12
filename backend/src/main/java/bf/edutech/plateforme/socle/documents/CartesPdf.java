package bf.edutech.plateforme.socle.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Cartes d'identité scolaires et professionnelles (v0.44) : format carte bancaire (85,6 × 54 mm),
 * huit par page A4, avec traits de coupe. En-tête de l'établissement (logo et nom), photo, nom,
 * informations utiles et pied (année scolaire). Noir et blanc possible : rien ne dépend de la couleur.
 */
public final class CartesPdf {

    /** Une carte : identité, lignes « libellé : valeur », photo (JPEG ou PNG, facultative) et pied. */
    public record Carte(String nom, String prenoms, List<String[]> lignes, byte[] photo, String pied) {
    }

    private static final float MM = 72f / 25.4f;
    private static final float L_CARTE = 85.6f * MM;
    private static final float H_CARTE = 54f * MM;
    private static final float ECART = 6 * MM;
    private static final int COLONNES = 2;
    private static final int RANGEES = 4;

    private final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private CartesPdf() {
    }

    /** @param titre « CARTE D'IDENTITÉ SCOLAIRE », « CARTE PROFESSIONNELLE »… */
    public static byte[] rendre(EnteteOfficiel entete, String titre, List<Carte> cartes) {
        return new CartesPdf().produire(entete, titre, cartes);
    }

    private byte[] produire(EnteteOfficiel entete, String titre, List<Carte> cartes) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            PDImageXObject logo = image(doc, entete != null ? entete.logo() : null, "logo");
            String etablissement = entete != null ? entete.etablissement() : "";
            float largeurPage = PDRectangle.A4.getWidth();
            float hauteurPage = PDRectangle.A4.getHeight();
            float x0 = (largeurPage - (COLONNES * L_CARTE + (COLONNES - 1) * ECART)) / 2;
            float yHaut = (hauteurPage + (RANGEES * H_CARTE + (RANGEES - 1) * ECART)) / 2;
            int parPage = COLONNES * RANGEES;
            for (int debut = 0; debut < Math.max(cartes.size(), 1); debut += parPage) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream f = new PDPageContentStream(doc, page)) {
                    if (cartes.isEmpty()) {
                        texte(f, normal, 10, 72, hauteurPage - 72, "Aucune carte à imprimer.");
                    }
                    for (int i = debut; i < Math.min(cartes.size(), debut + parPage); i++) {
                        int k = i - debut;
                        float x = x0 + (k % COLONNES) * (L_CARTE + ECART);
                        float y = yHaut - (k / COLONNES + 1) * H_CARTE - (k / COLONNES) * ECART;
                        carte(doc, f, x, y, cartes.get(i), titre, etablissement, logo);
                    }
                }
            }
            doc.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Production des cartes impossible", e);
        }
    }

    private void carte(PDDocument doc, PDPageContentStream f, float x, float y, Carte c, String titre, String etablissement,
            PDImageXObject logo) throws IOException {
        f.setLineWidth(0.5f);
        f.setStrokingColor(0.6f);
        f.addRect(x, y, L_CARTE, H_CARTE);
        f.stroke();
        f.setStrokingColor(0f);

        float marge = 2.5f * MM;
        float haut = y + H_CARTE - marge;
        // En-tête : logo et nom de l'établissement
        float xNom = x + marge;
        if (logo != null) {
            float h = 9 * MM;
            float l = logo.getWidth() * h / logo.getHeight();
            l = Math.min(l, 14 * MM);
            float hEffectif = logo.getHeight() * l / logo.getWidth();
            f.drawImage(logo, x + marge, haut - hEffectif, l, hEffectif);
            xNom += l + 2 * MM;
        }
        float largeurNom = x + L_CARTE - marge - xNom;
        List<String> lignesNom = couper(gras, 7, propre(etablissement), largeurNom, 2);
        float yy = haut - 6;
        for (String l : lignesNom) {
            texte(f, gras, 7, xNom, yy, l);
            yy -= 8;
        }
        float yTitre = haut - 11 * MM;
        f.setNonStrokingColor(0.15f);
        f.addRect(x, yTitre - 2, L_CARTE, 9);
        f.fill();
        f.setNonStrokingColor(1f);
        String t = propre(titre);
        texte(f, gras, 6.5f, x + (L_CARTE - largeur(gras, 6.5f, t)) / 2, yTitre + 0.5f, t);
        f.setNonStrokingColor(0f);

        // Photo à gauche, ou cadre vide à coller
        float lPhoto = 20 * MM;
        float hPhoto = 25 * MM;
        float xPhoto = x + marge;
        float yPhoto = yTitre - 4 - hPhoto;
        PDImageXObject photo = image(doc, c.photo(), "photo");
        if (photo != null) {
            float echelle = Math.min(lPhoto / photo.getWidth(), hPhoto / photo.getHeight());
            float l = photo.getWidth() * echelle;
            float h = photo.getHeight() * echelle;
            f.drawImage(photo, xPhoto + (lPhoto - l) / 2, yPhoto + (hPhoto - h) / 2, l, h);
        } else {
            f.setStrokingColor(0.6f);
            f.addRect(xPhoto, yPhoto, lPhoto, hPhoto);
            f.stroke();
            f.setStrokingColor(0f);
            String p = "Photo";
            texte(f, normal, 6, xPhoto + (lPhoto - largeur(normal, 6, p)) / 2, yPhoto + hPhoto / 2, p);
        }

        // Identité et informations à droite de la photo
        float xTexte = xPhoto + lPhoto + 2.5f * MM;
        float lTexte = x + L_CARTE - marge - xTexte;
        float yTexte = yTitre - 11;
        for (String l : couper(gras, 8.5f, propre(c.nom()), lTexte, 1)) {
            texte(f, gras, 8.5f, xTexte, yTexte, l);
            yTexte -= 10;
        }
        for (String l : couper(normal, 8, propre(c.prenoms()), lTexte, 2)) {
            texte(f, normal, 8, xTexte, yTexte, l);
            yTexte -= 9.5f;
        }
        yTexte -= 2;
        for (String[] ligne : c.lignes()) {
            if (ligne.length < 2 || ligne[1] == null || ligne[1].isBlank()) {
                continue;
            }
            String libelle = propre(ligne[0]) + " : ";
            float lLib = largeur(normal, 6.5f, libelle);
            texte(f, normal, 6.5f, xTexte, yTexte, libelle);
            for (String v : couper(gras, 6.5f, propre(ligne[1]), lTexte - lLib, 1)) {
                texte(f, gras, 6.5f, xTexte + lLib, yTexte, v);
            }
            yTexte -= 8.5f;
        }

        // Pied : année scolaire, validité
        if (c.pied() != null) {
            String p = propre(c.pied());
            texte(f, normal, 6, x + marge, y + marge, p);
        }
    }

    // ------------------------------------------------------------------

    private static PDImageXObject image(PDDocument doc, byte[] contenu, String nom) {
        if (contenu == null || contenu.length == 0) {
            return null;
        }
        try {
            return PDImageXObject.createFromByteArray(doc, contenu, nom);
        } catch (IOException | RuntimeException e) {
            return null; // une image illisible ne doit pas empêcher l'impression
        }
    }

    private void texte(PDPageContentStream f, PDFont police, float taille, float x, float y, String valeur) throws IOException {
        if (valeur == null || valeur.isEmpty()) {
            return;
        }
        f.beginText();
        f.setFont(police, taille);
        f.newLineAtOffset(x, y);
        f.showText(valeur);
        f.endText();
    }

    private static float largeur(PDFont police, float taille, String valeur) throws IOException {
        return police.getStringWidth(valeur) / 1000 * taille;
    }

    /** Coupe un texte en lignes qui tiennent dans la largeur ; la dernière ligne est tronquée au besoin. */
    private static List<String> couper(PDFont police, float taille, String valeur, float largeur, int lignesMax)
            throws IOException {
        List<String> lignes = new ArrayList<>();
        if (valeur == null || valeur.isBlank()) {
            return lignes;
        }
        StringBuilder courante = new StringBuilder();
        for (String mot : valeur.trim().split("\\s+")) {
            String essai = courante.isEmpty() ? mot : courante + " " + mot;
            if (largeur(police, taille, essai) <= largeur || courante.isEmpty()) {
                courante.setLength(0);
                courante.append(essai);
            } else {
                lignes.add(courante.toString());
                courante.setLength(0);
                courante.append(mot);
            }
        }
        lignes.add(courante.toString());
        if (lignes.size() > lignesMax) {
            List<String> gardees = new ArrayList<>(lignes.subList(0, lignesMax));
            gardees.set(lignesMax - 1, gardees.get(lignesMax - 1) + "…");
            lignes = gardees;
        }
        List<String> ajustees = new ArrayList<>();
        for (String l : lignes) {
            String r = l;
            while (r.length() > 1 && largeur(police, taille, r) > largeur) {
                r = r.substring(0, r.length() - 2) + "…";
            }
            ajustees.add(r);
        }
        return ajustees;
    }

    /** Caractères imprimables avec les polices standard (alphabet latin occidental). */
    static String propre(String valeur) {
        if (valeur == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(valeur.length());
        for (char ch : valeur.toCharArray()) {
            if (ch == '’' || ch == '‘') {
                sb.append('\'');
            } else if (ch == '…' || ch == '–' || ch == '—' || ch == 'œ' || ch == 'Œ' || (ch >= 0x20 && ch < 0x7F) || (ch >= 0xA0 && ch <= 0xFF)) {
                sb.append(ch);
            } else if (Character.isWhitespace(ch)) {
                sb.append(' ');
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}
