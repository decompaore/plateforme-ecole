package bf.edutech.plateforme.socle.export;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import bf.edutech.plateforme.socle.export.Tableau.Colonne;
import bf.edutech.plateforme.socle.export.Tableau.Image;
import bf.edutech.plateforme.socle.export.Tableau.Montant;
import bf.edutech.plateforme.socle.export.Tableau.Type;

/**
 * Exports Excel (une feuille par tableau) et PDF (A4, paysage par défaut, police Helvetica standard,
 * en-tête de l'établissement, saut de page avec répétition des en-têtes, total, notes, signatures,
 * pagination).
 */
public final class ExportTableaux {

    public enum Format {
        XLSX,
        PDF;

        public static Format lire(String valeur) {
            if (valeur == null || valeur.isBlank() || valeur.equalsIgnoreCase("xlsx") || valeur.equalsIgnoreCase("excel")) {
                return XLSX;
            }
            if (valeur.equalsIgnoreCase("pdf")) {
                return PDF;
            }
            throw new IllegalArgumentException("Format attendu : xlsx ou pdf");
        }
    }

    public static final MediaType EXCEL =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private ExportTableaux() {
    }

    /** Réponse HTTP : fichier « nom.xlsx » ou « nom.pdf ». */
    public static ResponseEntity<byte[]> reponse(String etablissement, String nom, Format format, Tableau... tableaux) {
        byte[] contenu = format == Format.PDF ? pdf(etablissement, tableaux) : excel(etablissement, tableaux);
        String fichier = nom + (format == Format.PDF ? ".pdf" : ".xlsx");
        return ResponseEntity.ok()
                .contentType(format == Format.PDF ? MediaType.APPLICATION_PDF : EXCEL)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fichier, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(contenu);
    }

    // ================================================================== Excel

