package bf.edutech.plateforme.secretariat;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

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
import org.springframework.web.multipart.MultipartFile;

import bf.edutech.plateforme.secretariat.AccueilService.PassageVue;
import bf.edutech.plateforme.secretariat.ArchivesService.PieceVue;
import bf.edutech.plateforme.secretariat.CourrierService.CourrierVue;
import bf.edutech.plateforme.secretariat.DemandesService.DemandeVue;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.export.ExportTableaux;

/** Courrier, actes administratifs du personnel, accueil et archives (v0.47.1). */
@RestController
public class SecretariatController {

    static final String PERSONNEL =
            "hasAnyRole('ADMIN_ECOLE','SECRETARIAT','CENSEUR','CHEF_TRAVAUX','INTENDANT','SURVEILLANT','ENSEIGNANT')";

    public record DemandeSuivi(String statut, String service, String suivi) {
    }

    public record DemandeAvis(Boolean favorable, String avis) {
    }

    public record DemandeHierarchie(String destinataire, String categorie) {
    }

    public record DemandeCloture(String suite) {
    }

    private final CourrierService courrier;
    private final DemandesService demandes;
    private final AccueilService accueil;
    private final ArchivesService archives;
    private final EntetesOfficiels entetes;

    SecretariatController(CourrierService courrier, DemandesService demandes, AccueilService accueil,
            ArchivesService archives, EntetesOfficiels entetes) {
        this.courrier = courrier;
        this.demandes = demandes;
        this.accueil = accueil;
        this.archives = archives;
        this.entetes = entetes;
    }

    // ------------------------------------------------------------------ courrier

    @GetMapping("/api/v1/courriers")
    @PreAuthorize(PERSONNEL)
    public List<CourrierVue> courriers(@RequestParam(required = false) String sens,
            @RequestParam(required = false) Integer annee, @RequestParam(required = false) String q) {
        return courrier.lister(sens, annee, q);
    }

    @GetMapping("/api/v1/courriers/registre")
    @PreAuthorize(Secretariat.GESTION)
    public ResponseEntity<byte[]> registre(@RequestParam(required = false) Integer annee,
            @RequestParam(required = false) String format) {
        int a = annee != null ? annee : LocalDate.now(ZoneId.of("Africa/Ouagadougou")).getYear();
        return ExportTableaux.reponse(entetes.courant(), "registre-courrier-" + a, ExportTableaux.Format.lire(format),
                courrier.registre(a));
    }

    @GetMapping("/api/v1/courriers/{id}")
    @PreAuthorize(PERSONNEL)
    public CourrierVue courrier(@PathVariable UUID id) {
        return courrier.trouver(id);
    }

    @PostMapping("/api/v1/courriers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Secretariat.GESTION)
    public CourrierVue enregistrer(@RequestBody CourrierService.Saisie s) {
        return courrier.enregistrer(s);
    }

    @PutMapping("/api/v1/courriers/{id}")
    @PreAuthorize(Secretariat.GESTION)
    public CourrierVue modifierCourrier(@PathVariable UUID id, @RequestBody CourrierService.Saisie s) {
        return courrier.modifier(id, s);
    }

    @PostMapping("/api/v1/courriers/{id}/suivi")
    @PreAuthorize(PERSONNEL)
    public CourrierVue suivre(@PathVariable UUID id, @RequestBody DemandeSuivi d) {
        return courrier.suivre(id, d.statut(), d.service(), d.suivi());
    }

    @PostMapping(path = "/api/v1/courriers/{id}/fichier", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Secretariat.GESTION)
    public CourrierVue joindreCourrier(@PathVariable UUID id, @RequestParam("fichier") MultipartFile fichier)
            throws IOException {
        return courrier.joindre(id, fichier.getBytes(), fichier.getOriginalFilename());
    }

    @GetMapping("/api/v1/courriers/{id}/fichier")
    @PreAuthorize(PERSONNEL)
    public ResponseEntity<byte[]> fichierCourrier(@PathVariable UUID id) {
        return Telechargements.fichier(courrier.fichier(id));
    }

    // ------------------------------------------------------------------ actes administratifs du personnel

    @GetMapping("/api/v1/demandes-administratives")
    @PreAuthorize(Secretariat.GESTION)
    public List<DemandeVue> demandes(@RequestParam(required = false) String statut) {
        return demandes.lister(statut);
    }

    @GetMapping("/api/v1/demandes-administratives/miennes")
    @PreAuthorize(PERSONNEL)
    public List<DemandeVue> mesDemandes() {
        return demandes.miennes();
    }

    @PostMapping("/api/v1/demandes-administratives")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Secretariat.GESTION)
    public DemandeVue recevoir(@RequestBody DemandesService.Saisie s) {
        return demandes.recevoir(s);
    }

