package bf.edutech.plateforme.eleves;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import bf.edutech.plateforme.etablissement.Vues.ClasseVue;

/**
 * Modèle Excel d'import : feuille « Eleves » à remplir (listes déroulantes pour
 * le sexe, la classe, le redoublement, la bourse et le lien), feuille
 * « Classes » de l'année et feuille « Aide ».
 */
final class ModeleImport {

    private static final String[] AIDE = {
        "IMPORT DES ÉLÈVES — MODE D'EMPLOI",
        "",
        "1. Remplissez la feuille « Eleves » : une ligne par élève, à partir de la ligne 2. Ne modifiez pas la ligne 1.",
        "2. Colonnes marquées * : obligatoires.",
        "3. Matricule : laissez vide pour un nouvel élève (il sera généré). Pour un élève déjà connu de",
        "   l'établissement, indiquez son matricule : son dossier sera réutilisé.",
        "4. Date de naissance : jj/mm/aaaa (ex. 14/02/2011).",
        "5. Classe : code exact de la classe (voir la feuille « Classes »).",
        "6. Redoublant : O ou N (N par défaut). Bourse : B = boursier, SB = semi-boursier, NB = non boursier (défaut).",
        "7. Parent : facultatif. S'il est indiqué, le nom, les prénoms et le téléphone sont obligatoires.",
        "   Les frères et sœurs ayant le même téléphone de parent partagent le même responsable.",
        "8. Envoyez d'abord le fichier en SIMULATION : le rapport indique les erreurs ligne par ligne,",
        "   sans rien enregistrer. Corrigez, puis lancez l'import réel.",
        "9. À l'import réel, seules les lignes valides sont enregistrées ; les autres restent dans le rapport.",
        "",
        "Limite : " + FormatImport.LIGNES_MAX + " élèves par fichier, 5 Mo."
    };

    private ModeleImport() {
    }

    static byte[] generer(List<ClasseVue> classes) {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Sheet eleves = classeur.createSheet(FormatImport.FEUILLE);
            Sheet feuilleClasses = classeur.createSheet("Classes");
            Sheet aide = classeur.createSheet("Aide");

            CellStyle entete = classeur.createCellStyle();
            Font gras = classeur.createFont();
            gras.setBold(true);
            gras.setColor(IndexedColors.WHITE.getIndex());
            entete.setFont(gras);
            entete.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            entete.setBorderBottom(BorderStyle.THIN);
            entete.setWrapText(true);

            CellStyle texte = classeur.createCellStyle();
            texte.setDataFormat(classeur.createDataFormat().getFormat("@"));
            CellStyle date = classeur.createCellStyle();
            date.setDataFormat(classeur.createDataFormat().getFormat("dd/mm/yyyy"));

            Row ligneEntete = eleves.createRow(0);
            for (int c = 0; c < FormatImport.ENTETES.length; c++) {
                ligneEntete.createCell(c).setCellValue(FormatImport.ENTETES[c]);
                ligneEntete.getCell(c).setCellStyle(entete);
                eleves.setColumnWidth(c, 20 * 256);
            }
            eleves.setColumnWidth(FormatImport.PRENOMS, 28 * 256);
            eleves.setColumnWidth(FormatImport.PARENT_LIEN, 30 * 256);
            eleves.setDefaultColumnStyle(FormatImport.MATRICULE, texte);
            eleves.setDefaultColumnStyle(FormatImport.PARENT_TELEPHONE, texte);
            eleves.setDefaultColumnStyle(FormatImport.DATE_NAISSANCE, date);
            eleves.createFreezePane(0, 1);

            DataValidationHelper aideValidation = eleves.getDataValidationHelper();
            liste(eleves, aideValidation, FormatImport.SEXE, aideValidation.createExplicitListConstraint(
                    new String[] { "M", "F" }));
            liste(eleves, aideValidation, FormatImport.REDOUBLANT, aideValidation.createExplicitListConstraint(
                    new String[] { "O", "N" }));
            liste(eleves, aideValidation, FormatImport.BOURSE, aideValidation.createExplicitListConstraint(
                    new String[] { "B", "SB", "NB" }));
            liste(eleves, aideValidation, FormatImport.PARENT_LIEN, aideValidation.createExplicitListConstraint(
                    new String[] { "PERE", "MERE", "TUTEUR", "AUTRE" }));

            Row enteteClasses = feuilleClasses.createRow(0);
            String[] titres = { "Code", "Niveau", "Filière", "Places" };
            for (int c = 0; c < titres.length; c++) {
                enteteClasses.createCell(c).setCellValue(titres[c]);
                enteteClasses.getCell(c).setCellStyle(entete);
                feuilleClasses.setColumnWidth(c, 18 * 256);
            }
            for (int i = 0; i < classes.size(); i++) {
                ClasseVue classe = classes.get(i);
                Row ligne = feuilleClasses.createRow(i + 1);
                ligne.createCell(0).setCellValue(classe.code());
                ligne.createCell(1).setCellValue(classe.niveau());
                ligne.createCell(2).setCellValue(classe.filiereCode() != null ? classe.filiereCode() : "");
                if (classe.effectifMax() != null) {
                    ligne.createCell(3).setCellValue(classe.effectifMax().doubleValue());
                }
            }
            if (!classes.isEmpty()) {
                liste(eleves, aideValidation, FormatImport.CLASSE, aideValidation.createFormulaListConstraint(
                        "Classes!$A$2:$A$" + (classes.size() + 1)));
            }

            for (int i = 0; i < AIDE.length; i++) {
                aide.createRow(i).createCell(0).setCellValue(AIDE[i]);
            }
            aide.setColumnWidth(0, 110 * 256);

            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void liste(Sheet feuille, DataValidationHelper aide, int colonne,
            DataValidationConstraint contrainte) {
        DataValidation validation = aide.createValidation(contrainte,
                new CellRangeAddressList(1, FormatImport.LIGNES_MAX, colonne, colonne));
        validation.setShowErrorBox(true);
        validation.createErrorBox("Valeur non valide", "Choisissez une valeur dans la liste");
        feuille.addValidationData(validation);
    }
}
