package bf.edutech.plateforme.bulletins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.pedagogie.CodeModele;

/**
 * Mise en page PDF des bulletins (A4, noir et blanc pour la photocopie).
 * <ul>
 * <li>Bulletin de notes : une rubrique par groupe de matières (matières générales,
 * matières techniques…) avec sa moyenne, puis la moyenne générale et le rang.</li>
 * <li>Relevé de compétences (formation professionnelle) : modules, compétences acquises, statut.</li>
 * </ul>
 * Polices standard (Helvetica) : les caractères hors de l'alphabet latin courant
 * sont remplacés par « ? ». Une police intégrée pourra être ajoutée pour les
 * langues nationales.
 */
@Component
class RenduPdf {

    private static final float MARGE = 36;
    private static final float LARGEUR = PDRectangle.A4.getWidth();
    private static final float HAUTEUR = PDRectangle.A4.getHeight();
    private static final float UTILE = LARGEUR - 2 * MARGE;
    private static final float LIGNE = 14;
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Un ou plusieurs bulletins dans un même document (un bulletin commence toujours une page). */
    byte[] rendre(List<DonneesBulletin> bulletins) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Plume plume = new Plume(doc);
            for (DonneesBulletin b : bulletins) {
                plume.nouvellePage();
                dessiner(plume, b);
            }
            plume.fermer();
            doc.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Production du bulletin impossible", e);
        }
    }

    /** Assemble des bulletins déjà produits (impression de la classe). */
    byte[] fusionner(List<byte[]> documents) {
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument cible = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            PDFMergerUtility fusion = new PDFMergerUtility();
            for (byte[] contenu : documents) {
                PDDocument source = Loader.loadPDF(contenu);
                sources.add(source);
                fusion.appendDocument(cible, source);
            }
            cible.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Assemblage des bulletins impossible", e);
        } finally {
            for (PDDocument source : sources) {
                try {
                    source.close();
                } catch (IOException e) {
                    // sans conséquence : document en mémoire
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private void dessiner(Plume p, DonneesBulletin b) throws IOException {
        entete(p, b);
        boolean competences = b.modele() == CodeModele.COMPETENCES;
        String titre = (competences ? "RELEVÉ DE COMPÉTENCES" : "BULLETIN DE NOTES") + " — " + b.periode();
        p.texteCentre(p.y, p.gras, 13, titre);
        p.y -= 22;
        identite(p, b);
        if (b.lignes().stream().anyMatch(l -> l.nature() != NatureLigne.MODULE)) {
            tableauNotes(p, b);
        }
        if (b.lignes().stream().anyMatch(l -> l.nature() == NatureLigne.MODULE)) {
            tableauModules(p, b);
        }
        resultats(p, b);
        vieScolaire(p, b);
        signatures(p);
        pied(p, b);
    }

    private void entete(Plume p, DonneesBulletin b) throws IOException {
        float haut = p.y;
        var e = b.parametres();
        float y = haut;
        p.texte(MARGE, y, p.gras, 10, e.entetePays());
        y -= 11;
        if (e.enteteDevise() != null) {
            p.texte(MARGE, y, p.italique, 8, e.enteteDevise());
            y -= 11;
        }
        if (e.enteteMinistere() != null) {
            y = p.paragraphe(MARGE, y, 250, p.normal, 8, e.enteteMinistere());
        }
        if (e.enteteDirection() != null) {
            y = p.paragraphe(MARGE, y, 250, p.normal, 8, e.enteteDirection());
        }
        float yd = haut;
        float xd = MARGE + UTILE / 2 + 20;
        yd = p.paragraphe(xd, yd, UTILE / 2 - 20, p.gras, 10, b.etablissement());
        if (e.adresse() != null) {
            yd = p.paragraphe(xd, yd, UTILE / 2 - 20, p.normal, 8, e.adresse());
        }
        p.texte(xd, yd, p.normal, 8, "Année scolaire " + b.annee());
        yd -= 11;
        p.y = Math.min(y, yd) - 4;
        p.trait(MARGE, p.y, LARGEUR - MARGE, p.y);
        p.y -= 18;
    }

    private void identite(Plume p, DonneesBulletin b) throws IOException {
        float haut = p.y + 10;
        float x2 = MARGE + UTILE / 2 + 10;
        p.texte(MARGE + 6, p.y, p.gras, 10, b.nom() + " " + b.prenoms());
        p.texte(x2, p.y, p.normal, 9, "Matricule : " + b.matricule());
        p.y -= 13;
        p.texte(MARGE + 6, p.y, p.normal, 9,
                "Né(e) le : " + (b.dateNaissance() != null ? b.dateNaissance().format(JOUR) : "—")
                        + "      Redoublant(e) : " + (b.redoublant() ? "Oui" : "Non"));
        p.texte(x2, p.y, p.normal, 9, "Classe : " + b.classe() + "      Effectif : " + b.effectif());
        p.y -= 8;
        p.cadre(MARGE, p.y, UTILE, haut - p.y);
        p.y -= 16;
    }

    private void tableauNotes(Plume p, DonneesBulletin b) throws IOException {
        float[] l = { 142, 32, 44, 46, 34, 135, 90 };
        String[] entetes = { "Matières", "Coef.", "Moy./20", "Points", "Rang", "Appréciations", "Professeur" };
        p.ligneTableau(l, entetes, p.gras, 8, true, true);
        String groupeCourant = null;
        boolean notesAbsentes = false;
        BigDecimal totalCoef = BigDecimal.ZERO;
        BigDecimal totalPoints = BigDecimal.ZERO;
        for (LigneBulletin ligne : b.lignes()) {
            if (ligne.nature() == NatureLigne.MODULE) {
                continue;
            }
            if (ligne.nature() == NatureLigne.MATIERE && ligne.groupe() != null
                    && !ligne.groupe().equals(groupeCourant)) {
                groupeCourant = ligne.groupe();
                p.assurer(LIGNE);
                p.fond(MARGE, p.y - LIGNE, UTILE, LIGNE, 0.92f);
                p.cadre(MARGE, p.y - LIGNE, UTILE, LIGNE);
                p.texte(MARGE + 3, p.y - LIGNE + 4, p.gras, 8, groupeCourant.toUpperCase(Locale.FRENCH));
                p.y -= LIGNE;
            }
            if (ligne.nature() == NatureLigne.GROUPE) {
                String[] v = { "Moyenne " + ligne.libelle(), nombre(ligne.coefficient(), 1), nombre(ligne.moyenne(), 2),
                    "", "", "", "" };
                p.ligneTableau(l, v, p.gras, 8, false, false);
                continue;
            }
            notesAbsentes |= ligne.sansNote();
            totalCoef = totalCoef.add(ligne.coefficient() != null ? ligne.coefficient() : BigDecimal.ZERO);
            totalPoints = totalPoints.add(ligne.points() != null ? ligne.points() : BigDecimal.ZERO);
            String[] v = { ligne.libelle(), nombre(ligne.coefficient(), 1),
                nombre(ligne.moyenne(), 2) + (ligne.sansNote() ? " *" : ""), nombre(ligne.points(), 2),
                ligne.rang() != null ? String.valueOf(ligne.rang()) : "", texte(ligne.appreciation()),
                texte(ligne.enseignant()) };
            p.ligneTableau(l, v, p.normal, 8, false, false);
        }
        String[] totaux = { "TOTAUX", nombre(totalCoef, 1), "", nombre(totalPoints, 2), "", "", "" };
        p.ligneTableau(l, totaux, p.gras, 8, true, false);
        if (notesAbsentes) {
            p.texte(MARGE, p.y - 9, p.italique, 7, "* Aucune note sur la période : moyenne comptée zéro.");
            p.y -= 12;
        }
        p.y -= 8;
    }

    private void tableauModules(Plume p, DonneesBulletin b) throws IOException {
        float[] l = { 140, 62, 46, 48, 56, 101, 70 };
        String[] entetes = { "Modules", "Compétences", "Acquises", "Taux", "Résultat", "Appréciations", "Formateur" };
        p.ligneTableau(l, entetes, p.gras, 8, true, true);
        for (LigneBulletin ligne : b.lignes()) {
            if (ligne.nature() != NatureLigne.MODULE) {
                continue;
            }
            String[] v = { ligne.libelle(), ligne.competences() != null ? String.valueOf(ligne.competences()) : "",
                ligne.acquises() != null ? String.valueOf(ligne.acquises()) : "",
                ligne.taux() != null ? nombre(ligne.taux(), 2) + " %" : "—", statut(ligne.statutModule()),
                texte(ligne.appreciation()), texte(ligne.enseignant()) };
            p.ligneTableau(l, v, p.normal, 8, false, false);
        }
        p.y -= 8;
    }

    private void resultats(Plume p, DonneesBulletin b) throws IOException {
        p.assurer(50);
        float haut = p.y + 10;
        if (b.moyenne() != null) {
            p.texte(MARGE + 6, p.y, p.gras, 11, "Moyenne générale : " + nombre(b.moyenne(), 2) + " / 20");
            p.texte(MARGE + UTILE / 2 + 10, p.y, p.gras, 11,
                    "Rang : " + (b.rang() != null ? rang(b.rang()) + " / " + b.effectif() : "—"));
            p.y -= 14;
            p.texte(MARGE + 6, p.y, p.normal, 8, "Moyenne de la classe : " + nombre(b.moyenneClasse(), 2)
                    + "     Plus forte : " + nombre(b.plusForte(), 2) + "     Plus faible : " + nombre(b.plusFaible(), 2));
            p.y -= 12;
        }
        if (b.tauxMaitrise() != null) {
            p.texte(MARGE + 6, p.y, p.gras, 10, "Taux de maîtrise des compétences : " + nombre(b.tauxMaitrise(), 2) + " %");
            p.y -= 13;
        }
        p.texte(MARGE + 6, p.y, p.normal, 9, "Résultat de la période : " + (b.admis() ? "Admis(e)" : "Non admis(e)"));
        p.y -= 6;
        p.cadre(MARGE, p.y, UTILE, haut - p.y);
        p.y -= 16;
    }

    private void vieScolaire(Plume p, DonneesBulletin b) throws IOException {
        p.assurer(70);
        p.texte(MARGE, p.y, p.normal, 9, "Absences : " + nombre(b.heuresAbsence(), 1) + " h, dont "
                + nombre(b.heuresNonJustifiees(), 1) + " h non justifiées      Retards : " + b.retards());
        p.y -= 14;
        p.texte(MARGE, p.y, p.gras, 9, "Décision du conseil de classe : " + b.distinction().libelle());
        p.y -= 12;
        p.texte(MARGE, p.y, p.normal, 9, "Appréciation du conseil de classe :");
        p.y -= 11;
        p.y = p.paragraphe(MARGE + 10, p.y, UTILE - 10, p.italique, 9,
                b.appreciationGenerale() != null ? b.appreciationGenerale() : "") - 6;
    }

    private void signatures(Plume p) throws IOException {
        p.assurer(70);
        float colonne = UTILE / 3;
        String[] titres = { "Le Censeur", "Le Chef d'établissement", "Signature du parent" };
        for (int i = 0; i < titres.length; i++) {
            p.texteCentreDans(MARGE + i * colonne, colonne, p.y, p.gras, 9, titres[i]);
        }
        p.y -= 60;
    }

    private void pied(Plume p, DonneesBulletin b) throws IOException {
        String code = b.codeVerification().substring(0, 5) + "-" + b.codeVerification().substring(5);
        p.texte(MARGE, MARGE - 12, p.normal, 7, "Code de vérification : " + code + " — document produit le "
                + b.genereLe().atZone(ZoneOffset.UTC).toLocalDate().format(JOUR)
                + ". Toute rature ou surcharge annule ce bulletin.");
    }

    // ------------------------------------------------------------------

    static String nombre(BigDecimal valeur, int decimales) {
        return valeur == null ? "" : valeur.setScale(decimales, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    static String rang(int rang) {
        return rang == 1 ? "1er" : rang + "e";
    }

    private static String texte(String valeur) {
        return valeur != null ? valeur : "";
    }

    private static String statut(String statut) {
        if (statut == null) {
            return "";
        }
        return switch (statut) {
            case "ACQUIS" -> "Acquis";
            case "NON_ACQUIS" -> "Non acquis";
            default -> "Non évalué";
        };
    }

    /** Écriture sur les pages d'un document, de haut en bas. */
    private static final class Plume {

        private final PDDocument doc;
        final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        final PDFont gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        final PDFont italique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        private PDPageContentStream flux;
        float y;

        Plume(PDDocument doc) {
            this.doc = doc;
        }

        void nouvellePage() throws IOException {
            fermer();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            flux = new PDPageContentStream(doc, page);
            flux.setLineWidth(0.6f);
            y = HAUTEUR - MARGE;
        }

        void fermer() throws IOException {
            if (flux != null) {
                flux.close();
                flux = null;
            }
        }

        /** Passe à la page suivante s'il ne reste pas {@code hauteur} points avant le pied de page. */
        void assurer(float hauteur) throws IOException {
            if (y - hauteur < MARGE + 20) {
                nouvellePage();
            }
        }

        void texte(float x, float yy, PDFont police, float taille, String valeur) throws IOException {
            String propre = propre(police, valeur);
            if (propre.isEmpty()) {
                return;
            }
            flux.beginText();
            flux.setFont(police, taille);
            flux.newLineAtOffset(x, yy);
            flux.showText(propre);
            flux.endText();
        }

        void texteCentre(float yy, PDFont police, float taille, String valeur) throws IOException {
            texteCentreDans(MARGE, UTILE, yy, police, taille, valeur);
        }

        void texteCentreDans(float x, float largeur, float yy, PDFont police, float taille, String valeur)
                throws IOException {
            String propre = propre(police, valeur);
            float l = largeur(police, taille, propre);
            texte(x + (largeur - l) / 2, yy, police, taille, propre);
        }

        /** Texte sur plusieurs lignes dans une largeur donnée ; renvoie la position sous le texte. */
        float paragraphe(float x, float yy, float largeur, PDFont police, float taille, String valeur)
                throws IOException {
            float courant = yy;
            for (String ligne : couper(police, taille, propre(police, valeur), largeur)) {
                texte(x, courant, police, taille, ligne);
                courant -= taille + 3;
            }
            return courant;
        }

        void ligneTableau(float[] largeurs, String[] valeurs, PDFont police, float taille, boolean fond,
                boolean entete) throws IOException {
            assurer(LIGNE);
            float x = MARGE;
            float bas = y - LIGNE;
            if (fond) {
                fond(MARGE, bas, somme(largeurs), LIGNE, entete ? 0.85f : 0.95f);
            }
            for (int i = 0; i < largeurs.length; i++) {
                cadre(x, bas, largeurs[i], LIGNE);
                String valeur = ajuster(police, taille, propre(police, valeurs[i]), largeurs[i] - 6);
                boolean aGauche = i == 0 || i >= largeurs.length - 2 || entete;
                float lx = aGauche ? x + 3 : x + largeurs[i] - 3 - largeur(police, taille, valeur);
                texte(lx, bas + 4, police, taille, valeur);
                x += largeurs[i];
            }
            y = bas;
        }

        void trait(float x1, float y1, float x2, float y2) throws IOException {
            flux.moveTo(x1, y1);
            flux.lineTo(x2, y2);
            flux.stroke();
        }

        void cadre(float x, float yy, float largeur, float hauteur) throws IOException {
            flux.addRect(x, yy, largeur, hauteur);
            flux.stroke();
        }

        void fond(float x, float yy, float largeur, float hauteur, float gris) throws IOException {
            flux.setNonStrokingColor(gris);
            flux.addRect(x, yy, largeur, hauteur);
            flux.fill();
            flux.setNonStrokingColor(0f);
        }

        private static float somme(float[] valeurs) {
            float s = 0;
            for (float v : valeurs) {
                s += v;
            }
            return s;
        }

        private static float largeur(PDFont police, float taille, String valeur) throws IOException {
            return police.getStringWidth(valeur) / 1000 * taille;
        }

        /** Tronque avec « … » pour tenir dans la largeur. */
        private static String ajuster(PDFont police, float taille, String valeur, float max) throws IOException {
            if (largeur(police, taille, valeur) <= max) {
                return valeur;
            }
            String court = valeur;
            while (!court.isEmpty() && largeur(police, taille, court + "…") > max) {
                court = court.substring(0, court.length() - 1);
            }
            return court + "…";
        }

        private static List<String> couper(PDFont police, float taille, String valeur, float max) throws IOException {
            List<String> lignes = new ArrayList<>();
            StringBuilder courante = new StringBuilder();
            for (String mot : valeur.split(" ")) {
                String essai = courante.isEmpty() ? mot : courante + " " + mot;
                if (largeur(police, taille, essai) <= max || courante.isEmpty()) {
                    courante.setLength(0);
                    courante.append(essai);
                } else {
                    lignes.add(courante.toString());
                    courante.setLength(0);
                    courante.append(mot);
                }
            }
            if (!courante.isEmpty()) {
                lignes.add(courante.toString());
            }
            return lignes;
        }

        /** Remplace les caractères que la police standard ne sait pas écrire. */
        private static String propre(PDFont police, String valeur) {
            if (valeur == null) {
                return "";
            }
            String simple = valeur.replace('\u00A0', ' ').replace('\u202F', ' ').replaceAll("\\s+", " ").trim();
            StringBuilder resultat = new StringBuilder();
            simple.codePoints().forEach(point -> {
                String caractere = new String(Character.toChars(point));
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
}
