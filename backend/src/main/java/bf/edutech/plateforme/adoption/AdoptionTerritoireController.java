package bf.edutech.plateforme.adoption;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.adoption.AdoptionService.VuePlateforme;

/**
 * Adoption vue du territoire (v0.39), règle de /api/v1/pilotage/** : comptes de direction (leur
 * direction et ce qui en dépend), administrateurs pays (leur pays), super administrateur (tout).
 * Uniquement des nombres, en lecture.
 */
@RestController
public class AdoptionTerritoireController {

    private final AdoptionService service;

    AdoptionTerritoireController(AdoptionService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/pilotage/adoption")
    public VuePlateforme territoire(@RequestParam(defaultValue = "30") int jours,
            @RequestParam(required = false) UUID direction) {
        return service.plateforme(jours, direction);
    }
}
