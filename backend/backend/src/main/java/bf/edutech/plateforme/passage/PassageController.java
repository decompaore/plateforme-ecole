package bf.edutech.plateforme.passage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.passage.DecisionsService.DecisionsClasseVue;
import bf.edutech.plateforme.passage.DecisionsService.SaisieDecision;
import bf.edutech.plateforme.passage.ParametresPassageService.ParametresPassage;
import bf.edutech.plateforme.passage.ParametresPassageService.PoidsPeriode;
import bf.edutech.plateforme.passage.PassageService.AnneeSuivanteVue;
import bf.edutech.plateforme.passage.PassageService.ReinscriptionsVue;
import bf.edutech.plateforme.passage.PassageService.TableauPassageVue;

/** Passage d'année : réglages, décisions de fin d'année, année suivante, réinscriptions en masse. */
@RestController
public class PassageController {

    static final String DIRECTION = "hasAnyRole('CENSEUR','ADMIN_ECOLE')";
    static final String CONSULTATION = "hasAnyRole('CENSEUR','ADMIN_ECOLE','SECRETARIAT')";

    public record DemandeParametres(BigDecimal seuilExclusion, Integer redoublementsMax) {
    }

    public record DemandePoids(@NotNull @Size(max = 12) List<@NotNull PoidsPeriode> poids) {
    }

    public record DemandeExamen(@Size(max = 40) String examen) {
    }

    public record DemandeDecisions(@NotNull @Size(max = 200) List<@NotNull SaisieDecision> decisions) {
    }

    public record DemandeAnneeSuivante(@NotBlank String libelle, @NotNull LocalDate debut, @NotNull LocalDate fin) {
    }

    public record DemandeReinscriptions(UUID classeAdmisId, UUID classeRedoublantsId, Boolean conserverStatutBourse) {
    }

    private final ParametresPassageService parametres;
    private final DecisionsService decisions;
    private final PassageService passage;

    PassageController(ParametresPassageService parametres, DecisionsService decisions, PassageService passage) {
        this.parametres = parametres;
        this.decisions = decisions;
        this.passage = passage;
    }

    // ---------------- Réglages ----------------

    @GetMapping("/api/v1/parametres/passage")
    @PreAuthorize(CONSULTATION)
    public ParametresPassage parametres() {
        return parametres.lire();
    }

    /** {"seuilExclusion":8.5,"redoublementsMax":2} ; une valeur null désactive la règle. */
    @PutMapping("/api/v1/parametres/passage")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ParametresPassage modifierParametres(@RequestBody DemandeParametres d) {
        return parametres.modifier(new ParametresPassage(d.seuilExclusion(), d.redoublementsMax()));
    }

    @GetMapping("/api/v1/profils/{profilId}/poids-periodes")
    @PreAuthorize(CONSULTATION)
    public List<PoidsPeriode> poids(@PathVariable UUID profilId) {
        return parametres.poids(profilId);
    }

    /** {"poids":[{"ordre":1,"poids":1},{"ordre":2,"poids":2},{"ordre":3,"poids":2}]}. */
    @PutMapping("/api/v1/profils/{profilId}/poids-periodes")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public List<PoidsPeriode> definirPoids(@PathVariable UUID profilId, @Valid @RequestBody DemandePoids d) {
        return parametres.definirPoids(profilId, d.poids());
    }

    /** {"examen":"BEPC"} ; vide : la classe ne prépare plus d'examen. */
    @PutMapping("/api/v1/classes/{classeId}/examen")
    @PreAuthorize(DIRECTION)
    public Map<String, String> definirExamen(@PathVariable UUID classeId, @Valid @RequestBody DemandeExamen d) {
        return Map.of("examen", parametres.definirExamen(classeId, d.examen()).orElse(""));
    }

    // ---------------- Décisions de fin d'année ----------------

    @PostMapping("/api/v1/classes/{classeId}/decisions")
    @PreAuthorize(DIRECTION)
    public DecisionsClasseVue proposer(@PathVariable UUID classeId) {
        return decisions.proposer(classeId);
    }

    @GetMapping("/api/v1/classes/{classeId}/decisions")
    @PreAuthorize(CONSULTATION)
    public DecisionsClasseVue decisions(@PathVariable UUID classeId) {
        return decisions.lister(classeId);
    }

    /**
     * {"decisions":[{"inscriptionId":"…","decision":"ADMIS","motif":"Rachat : 9,80 et bon comportement"},
     * {"inscriptionId":"…","resultatExamen":"ADMIS"}]}.
     */
    @PutMapping("/api/v1/classes/{classeId}/decisions")
    @PreAuthorize(DIRECTION)
    public DecisionsClasseVue modifier(@PathVariable UUID classeId, @Valid @RequestBody DemandeDecisions d) {
        return decisions.modifier(classeId, d.decisions());
    }

    @PostMapping("/api/v1/classes/{classeId}/decisions/validation")
    @PreAuthorize(DIRECTION)
    public DecisionsClasseVue valider(@PathVariable UUID classeId) {
        return decisions.valider(classeId);
    }

    // ---------------- Année suivante et réinscriptions ----------------

    @GetMapping("/api/v1/annees/{anneeId}/passage")
    @PreAuthorize(CONSULTATION)
    public TableauPassageVue tableau(@PathVariable UUID anneeId) {
        return passage.tableau(anneeId);
    }

    /** {"libelle":"2027-2028","debut":"2027-10-01","fin":"2028-07-31"}. */
    @PostMapping("/api/v1/annees/{anneeId}/annee-suivante")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public AnneeSuivanteVue anneeSuivante(@PathVariable UUID anneeId, @Valid @RequestBody DemandeAnneeSuivante d) {
        return passage.creerAnneeSuivante(anneeId, d.libelle(), d.debut(), d.fin());
    }

    /** {"classeAdmisId":"…","classeRedoublantsId":"…","conserverStatutBourse":false}. */
    @PostMapping("/api/v1/classes/{classeId}/decisions/reinscriptions")
    @PreAuthorize("hasAnyRole('SECRETARIAT','ADMIN_ECOLE')")
    public ReinscriptionsVue reinscrire(@PathVariable UUID classeId, @RequestBody DemandeReinscriptions d) {
        return passage.reinscrire(classeId, d.classeAdmisId(), d.classeRedoublantsId(),
                Boolean.TRUE.equals(d.conserverStatutBourse()));
    }
}
