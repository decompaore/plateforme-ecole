package bf.edutech.plateforme.pedagogie;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Profils pédagogiques. Lecture : tout membre ; modification : administrateur d'école. */
@RestController
@RequestMapping("/api/v1/profils")
public class ProfilsController {

    public record DemandeProfil(
            @NotBlank @Size(max = 120) String libelle,
            @NotNull OrdreEnseignement ordre,
            @NotNull CodeModele modele,
            @NotNull Decoupage decoupage,
            @NotBlank @Size(max = 40) String gabaritDocument,
            @DecimalMin("0") @DecimalMax("20") BigDecimal seuilAdmission,
            @Min(0) @Max(3) Short decimalesMoyenne,
            @DecimalMin("0") @DecimalMax("20") BigDecimal noteEliminatoire,
            @DecimalMin("0") @DecimalMax("100") BigDecimal seuilMaitrise,
            @Valid VocabulaireDemande vocabulaire) {

        ProfilsService.DonneesProfil versDonnees() {
            return new ProfilsService.DonneesProfil(libelle, ordre, modele, decoupage, gabaritDocument, seuilAdmission,
                    decimalesMoyenne, noteEliminatoire, seuilMaitrise,
                    vocabulaire == null ? null
                            : new ProfilVue.Vocabulaire(vocabulaire.apprenant(), vocabulaire.groupe(),
                                    vocabulaire.matiere(), vocabulaire.enseignant()));
        }
    }

    public record VocabulaireDemande(
            @NotBlank @Size(max = 30) String apprenant,
            @NotBlank @Size(max = 30) String groupe,
            @NotBlank @Size(max = 30) String matiere,
            @NotBlank @Size(max = 30) String enseignant) {
    }

    public record DemandeCreationProfil(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,30}$") String code,
            @NotNull @Valid DemandeProfil profil) {
    }

    private final ProfilsService service;

    ProfilsController(ProfilsService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProfilVue> lister() {
        return service.lister();
    }

    @GetMapping("/{id}")
    public ProfilVue trouver(@PathVariable UUID id) {
        return service.trouver(id);
    }

    @PostMapping("/initialisation")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public List<ProfilVue> initialiser() {
        return service.initialiserProfilsTypes();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ProfilVue creer(@Valid @RequestBody DemandeCreationProfil demande) {
        return service.creer(demande.code(), demande.profil().versDonnees());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ProfilVue modifier(@PathVariable UUID id, @Valid @RequestBody DemandeProfil demande) {
        return service.modifier(id, demande.versDonnees());
    }

    @PatchMapping("/{id}/activation")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ProfilVue changerActivation(@PathVariable UUID id, @RequestParam boolean actif) {
        return service.changerActivation(id, actif);
    }
}
