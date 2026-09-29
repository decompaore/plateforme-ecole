package bf.edutech.plateforme.passage;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.ResultatReinscription;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.ResultatCopie;
import bf.edutech.plateforme.scolarite.FraisService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Passage à l'année suivante : tableau de bord des décisions, création de l'année N+1 à partir de
 * l'année N (structure, frais, classes d'examen), réinscriptions en masse d'après les décisions validées.
 */
@Service
public class PassageService {

    public record ClassePassageVue(UUID classeId, String classeCode, String niveau, String examen, int decisions,
            boolean validees, Map<Decision, Long> repartition) {
    }

    public record TableauPassageVue(AnneeVue annee, int classes, int classesValidees, List<ClassePassageVue> detail) {
    }

    public record AnneeSuivanteVue(AnneeVue annee, int classesCopiees, int matieresCopiees, int periodesCopiees,
            int fraisRepris, int examensRepris) {
    }

    public record ReinscriptionsVue(ResultatReinscription admis, ResultatReinscription redoublants,
            int nonReinscrits) {
    }

    private final DecisionRepository decisions;
    private final ParametresPassageService parametres;
    private final AnneesService annees;
    private final ClassesService classes;
    private final InscriptionsService inscriptions;
    private final FraisService frais;
    private final AuditService audit;

    PassageService(DecisionRepository decisions, ParametresPassageService parametres, AnneesService annees,
            ClassesService classes, InscriptionsService inscriptions, FraisService frais, AuditService audit) {
        this.decisions = decisions;
        this.parametres = parametres;
        this.annees = annees;
        this.classes = classes;
        this.inscriptions = inscriptions;
        this.frais = frais;
        this.audit = audit;
    }

    /** Où en est le passage : décisions calculées et validées, classe par classe. */
    @Transactional(readOnly = true)
    public TableauPassageVue tableau(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        AnneeVue annee = annees.trouver(anneeId);
        List<ClasseVue> liste = classes.lister(anneeId);
        List<UUID> ids = liste.stream().map(ClasseVue::id).toList();
        Map<UUID, List<DecisionFinAnnee>> parClasse = ids.isEmpty() ? Map.of()
                : decisions.findByClasseIdIn(ids).stream().collect(Collectors.groupingBy(DecisionFinAnnee::getClasseId));
        Map<UUID, String> examens = parametres.examens(ids);
        List<ClassePassageVue> detail = liste.stream().map(c -> {
            List<DecisionFinAnnee> ds = parClasse.getOrDefault(c.id(), List.of());
            Map<Decision, Long> repartition = ds.stream()
                    .collect(Collectors.groupingBy(DecisionFinAnnee::getDecision, java.util.TreeMap::new,
                            Collectors.counting()));
            return new ClassePassageVue(c.id(), c.code(), c.niveau(), examens.get(c.id()), ds.size(),
                    !ds.isEmpty() && ds.stream().allMatch(DecisionFinAnnee::estValidee), repartition);
        }).toList();
        return new TableauPassageVue(annee, liste.size(), (int) detail.stream().filter(ClassePassageVue::validees)
                .count(), detail);
    }

