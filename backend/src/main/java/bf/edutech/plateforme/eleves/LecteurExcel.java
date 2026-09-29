package bf.edutech.plateforme.eleves;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Lecture du fichier Excel (.xlsx) d'import : une ligne lue par élève. */
final class LecteurExcel {

    /**
     * Ligne lue. {@code date} vaut null si la cellule est vide ou illisible ;
     * {@code valeurs[DATE_NAISSANCE]} garde alors le texte saisi.
     */
    record LigneLue(int numero, String[] valeurs, LocalDate date) {

        String valeur(int colonne) {
            return valeurs[colonne];
        }
    }

    private static final List<DateTimeFormatter> FORMATS_DATE = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private LecteurExcel() {
    }

    static List<LigneLue> lire(InputStream flux) {
        List<LigneLue> lignes = new ArrayList<>();
        boolean tropDeLignes = false;
        try (XSSFWorkbook classeur = new XSSFWorkbook(flux)) {
            Sheet feuille = classeur.getSheet(FormatImport.FEUILLE);
            if (feuille == null) {
                feuille = classeur.getSheetAt(0);
            }
            DataFormatter formateur = new DataFormatter(Locale.FRANCE);
            for (int r = 1; r <= feuille.getLastRowNum(); r++) {
                Row ligne = feuille.getRow(r);
                if (ligne == null) {
                    continue;
                }
                String[] valeurs = new String[FormatImport.ENTETES.length];
                boolean vide = true;
                for (int c = 0; c < valeurs.length; c++) {
                    valeurs[c] = texte(ligne.getCell(c), formateur);
                    vide &= valeurs[c] == null;
                }
                if (vide) {
                    continue;
                }
                if (lignes.size() == FormatImport.LIGNES_MAX) {
                    tropDeLignes = true;
                    break;
                }
                lignes.add(new LigneLue(r + 1, valeurs, date(ligne.getCell(FormatImport.DATE_NAISSANCE),
                        valeurs[FormatImport.DATE_NAISSANCE])));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Fichier illisible : utilisez le modèle Excel (.xlsx) fourni");
        }
        if (tropDeLignes) {
            throw new IllegalArgumentException("Le fichier dépasse " + FormatImport.LIGNES_MAX
                    + " élèves : découpez-le en plusieurs fichiers");
        }
        return lignes;
    }

    private static String texte(Cell cellule, DataFormatter formateur) {
        if (cellule == null) {
            return null;
        }
        String texte = formateur.formatCellValue(cellule).trim();
        return texte.isEmpty() ? null : texte;
    }

    private static LocalDate date(Cell cellule, String texte) {
        if (cellule == null || texte == null) {
            return null;
        }
        if (cellule.getCellType() == CellType.NUMERIC) {
            double valeur = cellule.getNumericCellValue();
            // Date Excel (jour n° 1 = 01/01/1900), même si la cellule n'est pas au format date
            return DateUtil.isValidExcelDate(valeur) && valeur > 1 ? DateUtil.getLocalDateTime(valeur).toLocalDate()
                    : null;
        }
        for (DateTimeFormatter format : FORMATS_DATE) {
            try {
                return LocalDate.parse(texte, format);
            } catch (DateTimeParseException e) {
                // format suivant
            }
        }
        return null;
    }
}
