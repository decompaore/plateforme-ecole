package bf.edutech.plateforme.etablissement;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ResultatCopie;

/** Années scolaires. Lecture : tout membre ; création et transitions : administrateur d'école. */
@RestController
@RequestMapping("/api/v1/annees")
public class AnneesController {

    public record DemandeAnnee(
            @NotBlank(message = "Libellé attendu de la forme 2026-2027") String libelle,
            @NotNull LocalDate debut,
            @NotNull LocalDate fin) {
    }

    private final AnneesService service;

    AnneesController(AnneesService service) {
        this.service = service;
    }

    @GetMapping
    public List<AnneeVue> lister() {
        return service.lister();
    }

    @GetMapping("/active")
    public AnneeVue active() {
        return service.active();
    }

    @GetMapping("/{id}")
    public AnneeVue trouver(@PathVariable UUID id) {
        return service.trouver(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public AnneeVue creer(@Valid @RequestBody DemandeAnnee d) {
        return service.creer(d.libelle(), d.debut(), d.fin());
    }

    @PostMapping("/{id}/ouverture")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public AnneeVue ouvrir(@PathVariable UUID id) {
        return service.ouvrir(id);
    }

    @PostMapping("/{id}/cloture")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public AnneeVue cloturer(@PathVariable UUID id) {
        return service.cloturer(id);
    }

    @PostMapping("/{id}/archivage")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public AnneeVue archiver(@PathVariable UUID id) {
        return service.archiver(id);
    }

    /** Crée l'année suivante en copiant classes, matières, coefficients et périodes. */
    @PostMapping("/{id}/copie")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ResultatCopie copier(@PathVariable UUID id, @Valid @RequestBody DemandeAnnee d) {
        return service.copier(id, d.libelle(), d.debut(), d.fin());
    }
}
