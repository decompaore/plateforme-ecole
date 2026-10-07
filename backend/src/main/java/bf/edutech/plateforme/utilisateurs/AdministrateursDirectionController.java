package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.utilisateurs.AdministrateursDirectionService.CompteDirectionVue;
import bf.edutech.plateforme.utilisateurs.AdministrateursDirectionService.ResultatNominationDirection;
import bf.edutech.plateforme.utilisateurs.ComptesService.ResultatReinitialisation;

/**
 * Comptes des directions : nommés, dépannés et retirés par l'administrateur du pays ou par le
 * super administrateur (la portée pays est vérifiée par le service).
 */
@RestController
@RequestMapping("/api/v1/plateforme/territoire/directions/{directionId}/comptes")
public class AdministrateursDirectionController {

    public record DemandeCompteDirection(@NotBlank String telephone, @NotBlank @Size(max = 80) String nom,
            @NotBlank @Size(max = 120) String prenoms) {
    }

    private final AdministrateursDirectionService service;

    AdministrateursDirectionController(AdministrateursDirectionService service) {
        this.service = service;
    }

    @GetMapping
    public List<CompteDirectionVue> lister(@PathVariable UUID directionId) {
        return service.lister(directionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResultatNominationDirection nommer(@PathVariable UUID directionId, @Valid @RequestBody DemandeCompteDirection d) {
        return service.nommer(directionId, d.telephone(), d.nom(), d.prenoms());
    }

    @DeleteMapping("/{utilisateurId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirer(@PathVariable UUID directionId, @PathVariable UUID utilisateurId) {
        service.retirer(directionId, utilisateurId);
    }

    @PostMapping("/{utilisateurId}/reinitialisation")
    public ResultatReinitialisation reinitialiser(@PathVariable UUID directionId, @PathVariable UUID utilisateurId) {
        return service.reinitialiser(directionId, utilisateurId);
    }
}
