package bf.edutech.plateforme.enseignants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import bf.edutech.plateforme.enseignants.EnseignantsService.DonneesEngagement;
import bf.edutech.plateforme.enseignants.Vues.EnseignantVue;
import bf.edutech.plateforme.enseignants.Vues.FicheEnseignantVue;
import bf.edutech.plateforme.enseignants.Vues.ResultatEngagement;
import bf.edutech.plateforme.enseignants.Vues.ResultatRecherche;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.socle.referentiel.Sexe;

/** Enseignants de l'établissement : engagements et affectations. */
@RestController
public class EnseignantsController {

    static final String ADMINISTRATION = "hasRole('ADMIN_ECOLE')";
    static final String AFFECTATION = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";
    static final String CONSULTATION = "hasAnyRole('ADMIN_ECOLE','CENSEUR','SECRETARIAT','INTENDANT','SURVEILLANT')";

    public record DemandeRecherche(String telephone, @Size(max = 30) String matriculeFp) {
    }

    public record DemandeEngagement(
            @NotBlank(message = "Le téléphone est obligatoire") String telephone,
            @Size(max = 30) String matriculeFp,
            @Size(max = 80) String nom,
            @Size(max = 120) String prenoms,
            Sexe sexe,
            @Size(max = 80) String specialite,
            @NotNull(message = "Le type est obligatoire : TITULAIRE ou VACATAIRE") TypeEngagement type,
            @NotNull(message = "La date de début est obligatoire") LocalDate debut,
            LocalDate fin,
            @DecimalMin("1") @Digits(integer = 8, fraction = 0) BigDecimal tauxHoraire) {
    }

    public record DemandeIdentite(
            @Size(max = 30) String matriculeFp,
            @NotBlank @Size(max = 80) String nom,
            @NotBlank @Size(max = 120) String prenoms,
            @NotNull Sexe sexe,
            @Size(max = 80) String specialite) {
    }

    public record DemandeFin(@NotNull LocalDate date, @Size(max = 200) String motif) {
    }

    public record DemandeAffectation(@NotNull UUID engagementId) {
    }

    private final EnseignantsService service;

    EnseignantsController(EnseignantsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/enseignants")
    @PreAuthorize(CONSULTATION)
    public List<EnseignantVue> lister(@RequestParam(required = false) StatutEngagement statut) {
        return service.lister(statut);
    }

    /** Recherche par téléphone ou matricule : répond seulement « trouvé » ou « non trouvé ». */
    @PostMapping("/api/v1/enseignants/recherche")
    @PreAuthorize(ADMINISTRATION)
    public ResultatRecherche rechercher(@Valid @RequestBody DemandeRecherche d) {
        return new ResultatRecherche(service.existe(d.telephone(), d.matriculeFp()));
    }

    /**
     * Engage un enseignant. Inconnu de la plateforme : identité et compte créés,
     * engagement actif (mot de passe temporaire renvoyé une fois). Déjà connu :
     * invitation qu'il accepte depuis son compte.
     */
    @PostMapping("/api/v1/enseignants")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADMINISTRATION)
    public ResultatEngagement engager(@Valid @RequestBody DemandeEngagement d) {
        return service.engager(new DonneesEngagement(d.telephone(), d.matriculeFp(), d.nom(), d.prenoms(), d.sexe(),
                d.specialite(), d.type(), d.debut(), d.fin(), d.tauxHoraire()));
    }

    /** Fiche : engagement, matières assurées pendant l'année (active par défaut), charge hebdomadaire. */
    @GetMapping("/api/v1/engagements/{id}")
    @PreAuthorize(CONSULTATION)
    public FicheEnseignantVue fiche(@PathVariable UUID id, @RequestParam(required = false) UUID anneeId) {
        return service.fiche(id, anneeId);
    }

    @PutMapping("/api/v1/engagements/{id}/identite")
    @PreAuthorize(ADMINISTRATION)
    public EnseignantVue modifierIdentite(@PathVariable UUID id, @Valid @RequestBody DemandeIdentite d) {
        return service.modifierIdentite(id, d.matriculeFp(), d.nom(), d.prenoms(), d.sexe(), d.specialite());
    }

    /**
     * Fin d'engagement. {@code date} est le dernier jour de travail : passée, la fin est
     * immédiate ; aujourd'hui ou plus tard, elle est programmée (l'engagement reste actif
     * jusque-là, puis est clos automatiquement le lendemain).
     */
    @PostMapping("/api/v1/engagements/{id}/fin")
    @PreAuthorize(ADMINISTRATION)
    public EnseignantVue terminer(@PathVariable UUID id, @Valid @RequestBody DemandeFin d) {
        return service.terminer(id, d.date(), d.motif());
    }

    /** Annule une fin d'engagement programmée. */
    @DeleteMapping("/api/v1/engagements/{id}/fin")
    @PreAuthorize(ADMINISTRATION)
    public EnseignantVue annulerFin(@PathVariable UUID id) {
        return service.annulerFin(id);
    }

    /** Annule une invitation encore en attente. */
    @DeleteMapping("/api/v1/engagements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ADMINISTRATION)
    public void annulerInvitation(@PathVariable UUID id) {
        service.annulerInvitation(id);
    }

    @PutMapping("/api/v1/classes/{classeId}/matieres/{matiereId}/enseignant")
    @PreAuthorize(AFFECTATION)
    public MatiereDeClasseVue affecter(@PathVariable UUID classeId, @PathVariable UUID matiereId,
            @Valid @RequestBody DemandeAffectation d) {
        return service.affecter(classeId, matiereId, d.engagementId());
    }

    @DeleteMapping("/api/v1/classes/{classeId}/matieres/{matiereId}/enseignant")
    @PreAuthorize(AFFECTATION)
    public MatiereDeClasseVue retirerAffectation(@PathVariable UUID classeId, @PathVariable UUID matiereId) {
        return service.retirerAffectation(classeId, matiereId);
    }
}
