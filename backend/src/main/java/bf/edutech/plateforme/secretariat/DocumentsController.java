package bf.edutech.plateforme.secretariat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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

import bf.edutech.plateforme.secretariat.CertificatsService.CandidatVue;
import bf.edutech.plateforme.secretariat.CertificatsService.CertificatVue;
import bf.edutech.plateforme.secretariat.DocumentsService.AfficheVue;
import bf.edutech.plateforme.secretariat.DocumentsService.DocumentVue;
import bf.edutech.plateforme.secretariat.ReunionsService.ReunionVue;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.export.ExportTableaux;

/** Documents administratifs, tableau d'affichage, comptes rendus, certificats et dossiers d'examen (v0.47.1). */
@RestController
public class DocumentsController {

    static final String TOUS = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT','CENSEUR','CHEF_TRAVAUX','INTENDANT',"
            + "'SURVEILLANT','ENSEIGNANT','PARENT')";
    static final String DIRECTION = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT','CENSEUR','CHEF_TRAVAUX')";
    static final String EXAMENS = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT','CENSEUR')";

    public record DemandeAffichage(String affichage, LocalDate du, LocalDate au) {
    }

    private final DocumentsService documents;
    private final ReunionsService reunions;
    private final CertificatsService certificats;
    private final EntetesOfficiels entetes;

    DocumentsController(DocumentsService documents, ReunionsService reunions, CertificatsService certificats,
            EntetesOfficiels entetes) {
        this.documents = documents;
        this.reunions = reunions;
        this.certificats = certificats;
        this.entetes = entetes;
    }

    // ------------------------------------------------------------------ documents administratifs

    @GetMapping("/api/v1/documents-administratifs")
    @PreAuthorize(Secretariat.GESTION)
    public List<DocumentVue> documents(@RequestParam(required = false) String type,
            @RequestParam(required = false) String statut) {
        return documents.lister(type, statut);
    }

    @GetMapping("/api/v1/documents-administratifs/{id}")
    @PreAuthorize(Secretariat.GESTION)
    public DocumentVue document(@PathVariable UUID id) {
        return documents.trouver(id);
    }

    @PostMapping("/api/v1/documents-administratifs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Secretariat.GESTION)
    public DocumentVue rediger(@RequestBody DocumentsService.Saisie s) {
        return documents.rediger(s);
    }

    @PutMapping("/api/v1/documents-administratifs/{id}")
    @PreAuthorize(Secretariat.GESTION)
    public DocumentVue modifier(@PathVariable UUID id, @RequestBody DocumentsService.Saisie s) {
        return documents.modifier(id, s);
    }

    @DeleteMapping("/api/v1/documents-administratifs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Secretariat.GESTION)
    public void supprimer(@PathVariable UUID id) {
        documents.supprimer(id);
    }

    @PostMapping("/api/v1/documents-administratifs/{id}/signature")
    @PreAuthorize(Secretariat.CHEF)
    public DocumentVue signer(@PathVariable UUID id) {
        return documents.signer(id);
    }

    @PostMapping("/api/v1/documents-administratifs/{id}/annulation")
    @PreAuthorize(Secretariat.CHEF)
    public DocumentVue annuler(@PathVariable UUID id) {
        return documents.annuler(id);
    }

    /** {"affichage":"PERSONNEL"|"TOUS"|null,"du":"…","au":"…"} ; null retire du tableau. */
    @PutMapping("/api/v1/documents-administratifs/{id}/affichage")
    @PreAuthorize(Secretariat.GESTION)
    public DocumentVue afficher(@PathVariable UUID id, @RequestBody DemandeAffichage d) {
        return documents.afficher(id, d.affichage(), d.du(), d.au());
    }

    @GetMapping("/api/v1/documents-administratifs/{id}/pdf")
    @PreAuthorize(TOUS)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return Telechargements.pdf(documents.pdf(id), "document-administratif.pdf");
    }

    @GetMapping("/api/v1/affichage")
    @PreAuthorize(TOUS)
    public List<AfficheVue> affichage() {
        return documents.affichage();
    }

    // ------------------------------------------------------------------ réunions

    @GetMapping("/api/v1/reunions")
    @PreAuthorize(DIRECTION)
    public List<ReunionVue> reunions() {
        return reunions.lister();
    }

    @PostMapping("/api/v1/reunions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Secretariat.GESTION)
    public ReunionVue creerReunion(@RequestBody ReunionsService.Saisie s) {
        return reunions.creer(s);
    }

    @PutMapping("/api/v1/reunions/{id}")
    @PreAuthorize(Secretariat.GESTION)
    public ReunionVue modifierReunion(@PathVariable UUID id, @RequestBody ReunionsService.Saisie s) {
        return reunions.modifier(id, s);
    }

    @DeleteMapping("/api/v1/reunions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Secretariat.GESTION)
    public void supprimerReunion(@PathVariable UUID id) {
        reunions.supprimer(id);
    }

    @PostMapping("/api/v1/reunions/{id}/validation")
    @PreAuthorize(Secretariat.CHEF)
    public ReunionVue validerReunion(@PathVariable UUID id) {
        return reunions.valider(id);
    }

    @GetMapping("/api/v1/reunions/{id}/pdf")
    @PreAuthorize(DIRECTION)
    public ResponseEntity<byte[]> pdfReunion(@PathVariable UUID id) {
        return Telechargements.pdf(reunions.pdf(id), "compte-rendu.pdf");
    }

    // ------------------------------------------------------------------ certificats et examens

    @PostMapping("/api/v1/inscriptions/{id}/certificat-scolarite")
    @PreAuthorize(Secretariat.GESTION)
    public ResponseEntity<byte[]> certificat(@PathVariable UUID id) {
        return Telechargements.pdf(certificats.certificat(id), "certificat-scolarite.pdf");
    }

    @GetMapping("/api/v1/certificats-scolarite")
    @PreAuthorize(Secretariat.GESTION)
    public List<CertificatVue> certificats(@RequestParam(required = false) Integer annee) {
        return certificats.registre(annee != null ? annee : LocalDate.now(ZoneId.of("Africa/Ouagadougou")).getYear());
    }

    @GetMapping("/api/v1/examens/candidats")
    @PreAuthorize(EXAMENS)
    public List<CandidatVue> candidats(@RequestParam List<UUID> classes) {
        return certificats.candidats(classes);
    }

    @GetMapping("/api/v1/examens/candidats/export")
    @PreAuthorize(EXAMENS)
    public ResponseEntity<byte[]> exporterCandidats(@RequestParam List<UUID> classes,
            @RequestParam(required = false) String examen, @RequestParam(required = false) String format) {
        return ExportTableaux.reponse(entetes.courant(), "candidats-examen", ExportTableaux.Format.lire(format),
                certificats.tableauCandidats(classes, examen));
    }
}
