package bf.edutech.plateforme.statistiques;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.passage.Decision;
import bf.edutech.plateforme.statistiques.StatistiquesService.AgeVue;
import bf.edutech.plateforme.statistiques.StatistiquesService.BourseFiliereVue;
import bf.edutech.plateforme.statistiques.StatistiquesService.Compte;
import bf.edutech.plateforme.statistiques.StatistiquesService.EffectifNiveauVue;
import bf.edutech.plateforme.statistiques.StatistiquesService.RapportVue;
import bf.edutech.plateforme.statistiques.StatistiquesService.RecouvrementClasseVue;
import bf.edutech.plateforme.statistiques.StatistiquesService.ResultatNiveauVue;
import bf.edutech.plateforme.utilisateurs.Role;

/** Classeur Excel des statistiques : une feuille par tableau (G = garçons, F = filles). */
@Component
class RapportExcel {

    private static final class Feuille {
        private final Sheet sheet;
        private final CellStyle gras;
        private int ligne;

        Feuille(XSSFWorkbook classeur, String nom, CellStyle gras, String titre) {
            this.sheet = classeur.createSheet(nom);
            this.gras = gras;
            Cell c = sheet.createRow(ligne++).createCell(0);
            c.setCellValue(titre);
            c.setCellStyle(gras);
            ligne++;
        }

        void entetes(String... valeurs) {
            Row r = sheet.createRow(ligne++);
            for (int i = 0; i < valeurs.length; i++) {
                Cell c = r.createCell(i);
                c.setCellValue(valeurs[i]);
                c.setCellStyle(gras);
            }
        }

        void ligne(Object... valeurs) {
            Row r = sheet.createRow(ligne++);
            for (int i = 0; i < valeurs.length; i++) {
                Object v = valeurs[i];
                Cell c = r.createCell(i);
                if (v instanceof Number n) {
                    c.setCellValue(n.doubleValue());
                } else if (v != null) {
                    c.setCellValue(v.toString());
                }
            }
        }

        void largeurs(int... caracteres) {
            for (int i = 0; i < caracteres.length; i++) {
                sheet.setColumnWidth(i, caracteres[i] * 256);
            }
        }
    }

