package bf.edutech.plateforme.scolarite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/** Reçu de paiement au format A5 (PDFBox, police Helvetica standard). */
@Component
class RenduRecu {

    record DonneesRecu(String etablissement, String annee, String numero, String codeVerification, Instant emisLe,
            String eleve, String matricule, String classe, long montant, String payeur, String moyen,
            String reference, String deposant, LocalDate datePaiement, long resteFamille, LocalDate situationAu,
            boolean annule,
            String motifAnnulation, Instant annuleLe) {
    }

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGE = 32;

    byte[] rendre(DonneesRecu d) {
        PDRectangle format = PDRectangle.A5;
        float largeur = format.getWidth();
        float utile = largeur - 2 * MARGE;
        PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDFont italique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(format);
            doc.addPage(page);
            try (PDPageContentStream f = new PDPageContentStream(doc, page)) {
                f.setLineWidth(0.6f);
                float y = format.getHeight() - MARGE;
                y = paragraphe(f, gras, 12, d.etablissement(), MARGE, y, utile) - 2;
                texte(f, normal, 9, "Année scolaire " + d.annee(), MARGE, y);
                y -= 10;
                trait(f, MARGE, y, largeur - MARGE);
                y -= 26;
                centre(f, gras, 15, "REÇU N° " + d.numero(), largeur, y);
                y -= 16;
                centre(f, normal, 9, "Émis le " + jour(d.emisLe()), largeur, y);
                y -= 26;
                String[][] lignes = {
                    { "Élève", d.eleve() },
                    { "Matricule", d.matricule() },
                    { "Classe", d.classe() },
                    { "Payé par", d.payeur() },
                    { "Remis par", d.deposant() },
                    { "Moyen de paiement", d.moyen() },
                    { "Référence", d.reference() },
                    { "Date du paiement", d.datePaiement().format(JOUR) },
                };
                for (String[] l : lignes) {
                    if (l[1] == null || l[1].isBlank()) {
                        continue;
                    }
                    texte(f, normal, 10, l[0] + " :", MARGE, y);
                    texte(f, gras, 10, ajuster(gras, 10, l[1], utile - 120), MARGE + 120, y);
                    y -= 16;
                }
                y -= 10;
                float haut = y + 14;
                texte(f, gras, 14, "Montant : " + Montants.fcfa(d.montant()), MARGE + 8, y);
                y -= 16;
                String lettres = Montants.lettres(d.montant());
                y = paragraphe(f, italique, 9, "Arrêté le présent reçu à la somme de "
                        + lettres + " francs CFA.", MARGE + 8, y, utile - 16);
                y -= 2;
                f.addRect(MARGE, y, utile, haut - y);
                f.stroke();
                y -= 18;
                texte(f, normal, 9, "Reste à payer par la famille au " + d.situationAu().format(JOUR) + " : "
                        + Montants.fcfa(d.resteFamille()),
                        MARGE, y);
                y -= 40;
                texte(f, gras, 9, "Le caissier / L'intendant", largeur - MARGE - 130, y);
                if (d.annule()) {
                    y -= 50;
                    texte(f, gras, 16, "REÇU ANNULÉ", MARGE, y);
                    y -= 14;
                    paragraphe(f, normal, 9, "Le " + jour(d.annuleLe()) + " : " + d.motifAnnulation(), MARGE, y,
                            utile);
                }
                texte(f, normal, 7, "Code de vérification : " + d.codeVerification().substring(0, 5) + "-"
                        + d.codeVerification().substring(5) + " — reçu non modifiable.", MARGE, MARGE - 12);
            }
            doc.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Production du reçu impossible", e);
        }
    }

    private static String jour(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().format(JOUR);
    }

    private static void trait(PDPageContentStream f, float x1, float y, float x2) throws IOException {
        f.moveTo(x1, y);
        f.lineTo(x2, y);
        f.stroke();
    }

    private static void texte(PDPageContentStream f, PDFont police, float taille, String valeur, float x, float y)
            throws IOException {
        String propre = propre(police, valeur);
        if (propre.isEmpty()) {
            return;
        }
        f.beginText();
        f.setFont(police, taille);
        f.newLineAtOffset(x, y);
        f.showText(propre);
        f.endText();
    }

    private static void centre(PDPageContentStream f, PDFont police, float taille, String valeur, float largeurPage,
            float y) throws IOException {
        String propre = propre(police, valeur);
        texte(f, police, taille, propre, (largeurPage - largeur(police, taille, propre)) / 2, y);
    }

    private static float paragraphe(PDPageContentStream f, PDFont police, float taille, String valeur, float x,
            float y, float max) throws IOException {
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        for (String mot : propre(police, valeur).split(" ")) {
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
        float courant = y;
        for (String l : lignes) {
            texte(f, police, taille, l, x, courant);
            courant -= taille + 3;
        }
        return courant;
    }

    private static String ajuster(PDFont police, float taille, String valeur, float max) throws IOException {
        String propre = propre(police, valeur);
        if (largeur(police, taille, propre) <= max) {
            return propre;
        }
        String court = propre;
        while (!court.isEmpty() && largeur(police, taille, court + "…") > max) {
            court = court.substring(0, court.length() - 1);
        }
        return court + "…";
    }

    private static float largeur(PDFont police, float taille, String valeur) throws IOException {
        return police.getStringWidth(valeur) / 1000 * taille;
    }

    /** Remplace les caractères que la police standard ne sait pas écrire. */
    private static String propre(PDFont police, String valeur) {
        if (valeur == null) {
            return "";
        }
        String simple = valeur.replace(' ', ' ').replace(' ', ' ').replaceAll("\\s+", " ").trim();
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