    public static byte[] excel(String etablissement, Tableau... tableaux) {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Font grasPolice = classeur.createFont();
            grasPolice.setBold(true);
            Font titrePolice = classeur.createFont();
            titrePolice.setBold(true);
            titrePolice.setFontHeightInPoints((short) 13);
            CellStyle titre = classeur.createCellStyle();
            titre.setFont(titrePolice);
            CellStyle gras = classeur.createCellStyle();
            gras.setFont(grasPolice);
            CellStyle entete = classeur.createCellStyle();
            entete.setFont(grasPolice);
            entete.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            entete.setBorderBottom(BorderStyle.THIN);
            entete.setWrapText(true);
            CellStyle montant = classeur.createCellStyle();
            montant.setDataFormat(classeur.createDataFormat().getFormat("#,##0"));
            CellStyle montantGras = classeur.createCellStyle();
            montantGras.setDataFormat(classeur.createDataFormat().getFormat("#,##0"));
            montantGras.setFont(grasPolice);
            CellStyle nombre = classeur.createCellStyle();
            nombre.setDataFormat(classeur.createDataFormat().getFormat("General"));
            CellStyle nombreGras = classeur.createCellStyle();
            nombreGras.setDataFormat(classeur.createDataFormat().getFormat("General"));
            nombreGras.setFont(grasPolice);
            CellStyle retour = classeur.createCellStyle();
            retour.setWrapText(true);

            List<String> noms = new ArrayList<>();
            for (Tableau t : tableaux) {
                String nomFeuille = nomFeuille(t.nomFeuille(), noms);
                noms.add(nomFeuille);
                Sheet feuille = classeur.createSheet(nomFeuille);
                int n = 0;
                ecrire(feuille.createRow(n++), 0, etablissement, gras);
                ecrire(feuille.createRow(n++), 0, t.titre(), titre);
                if (t.sousTitre() != null) {
                    ecrire(feuille.createRow(n++), 0, t.sousTitre(), null);
                }
                n++;
                Row r = feuille.createRow(n++);
                for (int i = 0; i < t.colonnes().size(); i++) {
                    ecrire(r, i, t.colonnes().get(i).titre(), entete);
                }
                int premiere = n;
                for (int k = 0; k < t.lignes().size(); k++) {
                    List<Object> valeurs = t.lignes().get(k);
                    Row ligne = feuille.createRow(n++);
                    if (t.estIntertitre(k)) {
                        ecrire(ligne, 0, String.valueOf(valeurs.get(0)), gras);
                        if (t.colonnes().size() > 1) {
                            feuille.addMergedRegion(new CellRangeAddress(n - 1, n - 1, 0, t.colonnes().size() - 1));
                        }
                        continue;
                    }
                    cellules(ligne, t, valeurs, montant, nombre, retour);
                }
                if (t.total() != null) {
                    cellules(feuille.createRow(n++), t, t.total(), montantGras, nombreGras, gras);
                    Row derniere = feuille.getRow(n - 1);
                    if (derniere.getCell(0) != null) {
                        derniere.getCell(0).setCellStyle(gras);
                    }
                }
                n++;
                for (String note : t.notes()) {
                    ecrire(feuille.createRow(n++), 0, note, null);
                }
                for (int i = 0; i < t.colonnes().size(); i++) {
                    Colonne c = t.colonnes().get(i);
                    int largeur = c.type() == Type.IMAGE ? 10 : Math.round(Math.max(8, Math.min(60, c.poids() * 6)));
                    feuille.setColumnWidth(i, largeur * 256);
                }
                if (n > premiere) {
                    feuille.createFreezePane(0, premiere);
                }
            }
            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cellules(Row ligne, Tableau t, List<Object> valeurs, CellStyle montant, CellStyle nombre,
            CellStyle texte) {
        for (int i = 0; i < valeurs.size() && i < t.colonnes().size(); i++) {
            Object v = valeurs.get(i);
            Cell c = ligne.createCell(i);
            if (v instanceof Montant m) {
                c.setCellValue(m.valeur());
                c.setCellStyle(montant);
            } else if (v instanceof Number num) {
                c.setCellValue(num.doubleValue());
                c.setCellStyle(nombre);
            } else if (v instanceof Image) {
                c.setCellValue("photo");
            } else if (v instanceof LocalDate d) {
                c.setCellValue(d.format(JOUR));
            } else if (v != null) {
                c.setCellValue(v.toString());
                if (texte != null) {
                    c.setCellStyle(texte);
                }
            }
        }
    }

    private static void ecrire(Row r, int colonne, String valeur, CellStyle style) {
        Cell c = r.createCell(colonne);
        c.setCellValue(valeur == null ? "" : valeur);
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    private static String nomFeuille(String nom, List<String> pris) {
        String base = nom.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        base = base.length() > 28 ? base.substring(0, 28) : base;
        String resultat = base.isEmpty() ? "Feuille" : base;
        int i = 2;
        while (pris.contains(resultat)) {
            resultat = base + " " + i++;
        }
        return resultat;
    }

    // ================================================================== PDF

    private static final float MARGE = 32;
    private static final float TAILLE = 8.5f;
    private static final float INTERLIGNE = 10.5f;
    private static final float HAUTEUR_IMAGE = 34;
    private static final Color GRIS = new Color(232, 236, 234);

    public static byte[] pdf(String etablissement, Tableau... tableaux) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Rendu rendu = new Rendu(doc, etablissement);
            for (Tableau t : tableaux) {
                rendu.tableau(t);
            }
            rendu.terminer();
            doc.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Rendu {
        private final PDDocument doc;
        private final String etablissement;
        private final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final String date = LocalDate.now(FUSEAU).format(JOUR);
        private PDPageContentStream flux;
        private PDRectangle format;
        private float y;
        private float[] largeurs;
        private Tableau courant;

        Rendu(PDDocument doc, String etablissement) {
            this.doc = doc;
            this.etablissement = etablissement;
        }

        void tableau(Tableau t) throws IOException {
            courant = t;
            format = t.paysage() ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            float utile = format.getWidth() - 2 * MARGE;
            float somme = 0;
            for (Colonne c : t.colonnes()) {
                somme += c.poids();
            }
            largeurs = new float[t.colonnes().size()];
            for (int i = 0; i < largeurs.length; i++) {
                largeurs[i] = utile * t.colonnes().get(i).poids() / somme;
            }
            nouvellePage(true);
            for (int k = 0; k < t.lignes().size(); k++) {
                if (t.estIntertitre(k)) {
                    intertitre(String.valueOf(t.lignes().get(k).get(0)));
                } else {
                    ligne(t.lignes().get(k), false);
                }
            }
            if (t.total() != null) {
                ligne(t.total(), true);
            }
            y -= 8;
            for (String note : t.notes()) {
                for (String l : couper(normal, TAILLE, note, utile)) {
                    place(INTERLIGNE);
                    texte(normal, TAILLE, l, MARGE, y);
                    y -= INTERLIGNE;
                }
            }
            if (!t.signatures().isEmpty()) {
                place(70);
                y -= 14;
                float largeur = utile / t.signatures().size();
                for (int i = 0; i < t.signatures().size(); i++) {
                    texte(gras, TAILLE + 0.5f, t.signatures().get(i), MARGE + i * largeur, y);
                }
                y -= 60;
            }
        }

        void terminer() throws IOException {
            if (flux != null) {
                flux.close();
            }
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                try (PDPageContentStream f = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                    String pied = "Page " + (i + 1) + " / " + total + " — édité le " + date;
                    float l = largeurTexte(normal, 7.5f, pied);
                    f.beginText();
                    f.setFont(normal, 7.5f);
                    f.newLineAtOffset(page.getMediaBox().getWidth() - MARGE - l, 18);
                    f.showText(propre(normal, pied));
                    f.endText();
                }
            }
        }

        private void nouvellePage(boolean entetePrincipal) throws IOException {
            if (flux != null) {
                flux.close();
            }
            PDPage page = new PDPage(format);
            doc.addPage(page);
            flux = new PDPageContentStream(doc, page);
            flux.setLineWidth(0.5f);
            y = format.getHeight() - MARGE;
            texte(gras, 10, etablissement, MARGE, y);
            y -= 16;
            if (entetePrincipal) {
                for (String l : couper(gras, 13, courant.titre(), format.getWidth() - 2 * MARGE)) {
                    texte(gras, 13, l, MARGE, y);
                    y -= 16;
                }
                if (courant.sousTitre() != null) {
                    for (String l : couper(normal, 9, courant.sousTitre(), format.getWidth() - 2 * MARGE)) {
                        texte(normal, 9, l, MARGE, y);
                        y -= 12;
                    }
                }
                y -= 6;
            } else {
                texte(normal, 8, courant.titre() + " (suite)", MARGE, y);
                y -= 14;
            }
            entetes();
        }

        private void entetes() throws IOException {
            List<List<String>> cellules = new ArrayList<>();
            int max = 1;
            for (int i = 0; i < largeurs.length; i++) {
                List<String> l = couper(gras, TAILLE, courant.colonnes().get(i).titre(), largeurs[i] - 6);
                cellules.add(l);
                max = Math.max(max, l.size());
            }
            float h = max * INTERLIGNE + 6;
            flux.setNonStrokingColor(GRIS);
            flux.addRect(MARGE, y - h, somme(), h);
            flux.fill();
            flux.setNonStrokingColor(Color.BLACK);
            float x = MARGE;
            for (int i = 0; i < largeurs.length; i++) {
                float ty = y - INTERLIGNE + 1;
                for (String s : cellules.get(i)) {
                    ecrireCellule(gras, s, x, ty, i);
                    ty -= INTERLIGNE;
                }
                x += largeurs[i];
            }
            y -= h;
            trait(y);
        }

        private void intertitre(String texte) throws IOException {
            place(INTERLIGNE + 8);
            y -= INTERLIGNE + 2;
            texte(gras, TAILLE + 0.5f, texte, MARGE + 3, y + 2);
            y -= 4;
            trait(y);
        }

        private void ligne(List<Object> valeurs, boolean total) throws IOException {
            PDFont police = total ? gras : normal;
            List<List<String>> cellules = new ArrayList<>();
            int max = 1;
            boolean image = false;
            for (int i = 0; i < largeurs.length; i++) {
                Object v = i < valeurs.size() ? valeurs.get(i) : null;
                if (v instanceof Image) {
                    image = true;
                    cellules.add(List.of());
                    continue;
                }
                List<String> l = couper(police, TAILLE, format(v), largeurs[i] - 6);
                cellules.add(l);
                max = Math.max(max, l.size());
            }
            float h = Math.max(max * INTERLIGNE + 5, image ? HAUTEUR_IMAGE + 4 : 0);
            if (y - h < MARGE + 20) {
                nouvellePage(false);
            }
            float x = MARGE;
            for (int i = 0; i < largeurs.length; i++) {
                Object v = i < valeurs.size() ? valeurs.get(i) : null;
                if (v instanceof Image img) {
                    dessinerImage(img, x + 2, y - h + 2, largeurs[i] - 4, h - 4);
                } else {
                    float ty = y - INTERLIGNE + 1;
                    for (String s : cellules.get(i)) {
                        ecrireCellule(police, s, x, ty, i);
                        ty -= INTERLIGNE;
                    }
                }
                x += largeurs[i];
            }
            y -= h;
            if (total) {
                flux.setLineWidth(1f);
                trait(y + h);
                flux.setLineWidth(0.5f);
            }
            trait(y);
        }

        private void dessinerImage(Image img, float x, float yBas, float l, float h) {
            try {
                PDImageXObject image = PDImageXObject.createFromByteArray(doc, img.contenu(), "photo");
                float echelle = Math.min(l / image.getWidth(), h / image.getHeight());
                flux.drawImage(image, x, yBas, image.getWidth() * echelle, image.getHeight() * echelle);
            } catch (IOException | IllegalArgumentException e) {
                // format non pris en charge (WebP…) : cellule laissée vide
            }
        }

        private void ecrireCellule(PDFont police, String s, float x, float ty, int colonne) throws IOException {
            Type type = courant.colonnes().get(colonne).type();
            if (type == Type.NOMBRE || type == Type.MONTANT) {
                float l = largeurTexte(police, TAILLE, s);
                texte(police, TAILLE, s, x + largeurs[colonne] - 3 - l, ty);
            } else {
                texte(police, TAILLE, s, x + 3, ty);
            }
        }

        private void place(float hauteur) throws IOException {
            if (y - hauteur < MARGE + 20) {
                nouvellePage(false);
            }
        }

        private float somme() {
            float s = 0;
            for (float l : largeurs) {
                s += l;
            }
            return s;
        }

        private void trait(float hauteur) throws IOException {
            flux.setStrokingColor(new Color(180, 186, 184));
            flux.moveTo(MARGE, hauteur);
            flux.lineTo(MARGE + somme(), hauteur);
            flux.stroke();
            flux.setStrokingColor(Color.BLACK);
        }

        private void texte(PDFont police, float taille, String s, float x, float yy) throws IOException {
            flux.beginText();
            flux.setFont(police, taille);
            flux.newLineAtOffset(x, yy);
            flux.showText(propre(police, s));
            flux.endText();
        }
    }

    /** Texte d'une cellule : nombres à la française (1 250,5), montants avec FCFA. */
    static String format(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Montant m) {
            return groupes(m.valeur()) + " F";
        }
        if (v instanceof BigDecimal b) {
            b = b.stripTrailingZeros();
            return b.scale() <= 0 ? groupes(b.longValue()) : b.toPlainString().replace('.', ',');
        }
        if (v instanceof Double || v instanceof Float) {
            return format(BigDecimal.valueOf(((Number) v).doubleValue()));
        }
        if (v instanceof Number n) {
            return groupes(n.longValue());
        }
        if (v instanceof LocalDate d) {
            return d.format(JOUR);
        }
        return v.toString();
    }