    @PostMapping("/api/v1/demandes-administratives/{id}/transmission")
    @PreAuthorize(Secretariat.GESTION)
    public DemandeVue transmettre(@PathVariable UUID id) {
        return demandes.transmettre(id);
    }

    @PostMapping("/api/v1/demandes-administratives/{id}/avis")
    @PreAuthorize(Secretariat.CHEF)
    public DemandeVue aviser(@PathVariable UUID id, @RequestBody DemandeAvis d) {
        return demandes.aviser(id, d.favorable(), d.avis());
    }

    @PostMapping("/api/v1/demandes-administratives/{id}/transmission-hierarchie")
    @PreAuthorize(Secretariat.GESTION)
    public DemandeVue transmettreHierarchie(@PathVariable UUID id, @RequestBody DemandeHierarchie d) {
        return demandes.transmettreHierarchie(id, d.destinataire(), d.categorie());
    }

    @PostMapping("/api/v1/demandes-administratives/{id}/cloture")
    @PreAuthorize(Secretariat.GESTION)
    public DemandeVue cloturer(@PathVariable UUID id, @RequestBody DemandeCloture d) {
        return demandes.cloturer(id, d.suite());
    }

    @PostMapping(path = "/api/v1/demandes-administratives/{id}/fichier", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Secretariat.GESTION)
    public DemandeVue joindreDemande(@PathVariable UUID id, @RequestParam("fichier") MultipartFile fichier)
            throws IOException {
        return demandes.joindre(id, fichier.getBytes(), fichier.getOriginalFilename());
    }

    @GetMapping("/api/v1/demandes-administratives/{id}/fichier")
    @PreAuthorize(Secretariat.GESTION)
    public ResponseEntity<byte[]> fichierDemande(@PathVariable UUID id) {
        return Telechargements.fichier(demandes.fichier(id));
    }

    // ------------------------------------------------------------------ accueil

    @GetMapping("/api/v1/secretariat/personnel")
    @PreAuthorize(Secretariat.GESTION)
    public List<AccueilService.PersonneVue> personnel() {
        return accueil.personnel();
    }

    @GetMapping("/api/v1/accueil")
    @PreAuthorize(Secretariat.GESTION)
    public List<PassageVue> accueil(@RequestParam(required = false) LocalDate jour) {
        return accueil.registre(jour);
    }

    @PostMapping("/api/v1/accueil")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Secretariat.GESTION)
    public PassageVue passage(@RequestBody AccueilService.Saisie s) {
        return accueil.enregistrer(s);
    }

    @PostMapping("/api/v1/accueil/{id}/transmission")
    @PreAuthorize(Secretariat.GESTION)
    public PassageVue transmettrePassage(@PathVariable UUID id) {
        return accueil.transmettre(id);
    }

    @PostMapping("/api/v1/accueil/{id}/depart")
    @PreAuthorize(Secretariat.GESTION)
    public PassageVue depart(@PathVariable UUID id) {
        return accueil.depart(id);
    }

    // ------------------------------------------------------------------ archives

    @GetMapping("/api/v1/archives")
    @PreAuthorize(Secretariat.GESTION)
    public List<PieceVue> archives(@RequestParam String dossier, @RequestParam(required = false) UUID eleveId,
            @RequestParam(required = false) UUID utilisateurId) {
        return archives.lister(dossier, eleveId, utilisateurId);
    }

    @PostMapping(path = "/api/v1/archives", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Secretariat.GESTION)
    public PieceVue archiver(@RequestParam("fichier") MultipartFile fichier, @RequestParam String dossier,
            @RequestParam(required = false) UUID eleveId, @RequestParam(required = false) UUID utilisateurId,
            @RequestParam String categorie, @RequestParam String titre, @RequestParam(required = false) String cote,
            @RequestParam(required = false) LocalDate dateDocument) throws IOException {
        return archives.ajouter(new ArchivesService.Description(dossier, eleveId, utilisateurId, categorie, titre, cote,
                dateDocument), fichier.getBytes(), fichier.getOriginalFilename());
    }

    @GetMapping("/api/v1/archives/{id}/fichier")
    @PreAuthorize(Secretariat.GESTION)
    public ResponseEntity<byte[]> fichierArchive(@PathVariable UUID id) {
        return Telechargements.fichier(archives.fichier(id));
    }

    @DeleteMapping("/api/v1/archives/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Secretariat.GESTION)
    public void supprimerArchive(@PathVariable UUID id) {
        archives.supprimer(id);
    }
}
