package bf.edutech.plateforme.adoption;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.adoption.AdoptionService.VuePlateforme;

/** Adoption de tous les établissements (super administrateur, règle de /api/v1/plateforme/**). */
@RestController
@RequestMapping("/api/v1/plateforme/adoption")
public class AdoptionPlateformeController {

    private final AdoptionService service;

    AdoptionPlateformeController(AdoptionService service) {
        this.service = service;
    }

    /** {@code ?direction=} : seulement les établissements qui dépendent de cette direction (à tout niveau). */
    @GetMapping
    public VuePlateforme plateforme(@RequestParam(defaultValue = "30") int jours,
            @RequestParam(required = false) java.util.UUID direction) {
        return service.plateforme(jours, direction);
    }

    /** Recalcule les mesures des derniers jours (après une correction de données, par exemple). */
    @PostMapping("/calcul")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public VuePlateforme recalculer(@RequestParam(defaultValue = "30") int jours,
            @RequestParam(required = false) java.util.UUID direction) {
        service.recalculer(jours);
        return service.plateforme(jours, direction);
    }
}
