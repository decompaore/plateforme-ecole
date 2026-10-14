package bf.edutech.plateforme.moyens;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.moyens.DotationsService.DotationResume;
import bf.edutech.plateforme.moyens.DotationsService.DotationVue;
import bf.edutech.plateforme.moyens.DotationsService.Reception;
import bf.edutech.plateforme.moyens.EtatBesoinsService.DemandeTransmission;
import bf.edutech.plateforme.moyens.EtatBesoinsService.DonneesLigne;
import bf.edutech.plateforme.moyens.EtatBesoinsService.EtatVue;

/**
 * Côté établissement (v0.48) : état annuel des besoins (censeur, chef des travaux, proviseur) et dotations reçues
 * (accusé de réception par le proviseur ou l'intendant).
 */
@RestController
public class BesoinsEtablissementController {

    static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX','INTENDANT')";
    static final String SAISIE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX')";

    private final EtatBesoinsService etats;
    private final DotationsService dotations;

    BesoinsEtablissementController(EtatBesoinsService etats, DotationsService dotations) {
        this.etats = etats;
        this.dotations = dotations;
    }

    @GetMapping("/api/v1/etat-besoins")
    @PreAuthorize(LECTURE)
    public EtatVue etat(@RequestParam(required = false) UUID anneeId) {
        return etats.etat(anneeId);
    }

    @PostMapping("/api/v1/etat-besoins/{anneeId}/lignes")
    @PreAuthorize(SAISIE)
    public EtatVue ajouter(@PathVariable UUID anneeId, @RequestBody DonneesLigne donnees) {
        return etats.ajouter(anneeId, donnees);
    }

    @PutMapping("/api/v1/etat-besoins/lignes/{id}")
    @PreAuthorize(SAISIE)
    public EtatVue modifier(@PathVariable UUID id, @RequestBody DonneesLigne donnees) {
        return etats.modifier(id, donnees);
    }

    @DeleteMapping("/api/v1/etat-besoins/lignes/{id}")
    @PreAuthorize(SAISIE)
    public EtatVue supprimer(@PathVariable UUID id) {
        return etats.supprimer(id);
    }

    @PostMapping("/api/v1/etat-besoins/{anneeId}/transmettre")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public EtatVue transmettre(@PathVariable UUID anneeId, @RequestBody(required = false) DemandeTransmission demande) {
        return etats.transmettre(anneeId, demande);
    }

    // ------------------------------------------------------------------ dotations reçues

    @GetMapping("/api/v1/dotations-recues")
    @PreAuthorize(LECTURE)
    public List<DotationResume> dotations() {
        return dotations.recuesParEtablissement();
    }

    @GetMapping("/api/v1/dotations-recues/{id}")
    @PreAuthorize(LECTURE)
    public DotationVue dotation(@PathVariable UUID id) {
        return dotations.detailEtablissement(id);
    }

    @PostMapping("/api/v1/dotations-recues/{id}/accuser")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','INTENDANT')")
    public DotationVue accuser(@PathVariable UUID id, @RequestBody(required = false) Reception reception) {
        return dotations.accuserEtablissement(id, reception);
    }
}
