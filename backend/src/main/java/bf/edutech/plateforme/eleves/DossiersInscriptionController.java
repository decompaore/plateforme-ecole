package bf.edutech.plateforme.eleves;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.eleves.DossiersInscriptionService.DonneesPiece;
import bf.edutech.plateforme.eleves.DossiersInscriptionService.DossierInscriptionVue;
import bf.edutech.plateforme.eleves.DossiersInscriptionService.LigneDossierVue;
import bf.edutech.plateforme.eleves.DossiersInscriptionService.PieceVue;

/** Pièces du dossier d'inscription et mode d'admission (v0.47). */
@RestController
public class DossiersInscriptionController {

    public record DemandeDossier(ModeAdmission modeAdmission, List<UUID> piecesRecues) {
    }

    private final DossiersInscriptionService service;

    DossiersInscriptionController(DossiersInscriptionService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/pieces-dossier")
    @PreAuthorize(Roles.CONSULTATION)
    public List<PieceVue> pieces() {
        return service.pieces();
    }

    @PutMapping("/api/v1/pieces-dossier")
    @PreAuthorize(Roles.MODIFICATION)
    public List<PieceVue> definir(@RequestBody List<DonneesPiece> pieces) {
        return service.definirPieces(pieces);
    }

    @GetMapping("/api/v1/inscriptions/{id}/dossier")
    @PreAuthorize(Roles.CONSULTATION)
    public DossierInscriptionVue dossier(@PathVariable UUID id) {
        return service.dossier(id);
    }

    /** L'intendant complète ; retirer une pièce ou changer le mode : le chef d'établissement (contrôlé au service). */
    @PutMapping("/api/v1/inscriptions/{id}/dossier")
    @PreAuthorize(Roles.SAISIE)
    public DossierInscriptionVue enregistrer(@PathVariable UUID id, @RequestBody DemandeDossier d) {
        return service.enregistrer(id, d.modeAdmission(), d.piecesRecues());
    }

    @GetMapping("/api/v1/annees/{anneeId}/dossiers-inscription")
    @PreAuthorize(Roles.CONSULTATION)
    public List<LigneDossierVue> dossiers(@PathVariable UUID anneeId,
            @RequestParam(defaultValue = "false") boolean incomplets) {
        return service.dossiers(anneeId, incomplets);
    }
}