    private static String groupes(long n) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(n).replace(' ', ' ').replace(' ', ' ');
    }

    private static float largeurTexte(PDFont police, float taille, String s) throws IOException {
        return police.getStringWidth(propre(police, s)) / 1000 * taille;
    }

    /** Découpe un texte en lignes qui tiennent dans la largeur (mots trop longs coupés). */
    static List<String> couper(PDFont police, float taille, String texte, float largeur) throws IOException {
        List<String> lignes = new ArrayList<>();
        for (String paragraphe : propre(police, texte).split("\n")) {
            StringBuilder courante = new StringBuilder();
            for (String mot : paragraphe.split(" ")) {
                String essai = courante.isEmpty() ? mot : courante + " " + mot;
                if (largeurTexte(police, taille, essai) <= largeur) {
                    courante.setLength(0);
                    courante.append(essai);
                    continue;
                }
                if (!courante.isEmpty()) {
                    lignes.add(courante.toString());
                    courante.setLength(0);
                }
                String reste = mot;
                while (largeurTexte(police, taille, reste) > largeur && reste.length() > 1) {
                    int coupe = reste.length() - 1;
                    while (coupe > 1 && largeurTexte(police, taille, reste.substring(0, coupe)) > largeur) {
                        coupe--;
                    }
                    lignes.add(reste.substring(0, coupe));
                    reste = reste.substring(coupe);
                }
                courante.append(reste);
            }
            lignes.add(courante.toString());
        }
        return lignes;
    }

    /** Remplace les caractères que la police standard ne sait pas écrire. */
    static String propre(PDFont police, String valeur) {
        if (valeur == null) {
            return "";
        }
        String simple = valeur.replace(' ', ' ').replace(' ', ' ').replace('\t', ' ').replace("\r", "");
        StringBuilder resultat = new StringBuilder();
        simple.codePoints().forEach(point -> {
            String caractere = new String(Character.toChars(point));
            if (caractere.equals("\n")) {
                resultat.append(caractere);
                return;
            }
            try {
                police.encode(caractere);
                resultat.append(caractere);
            } catch (IOException | IllegalArgumentException e) {
                resultat.append('?');
            }
        });
        return resultat.toString();
    }
}
