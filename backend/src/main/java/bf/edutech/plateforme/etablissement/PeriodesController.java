package bf.edutech.plateforme.etablissement;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;

/** Périodes d'une année. Modification : administrateur d'école ou censeur. */
@RestController
public class PeriodesController {

    private static final String GESTION = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";

    public record DemandeGeneration(@NotNull UUID profilId) {
    }

    public record DemandePeriode(
            @NotNull UUID profilId,
            @NotBlank @Size(max = 40) String libelle,
            @NotNull LocalDate debut,
            @NotNull LocalDate fin) {
    }

    public record DemandeModificationPeriode(
            @NotBlank @Size(max = 40) String libelle,
            @NotNull LocalDate debut,
            @NotNull LocalDate fin) {
    }

    private final PeriodesService service;

    PeriodesController(PeriodesService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/annees/{anneeId}/periodes")
    public List<PeriodeVue> lister(@PathVariable UUID anneeId) {
        return service.lister(anneeId);
    }

    /** Trimestres ou semestres générés automatiquement selon le profil. */
    @PostMapping("/api/v1/annees/{anneeId}/periodes/generation")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public List<PeriodeVue> generer(@PathVariable UUID anneeId, @Valid @RequestBody DemandeGeneration d) {
        return service.generer(anneeId, d.profilId());
    }

    /** Ajout manuel (modules de formation professionnelle, sessions). */
    @PostMapping("/api/v1/annees/{anneeId}/periodes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public PeriodeVue creer(@PathVariable UUID anneeId, @Valid @RequestBody DemandePeriode d) {
        return service.creer(anneeId, d.profilId(), d.libelle(), d.debut(), d.fin());
    }

    @PutMapping("/api/v1/periodes/{id}")
    @PreAuthorize(GESTION)
    public PeriodeVue modifier(@PathVariable UUID id, @Valid @RequestBody DemandeModificationPeriode d) {
        return service.modifier(id, d.libelle(), d.debut(), d.fin());
    }

    @DeleteMapping("/api/v1/periodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void supprimer(@PathVariable UUID id) {
        service.supprimer(id);
    }

    @PostMapping("/api/v1/periodes/{id}/verrouillage")
    @PreAuthorize(GESTION)
    public PeriodeVue verrouiller(@PathVariable UUID id) {
        return service.verrouiller(id);
    }

    @PostMapping("/api/v1/periodes/{id}/deverrouillage")
    @PreAuthorize(GESTION)
    public PeriodeVue deverrouiller(@PathVariable UUID id) {
        return service.deverrouiller(id);
    }
}
