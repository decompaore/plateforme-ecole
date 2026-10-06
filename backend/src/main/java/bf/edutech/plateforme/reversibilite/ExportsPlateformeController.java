package bf.edutech.plateforme.reversibilite;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.reversibilite.ExportsController.DemandeExport;
import bf.edutech.plateforme.reversibilite.ExportsService.ExportVue;
import bf.edutech.plateforme.reversibilite.ExportsService.Lien;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Export complet par le super administrateur (règle de /api/v1/plateforme/**) : un
 * établissement qui quitte la plateforme, suspendu ou résilié, récupère ainsi ses données.
 * L'établissement voit ces exports dans sa propre liste.
 */
@RestController
@RequestMapping("/api/v1/plateforme/etablissements/{etablissementId}/exports")
public class ExportsPlateformeController {

    private final ExportsService service;

    ExportsPlateformeController(ExportsService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExportVue> lister(@PathVariable UUID etablissementId) {
        return service.pour(etablissementId, service::lister);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportVue demander(@PathVariable UUID etablissementId, @Valid @RequestBody DemandeExport d) {
        UUID moi = UtilisateurConnecte.id();
        return service.pour(etablissementId, () -> service.demander(moi, d.motDePasse(), true));
    }

    @PostMapping("/{id}/lien")
    public Lien lien(@PathVariable UUID etablissementId, @PathVariable UUID id) {
        UUID moi = UtilisateurConnecte.id();
        return service.pour(etablissementId, () -> service.lien(moi, id));
    }
}
