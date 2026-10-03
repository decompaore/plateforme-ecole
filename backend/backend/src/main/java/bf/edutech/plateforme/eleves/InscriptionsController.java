package bf.edutech.plateforme.eleves;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.eleves.Vues.ResultatReinscription;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Inscriptions, listes de classe et réinscriptions. */
@RestController
public class InscriptionsController {

    public record DemandeInscription(
            @NotNull UUID eleveId,
            @NotNull UUID classeId,
            Boolean redoublant,
            StatutBourse statutBourse) {

        public DemandeInscription {
            redoublant = Boolean.TRUE.equals(redoublant); // facultatif : non redoublant
        }
    }

    public record DemandeChangementClasse(@NotNull UUID classeId) {
    }

    public record DemandeBourse(@NotNull StatutBourse statutBourse) {
    }

    public record DemandeSortie(
            @NotNull(message = "Statut de sortie obligatoire : TRANSFEREE ou ABANDON") StatutInscription statut,
            @NotNull LocalDate date,
            @Size(max = 200) String motif) {
    }

    public record DemandeReinscription(
            @NotEmpty @Size(max = 500) List<@NotNull UUID> inscriptions,
            Boolean redoublant,
            Boolean conserverStatutBourse) {

        public DemandeReinscription {
            redoublant = Boolean.TRUE.equals(redoublant);
            conserverStatutBourse = Boolean.TRUE.equals(conserverStatutBourse);
        }
    }

    private final InscriptionsService service;
    private final EnseignantsService enseignants;

    InscriptionsController(InscriptionsService service, EnseignantsService enseignants) {
        this.service = service;
        this.enseignants = enseignants;
    }

    @PostMapping("/api/v1/inscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.GESTION)
    public InscriptionVue inscrire(@Valid @RequestBody DemandeInscription d) {
        return service.inscrire(d.eleveId(), d.classeId(), d.redoublant(), d.statutBourse());
    }

    @GetMapping("/api/v1/inscriptions/{id}")
    @PreAuthorize(Roles.CONSULTATION)
    public InscriptionVue trouver(@PathVariable UUID id) {
        return service.trouver(id);
    }

    @PatchMapping("/api/v1/inscriptions/{id}/classe")
    @PreAuthorize(Roles.GESTION)
    public InscriptionVue changerClasse(@PathVariable UUID id, @Valid @RequestBody DemandeChangementClasse d) {
        return service.changerClasse(id, d.classeId());
    }

    @PatchMapping("/api/v1/inscriptions/{id}/bourse")
    @PreAuthorize(Roles.BOURSE)
    public InscriptionVue changerStatutBourse(@PathVariable UUID id, @Valid @RequestBody DemandeBourse d) {
        return service.changerStatutBourse(id, d.statutBourse());
    }

    /** Sortie en cours d'année (transfert ou abandon). */
    @PostMapping("/api/v1/inscriptions/{id}/sortie")
    @PreAuthorize(Roles.GESTION)
    public InscriptionVue sortir(@PathVariable UUID id, @Valid @RequestBody DemandeSortie d) {
        return service.sortir(id, d.statut(), d.date(), d.motif());
    }

    /** Liste de la classe par ordre alphabétique ; {@code sorties=true} inclut les élèves partis. */
    @GetMapping("/api/v1/classes/{id}/inscriptions")
    @PreAuthorize(Roles.LISTES_DE_CLASSE)
    public List<InscriptionVue> listerParClasse(@PathVariable UUID id,
            @RequestParam(name = "sorties", defaultValue = "false") boolean avecSorties) {
        // Un enseignant ne consulte que les classes où il a au moins une matière
        if (!UtilisateurConnecte.aUnRole(Roles.PERSONNEL) && !enseignants.enseigneDans(UtilisateurConnecte.id(), id)) {
            throw new AccesRefuseException("Vous n'enseignez pas dans cette classe");
        }
        return service.listerParClasse(id, avecSorties);
    }

    /** Réinscrit dans cette classe des élèves inscrits une année précédente (identifiants d'inscription). */
    @PostMapping("/api/v1/classes/{id}/reinscriptions")
    @PreAuthorize(Roles.GESTION)
    public ResultatReinscription reinscrire(@PathVariable UUID id, @Valid @RequestBody DemandeReinscription d) {
        return service.reinscrire(id, d.inscriptions(), d.redoublant(), d.conserverStatutBourse());
    }
}
