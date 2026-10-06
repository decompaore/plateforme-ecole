package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Gestion des membres de l'établissement actif (administrateur d'école). */
@RestController
@RequestMapping("/api/v1/membres")
@PreAuthorize("hasRole('ADMIN_ECOLE')")
public class MembresController {

    public record DemandeAjoutMembre(
            @NotBlank(message = "Le téléphone est obligatoire") String telephone,
            @NotBlank @Size(max = 80) String nom,
            @NotBlank @Size(max = 120) String prenoms,
            @NotNull(message = "Le rôle est obligatoire") Role role) {
    }

    private final MembresService service;

    MembresController(MembresService service) {
        this.service = service;
    }

    @GetMapping
    public List<MembreVue> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MembresService.ResultatAjout ajouter(@Valid @RequestBody DemandeAjoutMembre demande) {
        return service.ajouter(demande.telephone(), demande.nom(), demande.prenoms(), demande.role());
    }

    @PostMapping("/{id}/desactivation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desactiver(@PathVariable UUID id) {
        service.desactiver(id);
    }
}
