package bf.edutech.plateforme.viescolaire;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.viescolaire.VieScolaireService.ConvocationVue;
import bf.edutech.plateforme.viescolaire.VieScolaireService.HistoriqueVue;
import bf.edutech.plateforme.viescolaire.VieScolaireService.IncidentVue;
import bf.edutech.plateforme.viescolaire.VieScolaireService.LigneClasseVue;
import bf.edutech.plateforme.viescolaire.VieScolaireService.SaisieIncident;

/** Vie scolaire : incidents, convocations, historique de l'élève, synthèse de classe, espace parent. */
@RestController
public class VieScolaireController {

    static final String SAISIE = "hasAnyRole('SURVEILLANT','ENSEIGNANT','CENSEUR','ADMIN_ECOLE')";
    static final String ENCADREMENT = "hasAnyRole('SURVEILLANT','CENSEUR','ADMIN_ECOLE')";
    static final String CONSULTATION = "hasAnyRole('SURVEILLANT','CENSEUR','ADMIN_ECOLE','SECRETARIAT')";

    public record DemandeMotif(@NotBlank @Size(max = 300) String motif) {
    }

    public record DemandeConvocation(@NotNull LocalDateTime rendezVous, @NotBlank @Size(max = 300) String motif,
            UUID incidentId) {
    }

    public record DemandeCloture(@NotNull StatutConvocation statut, @Size(max = 500) String compteRendu) {
    }

    private final VieScolaireService service;

    VieScolaireController(VieScolaireService service) {
        this.service = service;
    }

    /**
     * {"type":"AVERTISSEMENT","motif":"Bavardages répétés"} ;
     * {"type":"RETARD","minutesRetard":20,"motif":"Arrivé à 7 h 20"} ;
     * {"type":"EXCLUSION_TEMPORAIRE","joursExclusion":3,"debutExclusion":"2026-11-03","motif":"Bagarre"}.
     */
    @PostMapping("/api/v1/inscriptions/{inscriptionId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SAISIE)
    public IncidentVue signaler(@PathVariable UUID inscriptionId, @RequestBody SaisieIncident s) {
        return service.signaler(inscriptionId, s);
    }

    @PostMapping("/api/v1/incidents/{id}/annulation")
    @PreAuthorize(SAISIE)
    public IncidentVue annuler(@PathVariable UUID id, @Valid @RequestBody DemandeMotif d) {
        return service.annulerIncident(id, d.motif());
    }

    /** {"rendezVous":"2026-11-05T10:00","motif":"Comportement en classe"}. */
    @PostMapping("/api/v1/inscriptions/{inscriptionId}/convocations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ENCADREMENT)
    public ConvocationVue convoquer(@PathVariable UUID inscriptionId, @Valid @RequestBody DemandeConvocation d) {
        return service.convoquer(inscriptionId, d.rendezVous(), d.motif(), d.incidentId());
    }

    /** {"statut":"HONOREE","compteRendu":"Père venu, engagement pris"}. */
    @PostMapping("/api/v1/convocations/{id}/cloture")
    @PreAuthorize(ENCADREMENT)
    public ConvocationVue cloturer(@PathVariable UUID id, @Valid @RequestBody DemandeCloture d) {
        return service.cloturer(id, d.statut(), d.compteRendu());
    }

    @GetMapping("/api/v1/convocations")
    @PreAuthorize(CONSULTATION)
    public List<ConvocationVue> agenda(@RequestParam LocalDate du, @RequestParam LocalDate au) {
        return service.agenda(du, au);
    }

    @GetMapping("/api/v1/inscriptions/{inscriptionId}/vie-scolaire")
    @PreAuthorize(CONSULTATION)
    public HistoriqueVue historique(@PathVariable UUID inscriptionId) {
        return service.historique(inscriptionId);
    }

    @GetMapping("/api/v1/eleves/{eleveId}/vie-scolaire")
    @PreAuthorize(CONSULTATION)
    public List<HistoriqueVue> historiqueEleve(@PathVariable UUID eleveId) {
        return service.historiqueEleve(eleveId);
    }

    @GetMapping("/api/v1/classes/{classeId}/vie-scolaire")
    @PreAuthorize(CONSULTATION)
    public List<LigneClasseVue> classe(@PathVariable UUID classeId, @RequestParam LocalDate du,
            @RequestParam LocalDate au) {
        return service.classe(classeId, du, au);
    }

    @GetMapping("/api/v1/espace-parent/enfants/{eleveId}/vie-scolaire")
    @PreAuthorize("hasRole('PARENT')")
    public List<HistoriqueVue> deMonEnfant(@PathVariable UUID eleveId) {
        return service.deMonEnfant(eleveId);
    }
}
