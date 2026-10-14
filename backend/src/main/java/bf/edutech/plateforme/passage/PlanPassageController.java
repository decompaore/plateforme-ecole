package bf.edutech.plateforme.passage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.passage.PlanPassageService.Affectation;
import bf.edutech.plateforme.passage.PlanPassageService.PlanVue;
import bf.edutech.plateforme.passage.PlanPassageService.ResultatPlan;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.export.ExportTableaux;

/** Plan de passage vers l'année suivante (v0.45) : proposition, listes des classes, confirmation. */
@RestController
public class PlanPassageController {

    public record DemandePlan(@NotNull @Size(max = 5000) List<@NotNull Affectation> affectations,
            Boolean conserverStatutBourse) {
    }

    private final PlanPassageService plan;
    private final EntetesOfficiels entetes;

    PlanPassageController(PlanPassageService plan, EntetesOfficiels entetes) {
        this.plan = plan;
        this.entetes = entetes;
    }

    @GetMapping("/api/v1/annees/{anneeId}/plan-passage")
    @PreAuthorize(PassageController.CONSULTATION)
    public PlanVue proposer(@PathVariable UUID anneeId) {
        return plan.proposer(anneeId);
    }

    /** Listes des classes de l'année suivante (inscrits et proposés) et des sortants : Excel ou PDF. */
    @GetMapping("/api/v1/annees/{anneeId}/plan-passage/listes")
    @PreAuthorize(PassageController.CONSULTATION)
    public ResponseEntity<byte[]> listes(@PathVariable UUID anneeId, @RequestParam(required = false) String format) {
        ExportTableaux.Format f = ExportTableaux.Format.lire(format);
        return ExportTableaux.reponse(entetes.courant(), "listes-annee-suivante-" + LocalDate.now(ZoneId.of("Africa/Ouagadougou")),
                f, plan.listes(anneeId));
    }

    @PostMapping("/api/v1/annees/{anneeId}/plan-passage")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CENSEUR')")       // v0.47 : le censeur répartit
    public ResultatPlan appliquer(@PathVariable UUID anneeId, @Valid @RequestBody DemandePlan d) {
        return plan.appliquer(anneeId, d.affectations(), Boolean.TRUE.equals(d.conserverStatutBourse()));
    }
}
