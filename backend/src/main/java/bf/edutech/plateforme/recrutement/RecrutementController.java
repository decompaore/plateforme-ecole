package bf.edutech.plateforme.recrutement;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import bf.edutech.plateforme.recrutement.Vues.CandidatureVue;
import bf.edutech.plateforme.recrutement.Vues.ClassementOffre;
import bf.edutech.plateforme.recrutement.Vues.CommissionResume;
import bf.edutech.plateforme.recrutement.Vues.CommissionVue;
import bf.edutech.plateforme.recrutement.Vues.DemandeDeliberation;
import bf.edutech.plateforme.recrutement.Vues.DemandeDesistement;
import bf.edutech.plateforme.recrutement.Vues.DemandeInscription;
import bf.edutech.plateforme.recrutement.Vues.DemandeMembres;
import bf.edutech.plateforme.recrutement.Vues.DemandeOffres;
import bf.edutech.plateforme.recrutement.Vues.DemandeRecevabilite;
import bf.edutech.plateforme.recrutement.Vues.DonneesCandidature;
import bf.edutech.plateforme.recrutement.Vues.DonneesCommission;
import bf.edutech.plateforme.recrutement.Vues.ResultatDesistement;
import bf.edutech.plateforme.socle.export.ExportTableaux;

/**
 * Commission de recrutement (v0.49). Les droits fins (président, vice-président, saisie, intendance, membres
 * désignés) sont contrôlés par le service.
 */
@RestController
public class RecrutementController {

    static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','SECRETARIAT','SURVEILLANT','INTENDANT','ENSEIGNANT','CHEF_TRAVAUX')";
    static final String PRESIDENT = "hasRole('ADMIN_ECOLE')";
    static final String PREPARATION = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";
    static final String SAISIE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','SECRETARIAT','SURVEILLANT')";
    static final String INSCRIPTION = "hasAnyRole('ADMIN_ECOLE','INTENDANT')";

    private final RecrutementService service;
    private final DocumentsRecrutement documents;

    RecrutementController(RecrutementService service, DocumentsRecrutement documents) {
        this.service = service;
        this.documents = documents;
    }

    // ------------------------------------------------------------------ commissions

    @GetMapping("/api/v1/annees/{anneeId}/commissions-recrutement")
    @PreAuthorize(LECTURE)
    public List<CommissionResume> lister(@PathVariable UUID anneeId) {
        return service.lister(anneeId);
    }

    @PostMapping("/api/v1/annees/{anneeId}/commissions-recrutement")
    @PreAuthorize(PRESIDENT)
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionVue creer(@PathVariable UUID anneeId, @RequestBody DonneesCommission donnees) {
        return service.creer(anneeId, donnees);
    }

    @GetMapping("/api/v1/commissions-recrutement/{id}")
    @PreAuthorize(LECTURE)
    public CommissionVue commission(@PathVariable UUID id) {
        return service.commission(id);
    }

    @PutMapping("/api/v1/commissions-recrutement/{id}")
    @PreAuthorize(PRESIDENT)
    public CommissionVue modifier(@PathVariable UUID id, @RequestBody DonneesCommission donnees) {
        return service.modifier(id, donnees);
    }

    @PutMapping("/api/v1/commissions-recrutement/{id}/membres")
    @PreAuthorize(PRESIDENT)
    public CommissionVue membres(@PathVariable UUID id, @RequestBody DemandeMembres demande) {
        return service.definirMembres(id, demande);
    }

    @PutMapping("/api/v1/commissions-recrutement/{id}/offres")
    @PreAuthorize(PREPARATION)
    public CommissionVue offres(@PathVariable UUID id, @RequestBody DemandeOffres demande) {
        return service.definirOffres(id, demande);
    }

    @PostMapping("/api/v1/commissions-recrutement/{id}/offres/actualisation")
    @PreAuthorize(PREPARATION)
    public CommissionVue actualiserOffres(@PathVariable UUID id) {
        return service.actualiserOffres(id);
    }

    @GetMapping("/api/v1/commissions-recrutement/{id}/classement")
    @PreAuthorize(LECTURE)
    public List<ClassementOffre> classement(@PathVariable UUID id) {
        return service.classement(id);
    }

    @PostMapping("/api/v1/commissions-recrutement/{id}/deliberation")
    @PreAuthorize(PRESIDENT)
    public CommissionVue deliberer(@PathVariable UUID id, @RequestBody(required = false) DemandeDeliberation demande) {
        return service.deliberer(id, demande);
    }

    @PostMapping("/api/v1/commissions-recrutement/{id}/cloture")
    @PreAuthorize(PRESIDENT)
    public CommissionVue clore(@PathVariable UUID id) {
        return service.clore(id);
    }

    @GetMapping("/api/v1/commissions-recrutement/{id}/proces-verbal")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> procesVerbal(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("proces-verbal-commission.pdf", StandardCharsets.UTF_8).build().toString())
                .body(documents.procesVerbal(id));
    }

    @GetMapping("/api/v1/commissions-recrutement/{id}/resultats")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> resultats(@PathVariable UUID id, @RequestParam(required = false) String format) {
        return documents.resultats(id, ExportTableaux.Format.lire(format));
    }

    // ------------------------------------------------------------------ candidatures

    @GetMapping("/api/v1/commissions-recrutement/{id}/candidatures")
    @PreAuthorize(LECTURE)
    public List<CandidatureVue> candidatures(@PathVariable UUID id, @RequestParam(required = false) UUID offreId) {
        return service.candidatures(id, offreId);
    }

    @PostMapping("/api/v1/commissions-recrutement/{id}/candidatures")
    @PreAuthorize(SAISIE)
    @ResponseStatus(HttpStatus.CREATED)
    public CandidatureVue enregistrer(@PathVariable UUID id, @RequestBody DonneesCandidature donnees) {
        return service.enregistrer(id, donnees);
    }

    @PutMapping("/api/v1/candidatures/{id}")
    @PreAuthorize(SAISIE)
    public CandidatureVue modifier(@PathVariable UUID id, @RequestBody DonneesCandidature donnees) {
        return service.modifierCandidature(id, donnees);
    }

    @DeleteMapping("/api/v1/candidatures/{id}")
    @PreAuthorize(SAISIE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable UUID id) {
        service.supprimerCandidature(id);
    }

    @PostMapping("/api/v1/candidatures/{id}/recevabilite")
    @PreAuthorize(PREPARATION)
    public CandidatureVue recevabilite(@PathVariable UUID id, @RequestBody DemandeRecevabilite demande) {
        return service.recevabilite(id, demande);
    }

    @PostMapping("/api/v1/candidatures/{id}/inscription")
    @PreAuthorize(INSCRIPTION)
    public CandidatureVue inscrire(@PathVariable UUID id, @RequestBody DemandeInscription demande) {
        return service.inscrire(id, demande);
    }

    @PostMapping("/api/v1/candidatures/{id}/desistement")
    @PreAuthorize(INSCRIPTION)
    public ResultatDesistement desister(@PathVariable UUID id, @RequestBody(required = false) DemandeDesistement demande) {
        return service.desister(id, demande);
    }
}
