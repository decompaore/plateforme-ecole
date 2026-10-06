package bf.edutech.plateforme.reversibilite;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.reversibilite.ExportsService.ExportVue;
import bf.edutech.plateforme.reversibilite.ExportsService.Lien;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Export complet des données de l'établissement actif, par son administrateur. */
@RestController
@RequestMapping("/api/v1/exports")
@PreAuthorize("hasRole('ADMIN_ECOLE')")
public class ExportsController {

    /** Mot de passe de la personne connectée, redemandé avant l'export. */
    public record DemandeExport(@NotBlank @Size(max = 200) String motDePasse) {
    }

    private final ExportsService service;

    ExportsController(ExportsService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExportVue> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportVue demander(@Valid @RequestBody DemandeExport d) {
        return service.demander(UtilisateurConnecte.id(), d.motDePasse(), false);
    }

    @PostMapping("/{id}/lien")
    public Lien lien(@PathVariable UUID id) {
        return service.lien(UtilisateurConnecte.id(), id);
    }
}
