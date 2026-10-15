package bf.edutech.plateforme.progression;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.progression.InterpellationsService.DemandeCloture;
import bf.edutech.plateforme.progression.InterpellationsService.DemandeReponse;
import bf.edutech.plateforme.progression.InterpellationsService.DonneesInterpellation;
import bf.edutech.plateforme.progression.InterpellationsService.InterpellationVue;
import bf.edutech.plateforme.progression.InterpellationsService.Proposition;

/**
 * Interpellation des enseignants en retard sur leur progression (v0.48.1) : rappel ou demande d'explication écrite
 * du censeur, du chef des travaux ou du chef d'établissement ; réponse de l'enseignant ; suite donnée.
 */
@RestController
public class InterpellationsController {

    static final String DIRECTION = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX')";
    static final String TOUS = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX','ENSEIGNANT')";

    private final InterpellationsService service;

    InterpellationsController(InterpellationsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/classes/{classeId}/matieres/{matiereId}/interpellations/proposition")
    @PreAuthorize(DIRECTION)
    public Proposition proposition(@PathVariable UUID classeId, @PathVariable UUID matiereId) {
        return service.proposition(classeId, matiereId);
    }

    @PostMapping("/api/v1/classes/{classeId}/matieres/{matiereId}/interpellations")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    public InterpellationVue emettre(@PathVariable UUID classeId, @PathVariable UUID matiereId,
            @RequestBody DonneesInterpellation donnees) {
        return service.emettre(classeId, matiereId, donnees);
    }

    @GetMapping("/api/v1/interpellations")
    @PreAuthorize(DIRECTION)
    public List<InterpellationVue> lister(@RequestParam(required = false) UUID anneeId,
            @RequestParam(required = false) UUID classeId, @RequestParam(required = false) UUID matiereId) {
        return service.lister(anneeId, classeId, matiereId);
    }

    @GetMapping("/api/v1/espace-enseignant/interpellations")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public List<InterpellationVue> miennes() {
        return service.miennes();
    }

    @GetMapping("/api/v1/interpellations/{id}")
    @PreAuthorize(TOUS)
    public InterpellationVue consulter(@PathVariable UUID id) {
        return service.consulter(id);
    }

    @PostMapping("/api/v1/interpellations/{id}/reponse")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public InterpellationVue repondre(@PathVariable UUID id, @RequestBody DemandeReponse demande) {
        return service.repondre(id, demande);
    }

    @PostMapping("/api/v1/interpellations/{id}/cloture")
    @PreAuthorize(DIRECTION)
    public InterpellationVue clore(@PathVariable UUID id, @RequestBody(required = false) DemandeCloture demande) {
        return service.clore(id, demande);
    }

    @GetMapping("/api/v1/interpellations/{id}/lettre")
    @PreAuthorize(TOUS)
    public ResponseEntity<byte[]> lettre(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("interpellation-" + id + ".pdf", StandardCharsets.UTF_8).build().toString())
                .body(service.lettre(id));
    }
}
