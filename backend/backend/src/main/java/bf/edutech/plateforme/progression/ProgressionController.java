package bf.edutech.plateforme.progression;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.progression.Vues.DemandeVisa;
import bf.edutech.plateforme.progression.Vues.DonneesFiche;
import bf.edutech.plateforme.progression.Vues.FicheVue;
import bf.edutech.plateforme.progression.Vues.SuiviProgressionVue;

/** Fiches de progression : préparation par l'enseignant, visa et suivi par la direction. */
@RestController
public class ProgressionController {

    static final String SUPERVISION = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX')";
    static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX','ENSEIGNANT')";
    private static final String BASE = "/api/v1/classes/{classeId}/matieres/{matiereId}/progression";

    private final ProgressionService service;

    ProgressionController(ProgressionService service) {
        this.service = service;
    }

    @GetMapping(BASE)
    @PreAuthorize(LECTURE)
    public FicheVue fiche(@PathVariable UUID classeId, @PathVariable UUID matiereId) {
        return service.fiche(classeId, matiereId);
    }

    @PutMapping(BASE)
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public FicheVue enregistrer(@PathVariable UUID classeId, @PathVariable UUID matiereId, @RequestBody DonneesFiche d) {
        return service.enregistrer(classeId, matiereId, d);
    }

    @PostMapping(BASE + "/soumission")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public FicheVue soumettre(@PathVariable UUID classeId, @PathVariable UUID matiereId) {
        return service.soumettre(classeId, matiereId);
    }

    @PostMapping(BASE + "/visa")
    @PreAuthorize(SUPERVISION)
    public FicheVue viser(@PathVariable UUID classeId, @PathVariable UUID matiereId, @RequestBody DemandeVisa d) {
        return service.viser(classeId, matiereId, d);
    }

    @GetMapping("/api/v1/annees/{anneeId}/progressions")
    @PreAuthorize(SUPERVISION)
    public List<SuiviProgressionVue> suivi(@PathVariable UUID anneeId) {
        return service.suivi(anneeId);
    }

    @GetMapping("/api/v1/espace-enseignant/progressions")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public List<SuiviProgressionVue> mesFiches() {
        return service.mesFiches();
    }
}
