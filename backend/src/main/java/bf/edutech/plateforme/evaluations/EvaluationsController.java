package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.evaluations.Vues.CompetenceVue;
import bf.edutech.plateforme.evaluations.Vues.EvaluationVue;
import bf.edutech.plateforme.evaluations.Vues.FeuilleNotesVue;
import bf.edutech.plateforme.evaluations.Vues.GrilleCompetencesVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatsPeriodeVue;
import bf.edutech.plateforme.evaluations.Vues.SaisieCompetence;
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;

/** Évaluations, notes, compétences et résultats d'une période. */
@RestController
public class EvaluationsController {

    static final String SAISIE = "hasAnyRole('ENSEIGNANT','CENSEUR','ADMIN_ECOLE')";
    static final String CONSULTATION = "hasAnyRole('ENSEIGNANT','CENSEUR','ADMIN_ECOLE','SECRETARIAT')";

    /** {@code idClient} : facultatif, identifiant généré par l'appareil pour une création idempotente. */
    public record DemandeEvaluation(
            @NotNull UUID matiereId,
            @NotNull UUID periodeId,
            @NotBlank @Size(max = 80) String libelle,
            @NotNull TypeEvaluation type,
            @NotNull LocalDate date,
            BigDecimal bareme,
            BigDecimal poids,
            UUID idClient) {
    }

    public record DemandeModificationEvaluation(
            @NotBlank @Size(max = 80) String libelle,
            @NotNull TypeEvaluation type,
            @NotNull LocalDate date,
            BigDecimal bareme,
            BigDecimal poids) {
    }

    public record DemandeNotes(@NotNull @Size(max = 200) List<@NotNull SaisieNote> notes) {
    }

    public record DemandeCompetence(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 200) String libelle,
            @Min(1) @Max(999) Integer ordre) {
    }

    public record DemandeNiveaux(@NotNull @Size(max = 5000) List<@NotNull SaisieCompetence> resultats) {
    }

    private final EvaluationsService evaluations;
    private final CompetencesService competences;
    private final ResultatsService resultats;

    EvaluationsController(EvaluationsService evaluations, CompetencesService competences, ResultatsService resultats) {
        this.evaluations = evaluations;
        this.competences = competences;
        this.resultats = resultats;
    }

    @PostMapping("/api/v1/classes/{classeId}/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SAISIE)
    public EvaluationVue creer(@PathVariable UUID classeId, @Valid @RequestBody DemandeEvaluation d) {
        return evaluations.creer(classeId, d.matiereId(), d.periodeId(), d.libelle(), d.type(), d.date(), d.bareme(),
                d.poids(), d.idClient());
    }

    @GetMapping("/api/v1/classes/{classeId}/evaluations")
    @PreAuthorize(CONSULTATION)
    public List<EvaluationVue> lister(@PathVariable UUID classeId, @RequestParam UUID periodeId) {
        return evaluations.lister(classeId, periodeId);
    }

    @PutMapping("/api/v1/evaluations/{id}")
    @PreAuthorize(SAISIE)
    public EvaluationVue modifier(@PathVariable UUID id, @Valid @RequestBody DemandeModificationEvaluation d) {
        return evaluations.modifier(id, d.libelle(), d.type(), d.date(), d.bareme(), d.poids());
    }

    @DeleteMapping("/api/v1/evaluations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(SAISIE)
    public void supprimer(@PathVariable UUID id) {
        evaluations.supprimer(id);
    }

    @GetMapping("/api/v1/evaluations/{id}/notes")
    @PreAuthorize(CONSULTATION)
    public FeuilleNotesVue feuille(@PathVariable UUID id) {
        return evaluations.feuille(id);
    }

    /** Saisie des notes : {"notes":[{"inscriptionId":"…","valeur":14.5},{"inscriptionId":"…","absent":true}]}. */
    @PutMapping("/api/v1/evaluations/{id}/notes")
    @PreAuthorize(SAISIE)
    public FeuilleNotesVue saisir(@PathVariable UUID id, @Valid @RequestBody DemandeNotes d) {
        return evaluations.saisir(id, d.notes());
    }

    @PostMapping("/api/v1/matieres/{matiereId}/competences")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CENSEUR','ADMIN_ECOLE')")
    public CompetenceVue creerCompetence(@PathVariable UUID matiereId, @Valid @RequestBody DemandeCompetence d) {
        return competences.creer(matiereId, d.code(), d.libelle(), d.ordre());
    }

    @GetMapping("/api/v1/matieres/{matiereId}/competences")
    @PreAuthorize(CONSULTATION)
    public List<CompetenceVue> referentiel(@PathVariable UUID matiereId) {
        return competences.lister(matiereId);
    }

    @GetMapping("/api/v1/classes/{classeId}/competences")
    @PreAuthorize(CONSULTATION)
    public GrilleCompetencesVue grille(@PathVariable UUID classeId, @RequestParam UUID periodeId,
            @RequestParam UUID matiereId) {
        return competences.grille(classeId, periodeId, matiereId);
    }

    @PutMapping("/api/v1/classes/{classeId}/competences")
    @PreAuthorize(SAISIE)
    public GrilleCompetencesVue evaluer(@PathVariable UUID classeId, @RequestParam UUID periodeId,
            @RequestParam UUID matiereId, @Valid @RequestBody DemandeNiveaux d) {
        return competences.evaluer(classeId, periodeId, matiereId, d.resultats());
    }

    /** Moyennes, rangs, moyennes de groupes ou niveaux de maîtrise, selon le profil de la classe. */
    @GetMapping("/api/v1/classes/{classeId}/resultats")
    @PreAuthorize(CONSULTATION)
    public ResultatsPeriodeVue resultats(@PathVariable UUID classeId, @RequestParam UUID periodeId) {
        return resultats.calculer(classeId, periodeId);
    }
}