    /** Crée l'année suivante par copie : classes, matières, coefficients, périodes, frais, classes d'examen. */
    @Transactional
    public AnneeSuivanteVue creerAnneeSuivante(UUID anneeSourceId, String libelle, LocalDate debut, LocalDate fin) {
        UtilisateurConnecte.etablissementActif();
        if (libelle == null || debut == null || fin == null) {
            throw new IllegalArgumentException("Indiquez le libellé (ex. 2027-2028), le début et la fin de l'année");
        }
        AnneeVue source = annees.trouver(anneeSourceId);
        if (!debut.isAfter(source.fin())) {
            throw new IllegalArgumentException("L'année suivante commence après la fin de l'année " + source.libelle());
        }
        ResultatCopie copie = annees.copier(anneeSourceId, libelle, debut, fin);
        int fraisRepris = frais.copier(anneeSourceId, copie.annee().id());
        Map<String, UUID> nouvelles = classes.lister(copie.annee().id()).stream()
                .collect(Collectors.toMap(c -> c.code().toLowerCase(), ClasseVue::id, (a, b) -> a));
        List<ClasseVue> anciennes = classes.lister(anneeSourceId);
        Map<UUID, String> examens = parametres.examens(anciennes.stream().map(ClasseVue::id).toList());
        int examensRepris = 0;
        for (ClasseVue c : anciennes) {
            UUID nouvelle = nouvelles.get(c.code().toLowerCase());
            if (examens.containsKey(c.id()) && nouvelle != null) {
                parametres.definirExamen(nouvelle, examens.get(c.id()));
                examensRepris++;
            }
        }
        audit.enregistrer("ANNEE_SUIVANTE_CREEE", copie.annee().libelle(),
                Map.of("source", source.libelle(), "frais", fraisRepris, "examens", examensRepris));
        return new AnneeSuivanteVue(copie.annee(), copie.classesCopiees(), copie.matieresCopiees(),
                copie.periodesCopiees(), fraisRepris, examensRepris);
    }

    /**
     * Réinscrit en masse les élèves d'une classe d'après les décisions validées : admis dans
     * {@code classeAdmisId}, redoublants et non certifiés dans {@code classeRedoublantsId} (avec la mention
     * redoublant). Exclus, orientés et certifiés ne sont pas réinscrits (un orienté se réinscrit à la main).
     * Une classe cible absente : ce groupe n'est pas réinscrit.
     */
    @Transactional
    public ReinscriptionsVue reinscrire(UUID classeId, UUID classeAdmisId, UUID classeRedoublantsId,
            boolean conserverStatutBourse) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        List<DecisionFinAnnee> liste = decisions.findByClasseId(classeId);
        if (liste.isEmpty() || !liste.stream().allMatch(DecisionFinAnnee::estValidee)) {
            throw new RegleMetierException("DECISIONS_NON_VALIDEES", "Validez d'abord les décisions de la classe "
                    + classe.code());
        }
        if (classeAdmisId == null && classeRedoublantsId == null) {
            throw new IllegalArgumentException("Indiquez la classe des admis, celle des redoublants, ou les deux");
        }
        Map<Decision, List<UUID>> parDecision = liste.stream().collect(Collectors.groupingBy(
                DecisionFinAnnee::getDecision, Collectors.mapping(DecisionFinAnnee::getInscriptionId,
                        Collectors.toList())));
        List<UUID> admis = parDecision.getOrDefault(Decision.ADMIS, List.of());
        List<UUID> redoublants = new java.util.ArrayList<>(parDecision.getOrDefault(Decision.REDOUBLE, List.of()));
        redoublants.addAll(parDecision.getOrDefault(Decision.NON_CERTIFIE, List.of()));
        ResultatReinscription resAdmis = classeAdmisId == null || admis.isEmpty() ? null
                : inscriptions.reinscrire(classeAdmisId, admis, false, conserverStatutBourse);
        ResultatReinscription resRedoublants = classeRedoublantsId == null || redoublants.isEmpty() ? null
                : inscriptions.reinscrire(classeRedoublantsId, redoublants, true, conserverStatutBourse);
        int nonConcernes = (int) liste.stream().map(DecisionFinAnnee::getDecision)
                .filter(d -> d == Decision.EXCLU || d == Decision.CERTIFIE || d == Decision.ORIENTE).count();
        audit.enregistrer("REINSCRIPTIONS_PASSAGE", classe.code(), Map.of(
                "admis", resAdmis == null ? 0 : resAdmis.reinscrits(),
                "redoublants", resRedoublants == null ? 0 : resRedoublants.reinscrits()));
        return new ReinscriptionsVue(resAdmis, resRedoublants, nonConcernes);
    }
}
