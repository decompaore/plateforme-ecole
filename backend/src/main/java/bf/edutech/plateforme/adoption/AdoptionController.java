package bf.edutech.plateforme.adoption;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.adoption.AdoptionService.VueEtablissement;

/** Utilisation de l'application dans l'établissement actif (administrateur). */
@RestController
@RequestMapping("/api/v1/adoption")
@PreAuthorize("hasRole('ADMIN_ECOLE')")
public class AdoptionController {

    private final AdoptionService service;

    AdoptionController(AdoptionService service) {
        this.service = service;
    }

    @GetMapping
    public VueEtablissement etablissement(@RequestParam(defaultValue = "30") int jours) {
        return service.etablissement(jours);
    }
}
