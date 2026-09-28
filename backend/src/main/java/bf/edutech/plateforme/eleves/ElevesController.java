package bf.edutech.plateforme.eleves;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.eleves.Vues.EleveVue;
import bf.edutech.plateforme.eleves.Vues.EspaceParentVue;
import bf.edutech.plateforme.socle.persistance.PageResultat;

/** Dossiers des élèves et de leurs responsables. */
@RestController
public class ElevesController {

    public record DemandeResponsable(
            @NotBlank @Size(max = 80) String nom,
            @NotBlank @Size(max = 120) String prenoms,
            @NotBlank(message = "Le téléphone est obligatoire") String telephone,
            @NotNull(message = "Le lien avec l'élève est obligatoire") LienParente lien,
            @Size(max = 80) String profession,
            LangueSms langueSms,
            Boolean responsableLegal,
            Boolean contactPrioritaire) {

        DonneesResponsable donnees() {
            return new DonneesResponsable(nom, prenoms, telephone, lien, profession, langueSms, responsableLegal,
                    contactPrioritaire);
        }
    }

    public record DemandeEleve(
            @Size(max = 30) String matricule,
            @NotBlank @Size(max = 80) String nom,
            @NotBlank @Size(max = 120) String prenoms,
            @NotNull(message = "Le sexe est obligatoire") Sexe sexe,
            @NotNull(message = "La date de naissance est obligatoire") @Past LocalDate dateNaissance,
            @Size(max = 80) String lieuNaissance,
            String telephone,
            @Size(max = 40) String identifiantNational,
            @Size(max = 200) String adresse,
            @Valid List<DemandeResponsable> responsables) {

        DonneesEleve donnees() {
            return new DonneesEleve(matricule, nom, prenoms, sexe, dateNaissance, lieuNaissance, telephone,
                    identifiantNational, adresse);
        }
    }

    public record DemandeModificationResponsable(
            @NotBlank @Size(max = 80) String nom,
            @NotBlank @Size(max = 120) String prenoms,
            @NotBlank(message = "Le téléphone est obligatoire") String telephone,
            @Size(max = 80) String profession,
            LangueSms langueSms) {
    }

    private final ElevesService service;
    private final EspaceParentService espaceParent;

    ElevesController(ElevesService service, EspaceParentService espaceParent) {
        this.service = service;
        this.espaceParent = espaceParent;
    }

    /** Recherche par matricule, nom ou prénoms, page par page (20 élèves par défaut, 100 au plus). */
    @GetMapping("/api/v1/eleves")
    @PreAuthorize(Roles.CONSULTATION)
    public PageResultat<EleveVue> rechercher(@RequestParam(name = "q", required = false) String texte,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int taille) {
        return service.rechercher(texte, page, taille);
    }

    @PostMapping("/api/v1/eleves")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.GESTION)
    public DossierEleveVue creer(@Valid @RequestBody DemandeEleve d) {
        List<DonneesResponsable> responsables = d.responsables() == null ? List.of()
                : d.responsables().stream().map(DemandeResponsable::donnees).toList();
        return service.creer(d.donnees(), responsables);
    }

    @GetMapping("/api/v1/eleves/{id}")
    @PreAuthorize(Roles.CONSULTATION)
    public DossierEleveVue trouver(@PathVariable UUID id) {
        return service.trouver(id);
    }

    /** Modifie l'identité (les responsables se gèrent avec les routes dédiées). */
    @PutMapping("/api/v1/eleves/{id}")
    @PreAuthorize(Roles.GESTION)
    public DossierEleveVue modifier(@PathVariable UUID id, @Valid @RequestBody DemandeEleve d) {
        return service.modifier(id, d.donnees());
    }

    @PostMapping("/api/v1/eleves/{id}/responsables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.GESTION)
    public DossierEleveVue ajouterResponsable(@PathVariable UUID id, @Valid @RequestBody DemandeResponsable d) {
        return service.ajouterResponsable(id, d.donnees());
    }

    @DeleteMapping("/api/v1/eleves/{id}/responsables/{responsableId}")
    @PreAuthorize(Roles.GESTION)
    public DossierEleveVue retirerResponsable(@PathVariable UUID id, @PathVariable UUID responsableId) {
        return service.retirerResponsable(id, responsableId);
    }

    @PutMapping("/api/v1/responsables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Roles.GESTION)
    public void modifierResponsable(@PathVariable UUID id, @Valid @RequestBody DemandeModificationResponsable d) {
        service.modifierResponsable(id, d.nom(), d.prenoms(), d.telephone(), d.profession(), d.langueSms());
    }

    /**
     * Ouvre l'espace parent du responsable. Si son compte vient d'être créé, le
     * mot de passe temporaire est renvoyé une seule fois (à lui transmettre).
     */
    @PostMapping("/api/v1/responsables/{id}/espace-parent")
    @PreAuthorize(Roles.GESTION)
    public EspaceParentVue ouvrirEspaceParent(@PathVariable UUID id) {
        return espaceParent.ouvrir(id);
    }
}
