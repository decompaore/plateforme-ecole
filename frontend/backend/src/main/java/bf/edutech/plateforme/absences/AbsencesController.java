package bf.edutech.plateforme.absences;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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

import bf.edutech.plateforme.absences.Vues.AbsenceVue;
import bf.edutech.plateforme.absences.Vues.AbsencesParMatiereVue;
import bf.edutech.plateforme.absences.Vues.AccuseAppel;
import bf.edutech.plateforme.absences.Vues.AppelVue;
import bf.edutech.plateforme.absences.Vues.DonneesAppel;
import bf.edutech.plateforme.absences.Vues.EleveDuJourVue;
import bf.edutech.plateforme.absences.Vues.JustificatifVue;
import bf.edutech.plateforme.absences.Vues.Marque;
import bf.edutech.plateforme.absences.Vues.SyntheseEleveVue;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;

/** Appels (synchronisation depuis les appareils), absences, justificatifs, espace parent. */
@RestController
public class AbsencesController {

    private static final ZoneId OUAGADOUGOU = ZoneId.of("Africa/Ouagadougou");

    static final String APPEL = "hasAnyRole('ENSEIGNANT','SURVEILLANT','CENSEUR','ADMIN_ECOLE')";
    static final String CONSULTATION =
            "hasAnyRole('ENSEIGNANT','SURVEILLANT','CENSEUR','ADMIN_ECOLE','SECRETARIAT','INTENDANT')";
    static final String PERSONNEL = "hasAnyRole('SURVEILLANT','CENSEUR','ADMIN_ECOLE','SECRETARIAT','INTENDANT')";
    static final String JUSTIFICATION = "hasAnyRole('SURVEILLANT','CENSEUR','ADMIN_ECOLE','SECRETARIAT')";

    public record LotAppels(@NotEmpty @Size(max = AppelsService.LOT_MAX) List<@NotNull DonneesAppel> appels) {
    }

    public record DemandeModificationAppel(List<@NotNull Marque> marques) {
    }

    public record DemandeJustificatif(@NotNull LocalDate du, @NotNull LocalDate au, @NotNull TypeJustificatif type,
            @Size(max = 200) String motif) {
    }

    private final AppelsService appels;
    private final AbsencesService absences;
    private final EspaceParentService espaceParent;

    AbsencesController(AppelsService appels, AbsencesService absences, EspaceParentService espaceParent) {
        this.appels = appels;
        this.absences = absences;
        this.espaceParent = espaceParent;
    }

    /**
     * Synchronisation depuis l'appareil : un accusé par appel (ENREGISTRE, DEJA_RECU,
     * MODIFIE ou REFUSE). Renvoyer un lot déjà reçu est sans effet.
     */
    @PostMapping("/api/v1/appels/lot")
    @PreAuthorize(APPEL)
    public List<AccuseAppel> synchroniser(@Valid @RequestBody LotAppels lot) {
        return appels.synchroniser(lot.appels());
    }

    /** Corrige un appel (l'enseignant le jour même, la vie scolaire à tout moment). */
    @PutMapping("/api/v1/appels/{id}")
    @PreAuthorize(APPEL)
    public AccuseAppel modifier(@PathVariable UUID id, @Valid @RequestBody DemandeModificationAppel d) {
        return appels.modifier(id, d.marques() != null ? d.marques() : List.of());
    }

    @GetMapping("/api/v1/appels/{id}")
    @PreAuthorize(CONSULTATION)
    public AppelVue appel(@PathVariable UUID id) {
        return appels.trouver(id);
    }

    @GetMapping("/api/v1/classes/{id}/appels")
    @PreAuthorize(CONSULTATION)
    public List<AppelVue> appelsDuJour(@PathVariable UUID id, @RequestParam LocalDate date) {
        return appels.duJour(id, date);
    }

    /** Synthèse par élève (absences, justifiées, retards, heures) sur une période. */
    @GetMapping("/api/v1/classes/{id}/absences/synthese")
    @PreAuthorize(CONSULTATION)
    public List<SyntheseEleveVue> synthese(@PathVariable UUID id, @RequestParam(required = false) LocalDate du,
            @RequestParam(required = false) LocalDate au) {
        return absences.syntheseClasse(id, du, au);
    }

    /** Absences de la classe par discipline (toute l'année si les dates sont omises). */
    @GetMapping("/api/v1/classes/{id}/absences/matieres")
    @PreAuthorize(CONSULTATION)
    public List<AbsencesParMatiereVue> parMatiere(@PathVariable UUID id, @RequestParam(required = false) LocalDate du,
            @RequestParam(required = false) LocalDate au) {
        return absences.parMatiere(id, du, au);
    }

    /** Élèves absents ou en retard dans l'établissement un jour donné (aujourd'hui par défaut). */
    @GetMapping("/api/v1/absences/jour")
    @PreAuthorize(PERSONNEL)
    public List<EleveDuJourVue> duJour(@RequestParam(required = false) LocalDate date) {
        return absences.duJour(date != null ? date : LocalDate.now(OUAGADOUGOU));
    }

    @GetMapping("/api/v1/inscriptions/{id}/absences")
    @PreAuthorize(PERSONNEL)
    public List<AbsenceVue> absencesDeLInscription(@PathVariable UUID id,
            @RequestParam(required = false) LocalDate du, @RequestParam(required = false) LocalDate au) {
        return absences.deLInscription(id, du, au);
    }

    @GetMapping("/api/v1/inscriptions/{id}/justificatifs")
    @PreAuthorize(PERSONNEL)
    public List<JustificatifVue> justificatifs(@PathVariable UUID id) {
        return absences.justificatifs(id);
    }

    @PostMapping("/api/v1/inscriptions/{id}/justificatifs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(JUSTIFICATION)
    public JustificatifVue justifier(@PathVariable UUID id, @Valid @RequestBody DemandeJustificatif d) {
        return absences.justifier(id, d.du(), d.au(), d.type(), d.motif());
    }

    @DeleteMapping("/api/v1/justificatifs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(JUSTIFICATION)
    public void supprimerJustificatif(@PathVariable UUID id) {
        absences.supprimerJustificatif(id);
    }

    /** Absences d'un enfant, vues par son parent. */
    @GetMapping("/api/v1/espace-parent/enfants/{eleveId}/absences")
    @PreAuthorize("hasRole('PARENT')")
    public List<AbsenceVue> absencesDeMonEnfant(@PathVariable UUID eleveId) {
        if (!espaceParent.estMonEnfant(eleveId)) {
            throw new AccesRefuseException("Cet élève n'est pas rattaché à votre compte");
        }
        return absences.deLEleve(eleveId);
    }
}
