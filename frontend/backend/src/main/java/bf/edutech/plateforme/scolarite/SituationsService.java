package bf.edutech.plateforme.scolarite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.scolarite.Situations.Situation;
import bf.edutech.plateforme.scolarite.Vues.EtatClasseVue;
import bf.edutech.plateforme.scolarite.Vues.SituationResumeVue;
import bf.edutech.plateforme.scolarite.Vues.SituationVue;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Consultation : situation d'un élève, état d'une classe, liste des retards (Excel), espace parent. */
@Service
public class SituationsService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Situations situations;
    private final RelanceRepository relances;
    private final InscriptionsService inscriptions;
    private final ElevesService eleves;
    private final EspaceParentService espaceParent;
    private final ClassesService classes;
    private final AnneesService annees;
    private final ContactsEleves contacts;

    SituationsService(Situations situations, RelanceRepository relances, InscriptionsService inscriptions,
            ElevesService eleves, EspaceParentService espaceParent, ClassesService classes, AnneesService annees,
            ContactsEleves contacts) {
        this.situations = situations;
        this.relances = relances;
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.espaceParent = espaceParent;
        this.classes = classes;
        this.annees = annees;
        this.contacts = contacts;
    }

    @Transactional(readOnly = true)
    public SituationVue situation(UUID inscriptionId) {
        UtilisateurConnecte.etablissementActif();
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        return situations.vue(situations.calculer(i), annees.trouver(i.anneeId()).libelle());
    }

    /** État financier de la classe : une ligne par élève (sortis compris, leur dette demeure). */
    @Transactional(readOnly = true)
    public EtatClasseVue etatClasse(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        List<InscriptionVue> liste = inscriptions.listerParClasse(classeId, true);
        Map<UUID, Situation> calculees = situations.calculer(liste);
        Map<UUID, Instant> dernieres = dernieresRelances(liste);
        List<SituationResumeVue> lignes = calculees.values().stream()
                .map(s -> Situations.resume(s, dernieres.get(s.inscription().id()))).toList();
        long totalFamille = lignes.stream().mapToLong(SituationResumeVue::totalFamille).sum();
        long payeFamille = lignes.stream().mapToLong(SituationResumeVue::payeFamille).sum()
                - calculees.values().stream().mapToLong(s -> s.resultat().avanceFamille()).sum();
        long totalOrganisme = lignes.stream().mapToLong(SituationResumeVue::totalOrganisme).sum();
        long payeOrganisme = lignes.stream().mapToLong(SituationResumeVue::payeOrganisme).sum()
                - calculees.values().stream().mapToLong(s -> s.resultat().avanceOrganisme()).sum();
        return new EtatClasseVue(classe.id(), classe.code(), lignes, totalFamille, payeFamille,
                lignes.stream().mapToLong(SituationResumeVue::retardFamille).sum(), totalOrganisme, payeOrganisme,
                taux(payeFamille, totalFamille), taux(payeOrganisme, totalOrganisme));
    }

    /** Élèves de la classe en retard de paiement (part famille), au format Excel. */
    @Transactional(readOnly = true)
    public byte[] retardsExcel(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        List<InscriptionVue> liste = inscriptions.listerParClasse(classeId, true);
        List<Situation> enRetard = new ArrayList<>(situations.calculer(liste).values().stream()
                .filter(s -> s.resultat().retardFamille() > 0).toList());
        enRetard.sort(Comparator.comparingLong((Situation s) -> s.resultat().retardFamille()).reversed()
                .thenComparing(s -> s.inscription().nom()));
        Map<UUID, ContactEleve> contactsParInscription = contacts
                .pourInscriptions(enRetard.stream().map(s -> s.inscription().id()).toList());
        Map<UUID, Instant> dernieres = dernieresRelances(liste);
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Sheet feuille = classeur.createSheet("Retards " + classe.code().replaceAll("[\\\\/?*\\[\\]:]", "-"));
            CellStyle gras = classeur.createCellStyle();
            Font police = classeur.createFont();
            police.setBold(true);
            gras.setFont(police);
            CellStyle milliers = classeur.createCellStyle();
            milliers.setDataFormat(classeur.createDataFormat().getFormat("#,##0"));
            String[] entetes = { "Matricule", "Nom", "Prénoms", "Bourse", "Dû famille", "Payé", "Reste",
                "En retard", "Téléphone du contact", "Dernière relance" };
            Row titre = feuille.createRow(0);
            for (int c = 0; c < entetes.length; c++) {
                Cell cellule = titre.createCell(c);
                cellule.setCellValue(entetes[c]);
                cellule.setCellStyle(gras);
            }
            int ligne = 1;
            long totalRetard = 0;
            for (Situation s : enRetard) {
                InscriptionVue i = s.inscription();
                CalculEcheancier.Resultat r = s.resultat();
                ContactEleve contact = contactsParInscription.get(i.id());
                Instant relance = dernieres.get(i.id());
                Row row = feuille.createRow(ligne++);
                row.createCell(0).setCellValue(i.matricule());
                row.createCell(1).setCellValue(i.nom());
                row.createCell(2).setCellValue(i.prenoms());
                row.createCell(3).setCellValue(i.statutBourse().name());
                nombre(row, 4, r.totalFamille(), milliers);
                nombre(row, 5, r.payeFamille() - r.avanceFamille(), milliers);
                nombre(row, 6, r.resteFamille(), milliers);
                nombre(row, 7, r.retardFamille(), milliers);
                row.createCell(8).setCellValue(contact != null && contact.telephone() != null ? contact.telephone() : "");
                row.createCell(9).setCellValue(relance != null
                        ? relance.atZone(ZoneOffset.UTC).toLocalDate().format(JOUR) : "");
                totalRetard += r.retardFamille();
            }
            Row total = feuille.createRow(ligne);
            Cell libelle = total.createCell(6);
            libelle.setCellValue("Total");
            libelle.setCellStyle(gras);
            nombre(total, 7, totalRetard, milliers);
            int[] largeurs = { 14, 20, 24, 14, 12, 12, 12, 12, 20, 16 };
            for (int c = 0; c < largeurs.length; c++) {
                feuille.setColumnWidth(c, largeurs[c] * 256);
            }
            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Production du fichier des retards impossible", e);
        }
    }

    /** Situation de chaque année d'inscription d'un enfant du parent connecté (la plus récente d'abord). */
    @Transactional(readOnly = true)
    public List<SituationVue> deMonEnfant(UUID eleveId) {
        if (!espaceParent.estMonEnfant(eleveId)) {
            throw new AccesRefuseException("Cet élève n'est pas rattaché à votre compte");
        }
        List<InscriptionVue> liste = new ArrayList<>(eleves.trouver(eleveId).inscriptions());
        liste.sort(Comparator.comparing(InscriptionVue::inscritLe).reversed());
        Map<UUID, String> libellesAnnees = new HashMap<>();
        return situations.calculer(liste).values().stream()
                .map(s -> situations.vue(s, libellesAnnees.computeIfAbsent(s.inscription().anneeId(),
                        id -> annees.trouver(id).libelle())))
                .toList();
    }

    private Map<UUID, Instant> dernieresRelances(List<InscriptionVue> liste) {
        Map<UUID, Instant> dernieres = new HashMap<>();
        if (!liste.isEmpty()) {
            relances.findByInscriptionIdIn(liste.stream().map(InscriptionVue::id).toList())
                    .forEach(r -> dernieres.merge(r.getInscriptionId(), r.getEnvoyeLe(),
                            (a, b) -> a.isAfter(b) ? a : b));
        }
        return dernieres;
    }

    private static void nombre(Row row, int colonne, long valeur, CellStyle style) {
        Cell cellule = row.createCell(colonne);
        cellule.setCellValue(valeur);
        cellule.setCellStyle(style);
    }

    private static BigDecimal taux(long paye, long total) {
        return total == 0 ? null
                : BigDecimal.valueOf(paye).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}
