package bf.edutech.plateforme.eleves;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.eleves.Vues.EnfantVue;

/** Espace parent : ce que voit le parent connecté. */
@RestController
@PreAuthorize("hasRole('PARENT')")
public class EspaceParentController {

    private final EspaceParentService service;

    EspaceParentController(EspaceParentService service) {
        this.service = service;
    }

    /** Enfants du parent connecté dans l'établissement choisi à la connexion. */
    @GetMapping("/api/v1/espace-parent/enfants")
    public List<EnfantVue> mesEnfants() {
        return service.mesEnfants();
    }
}
