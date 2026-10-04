package bf.edutech.plateforme.plateforme;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.plateforme.EtablissementsService.EtablissementVue;
import bf.edutech.plateforme.plateforme.EtablissementsService.ResultatCreation;
import bf.edutech.plateforme.utilisateurs.ComptesService.CompteVue;
import bf.edutech.plateforme.utilisateurs.ComptesService.ResultatReinitialisation;

/**
 * Administration des établissements. Réservé au super administrateur
 * (règle déclarée dans la configuration de sécurité pour /api/v1/plateforme/**).
 */
@RestController
@RequestMapping("/api/v1/plateforme/etablissements")
public class EtablissementsController {

    public record DemandeCreation(
            @NotBlank @Size(min = 3, max = 30) String code,
            @NotBlank @Size(max = 200) String nom,
            @NotBlank String telephoneAdministrateur,
            @NotBlank @Size(max = 80) String nomAdministrateur,
            @NotBlank @Size(max = 120) String prenomsAdministrateur) {
    }

    public record DemandeStatut(@NotNull StatutTenant statut) {
    }

    private final EtablissementsService service;

    EtablissementsController(EtablissementsService service) {
        this.service = service;
    }

    @GetMapping
    public List<EtablissementVue> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResultatCreation creer(@Valid @RequestBody DemandeCreation d) {
        return service.creer(d.code(), d.nom(), d.telephoneAdministrateur(), d.nomAdministrateur(),
                d.prenomsAdministrateur());
    }

    @GetMapping("/{id}/administrateurs")
    public List<CompteVue> administrateurs(@PathVariable UUID id) {
        return service.administrateurs(id);
    }

    /** Mot de passe oublié par l'administrateur d'un établissement : nouveau mot de passe provisoire. */
    @PostMapping("/{id}/administrateurs/{utilisateurId}/reinitialisation")
    public ResultatReinitialisation reinitialiserAdministrateur(@PathVariable UUID id, @PathVariable UUID utilisateurId) {
        return service.reinitialiserAdministrateur(id, utilisateurId);
    }

    @PatchMapping("/{id}/statut")
    public EtablissementVue changerStatut(@PathVariable UUID id, @Valid @RequestBody DemandeStatut d) {
        return service.changerStatut(id, d.statut());
    }
}
