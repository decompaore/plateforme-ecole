package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.evaluations.ContexteClasse.Contexte;
import bf.edutech.plateforme.evaluations.Vues.ResultatEleveVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatsPeriodeVue;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Résultats d'une classe pour une période, calculés par la stratégie du profil
 * pédagogique de la classe, avec les statistiques de la classe et les alertes
 * de complétude (évaluations sans note, matières non évaluées…).
 */
@Service
public class ResultatsService {

    private final ContexteClasse contextes;
    private final AutorisationsEvaluations autorisations;
    private final MoteurEvaluation moteur;
    private final InscriptionsService inscriptions;
    private final EvaluationRepository evaluations;
    private final NoteRepository notes;
    private final CompetenceRepository competences;
    private final ResultatCompetenceRepository resultatsCompetences;

    ResultatsService(ContexteClasse contextes, AutorisationsEvaluations autorisations, MoteurEvaluation moteur,
            InscriptionsService inscriptions, EvaluationRepository evaluations, NoteRepository notes,
            CompetenceRepository competences, ResultatCompetenceRepository resultatsCompetences) {
        this.contextes = contextes;
        this.autorisations = autorisations;
        this.moteur = moteur;
        this.inscriptions = inscriptions;
        this.evaluations = evaluations;
        this.notes = notes;
        this.competences = competences;
        this.resultatsCompetences = resultatsCompetences;
    }

    @Transactional(readOnly = true)
    public ResultatsPeriodeVue calculer(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        autorisations.verifierConsultation(classeId);
        Contexte c = contextes.pourLecture(classeId, periodeId);
        List<InscriptionVue> eleves = inscriptions.listerParClasse(classeId, false);
        List<Evaluation> evals = evaluations.findByClasseIdAndPeriodeIdOrderByDateEvaluationAscLibelleAsc(classeId,
                periodeId);
        List<Note> notesPeriode = evals.isEmpty() ? List.of()
                : notes.findByEvaluationIdIn(evals.stream().map(Evaluation::getId).toList());
        List<UUID> modules = c.matieres().stream().filter(Contexte::estModule).map(m -> m.matiereId()).toList();
        List<Competence> referentiels = modules.isEmpty() ? List.of()
                : competences.findByMatiereIdInAndActifTrue(modules);
        List<ResultatCompetence> niveaux = referentiels.isEmpty() ? List.of()
                : resultatsCompetences.findByPeriodeIdAndCompetenceIdIn(periodeId,
                        referentiels.stream().map(Competence::getId).toList());

        ResultatsCalcules calcules = moteur.pour(c.profil().modele()).calculer(new DonneesCalcul(c.profil(),
                c.matieres(), eleves, evals, notesPeriode, referentiels, niveaux));

        List<BigDecimal> moyennes = calcules.eleves().stream().map(ResultatEleveVue::moyenne).filter(Objects::nonNull)
                .toList();
        BigDecimal moyenneClasse = moyennes.isEmpty() ? null
                : moyennes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(moyennes.size()), c.profil().decimalesMoyenne(), RoundingMode.HALF_UP);
        long admis = calcules.eleves().stream().filter(ResultatEleveVue::admis).count();
        return new ResultatsPeriodeVue(classeId, c.classe().code(), periodeId, c.periode().libelle(),
                c.profil().modele(), eleves.size(), moyenneClasse,
                moyennes.stream().max(BigDecimal::compareTo).orElse(null),
                moyennes.stream().min(BigDecimal::compareTo).orElse(null),
                CalculNotes.pourcentage(admis, eleves.size()), calcules.alertes(), calcules.eleves());
    }
}
