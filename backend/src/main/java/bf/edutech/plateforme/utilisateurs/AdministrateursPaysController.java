package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.utilisateurs.AdministrateursPaysService.AdministrateurPaysVue;
import bf.edutech.plateforme.utilisateurs.AdministrateursPaysService.ResultatNomination;
import bf.edutech.plateforme.utilisateurs.ComptesService.ResultatReinitialisation;

/** Administrateurs pays : nommés, dépannés et retirés par le super administrateur. */
@RestController
@RequestMapping("/api/v1/plateforme/territoire/pays/{paysId}/administrateurs")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdministrateursPaysController {

    public record DemandeNomination(@NotBlank String telephone, @NotBlank @Size(max = 80) String nom,
            @NotBlank @Size(max = 120) String prenoms) {
    }

    private final AdministrateursPaysService service;

    AdministrateursPaysController(AdministrateursPaysService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdministrateurPaysVue> lister(@PathVariable UUID paysId) {
        return service.lister(paysId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResultatNomination nommer(@PathVariable UUID paysId, @Valid @RequestBody DemandeNomination d) {
        return service.nommer(paysId, d.telephone(), d.nom(), d.prenoms());
    }

    @DeleteMapping("/{utilisateurId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirer(@PathVariable UUID paysId, @PathVariable UUID utilisateurId) {
        service.retirer(paysId, utilisateurId);
    }

    @PostMapping("/{utilisateurId}/reinitialisation")
    public ResultatReinitialisation reinitialiser(@PathVariable UUID paysId, @PathVariable UUID utilisateurId) {
        return service.reinitialiser(paysId, utilisateurId);
    }
}
