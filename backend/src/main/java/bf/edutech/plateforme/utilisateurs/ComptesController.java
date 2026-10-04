package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.utilisateurs.ComptesService.CompteVue;
import bf.edutech.plateforme.utilisateurs.ComptesService.ResultatReinitialisation;

/** Contrôle des comptes de l'établissement actif (administrateur d'école). */
@RestController
@RequestMapping("/api/v1/comptes")
@PreAuthorize("hasRole('ADMIN_ECOLE')")
public class ComptesController {

    private final ComptesService service;

    ComptesController(ComptesService service) {
        this.service = service;
    }

    @GetMapping
    public List<CompteVue> lister() {
        return service.lister();
    }

    /** Nouveau mot de passe provisoire, renvoyé une seule fois ; ferme les sessions du compte. */
    @PostMapping("/{utilisateurId}/reinitialisation")
    public ResultatReinitialisation reinitialiser(@PathVariable UUID utilisateurId) {
        return service.reinitialiser(utilisateurId);
    }

    @PostMapping("/{utilisateurId}/deverrouillage")
    public CompteVue deverrouiller(@PathVariable UUID utilisateurId) {
        return service.deverrouiller(utilisateurId);
    }
}