    byte[] produire(RapportVue r) {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            CellStyle gras = classeur.createCellStyle();
            Font police = classeur.createFont();
            police.setBold(true);
            gras.setFont(police);
            String entete = r.etablissement() + " — année " + r.annee().libelle();

            Feuille effectifs = new Feuille(classeur, "Effectifs", gras, entete + " — effectifs par niveau");
            effectifs.entetes("Niveau", "Classes", "G", "F", "Total", "Redoublants G", "Redoublants F",
                    "Redoublants total");
            for (EffectifNiveauVue e : r.effectifs()) {
                effectifs.ligne(e.niveau(), e.classes(), e.effectif().garcons(), e.effectif().filles(),
                        e.effectif().total(), e.redoublants().garcons(), e.redoublants().filles(),
                        e.redoublants().total());
            }
            effectifs.ligne("TOTAL", r.classes(), r.effectifTotal().garcons(), r.effectifTotal().filles(),
                    r.effectifTotal().total(),
                    r.effectifs().stream().mapToInt(e -> e.redoublants().garcons()).sum(),
                    r.effectifs().stream().mapToInt(e -> e.redoublants().filles()).sum(),
                    r.effectifs().stream().mapToInt(e -> e.redoublants().total()).sum());
            effectifs.largeurs(14, 10, 8, 8, 8, 14, 14, 16);

            Feuille ages = new Feuille(classeur, "Âges", gras, entete + " — âge au 31 décembre, par niveau");
            ages.entetes("Niveau", "Âge", "G", "F", "Total");
            for (AgeVue a : r.ages()) {
                ages.ligne(a.niveau(), a.age(), a.effectif().garcons(), a.effectif().filles(), a.effectif().total());
            }
            ages.largeurs(14, 8, 8, 8, 8);

            Feuille bourses = new Feuille(classeur, "Bourses", gras, entete + " — statut de bourse par filière");
            bourses.entetes("Filière", "Boursiers G", "Boursiers F", "Semi-boursiers G", "Semi-boursiers F",
                    "Non boursiers G", "Non boursiers F");
            for (BourseFiliereVue b : r.bourses()) {
                bourses.ligne(b.filiere(), b.boursiers().garcons(), b.boursiers().filles(),
                        b.semiBoursiers().garcons(), b.semiBoursiers().filles(), b.nonBoursiers().garcons(),
                        b.nonBoursiers().filles());
            }
            bourses.largeurs(30, 12, 12, 16, 16, 16, 16);

            Feuille personnel = new Feuille(classeur, "Personnel", gras, entete + " — personnel en fonction");
            personnel.entetes("Catégorie", "H", "F", "Total");
            Compte t = r.personnel().titulaires();
            Compte v = r.personnel().vacataires();
            personnel.ligne("Enseignants titulaires", t.garcons(), t.filles(), t.total());
            personnel.ligne("Enseignants vacataires", v.garcons(), v.filles(), v.total());
            personnel.ligne("");
            personnel.entetes("Fonction", "Nombre");
            for (var e : r.personnel().administratif().entrySet()) {
                personnel.ligne(fonction(e.getKey()), e.getValue());
            }
            personnel.largeurs(28, 8, 8, 8);

            Feuille recouvrement = new Feuille(classeur, "Recouvrement", gras, entete + " — recouvrement des frais (FCFA)");
            recouvrement.entetes("Classe", "Dû familles", "Payé familles", "Taux familles %", "Dû organismes",
                    "Payé organismes", "Taux organismes %");
            for (RecouvrementClasseVue x : r.recouvrement()) {
                recouvrementLigne(recouvrement, x);
            }
            recouvrementLigne(recouvrement, r.recouvrementTotal());
            recouvrement.largeurs(14, 14, 14, 14, 14, 14, 16);

            Feuille resultats = new Feuille(classeur, "Résultats", gras, entete + " — décisions de fin d'année");
            List<Decision> colonnes = List.of(Decision.ADMIS, Decision.REDOUBLE, Decision.EXCLU, Decision.ORIENTE,
                    Decision.CERTIFIE, Decision.NON_CERTIFIE);
            String[] titres = new String[colonnes.size() + 3];
            titres[0] = "Niveau";
            titres[1] = "Élèves";
            for (int i = 0; i < colonnes.size(); i++) {
                titres[i + 2] = colonnes.get(i).name();
            }
            titres[titres.length - 1] = "Taux d'admission %";
            resultats.entetes(titres);
            for (ResultatNiveauVue x : r.resultats()) {
                Object[] valeurs = new Object[titres.length];
                valeurs[0] = x.niveau();
                valeurs[1] = x.decides().total();
                for (int i = 0; i < colonnes.size(); i++) {
                    Compte c = x.parDecision().get(colonnes.get(i));
                    valeurs[i + 2] = c == null ? 0 : c.total();
                }
                valeurs[valeurs.length - 1] = x.tauxAdmission();
                resultats.ligne(valeurs);
            }
            resultats.largeurs(14, 10, 10, 10, 10, 10, 10, 14, 18);

            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Production du classeur des statistiques impossible", e);
        }
    }

    private static void recouvrementLigne(Feuille f, RecouvrementClasseVue x) {
        f.ligne(x.classe(), x.duFamilles(), x.payeFamilles(), nombre(x.tauxFamilles()), x.duOrganismes(),
                x.payeOrganismes(), nombre(x.tauxOrganismes()));
    }

    private static Object nombre(BigDecimal valeur) {
        return valeur == null ? "—" : valeur;
    }

    private static String fonction(Role role) {
        return switch (role) {
            case ADMIN_ECOLE -> "Chef d'établissement / administration";
            case CENSEUR -> "Censeur / directeur des études";
            case CHEF_TRAVAUX -> "Chef des travaux";
            case SECRETARIAT -> "Secrétariat";
            case INTENDANT -> "Intendance";
            case SURVEILLANT -> "Surveillance";
            default -> role.name();
        };
    }
}
