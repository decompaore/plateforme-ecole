package bf.edutech.plateforme.personnel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bf.edutech.plateforme.personnel.AbsencesPersonnelService.DonneesAbsence;
import bf.edutech.plateforme.personnel.ActesService.Document;
import bf.edutech.plateforme.personnel.DossiersService.DonneesDiplome;
import bf.edutech.plateforme.personnel.DossiersService.ListePersonnel;
import bf.edutech.plateforme.personnel.DossiersService.Telechargement;
import bf.edutech.plateforme.personnel.Vues.AbsenceVue;
import bf.edutech.plateforme.personnel.Vues.ActeVue;
import bf.edutech.plateforme.personnel.Vues.DonneesAgent;
import bf.edutech.plateforme.personnel.Vues.DossierVue;
import bf.edutech.plateforme.personnel.Vues.NaturePiece;
import bf.edutech.plateforme.personnel.Vues.ResultatImport;
import bf.edutech.plateforme.personnel.Vues.TypeActe;

/** Dossier administratif du personnel (v0.51) : direction et secrétariat, et « Mon dossier » de chaque agent. */
@RestController
public class PersonnelController {

    public record DemandeActe(TypeActe type, LocalDate dateEffet, String complement) {
    }

    public record DemandeTexte(String texte) {
    }

    public record DemandeDecision(boolean accorder, String reponse) {
    }

    private final DossiersService dossiers;
    private final ActesService actes;
    private final AbsencesPersonnelService absences;

    PersonnelController(DossiersService dossiers, ActesService actes, AbsencesPersonnelService absences) {
        this.dossiers = dossiers;
        this.actes = actes;
        this.absences = absences;
    }

    // ------------------------------------------------------------------ dossiers

    @GetMapping("/api/v1/personnel")
    @PreAuthorize(Personnel.LECTURE)
    public ListePersonnel lister() {
        return dossiers.lister();
    }

    @PostMapping("/api/v1/personnel/reprise")
    @PreAuthorize(Personnel.GESTION)
    public ResultatImport importer() {
        return dossiers.importer();
    }

    @PostMapping("/api/v1/personnel")
    @PreAuthorize(Personnel.GESTION)
    public DossierVue creer(@RequestBody DonneesAgent d) {
        return dossiers.creer(d);
    }

    @GetMapping("/api/v1/personnel/{id}")
    @PreAuthorize(Personnel.PERSONNEL)
    public DossierVue dossier(@PathVariable UUID id) {
        return dossiers.dossier(id);
    }

    @PutMapping("/api/v1/personnel/{id}")
    @PreAuthorize(Personnel.GESTION)
    public DossierVue modifier(@PathVariable UUID id, @RequestBody DonneesAgent d) {
        return dossiers.modifier(id, d);
    }

    @PostMapping("/api/v1/personnel/{id}/diplomes")
    @PreAuthorize(Personnel.GESTION)
    public DossierVue ajouterDiplome(@PathVariable UUID id, @RequestBody DonneesDiplome d) {
        return dossiers.ajouterDiplome(id, d);
    }

    @DeleteMapping("/api/v1/personnel/diplomes/{diplomeId}")
    @PreAuthorize(Personnel.GESTION)
    public DossierVue retirerDiplome(@PathVariable UUID diplomeId) {
        return dossiers.retirerDiplome(diplomeId);
    }

    @PostMapping(path = "/api/v1/personnel/{id}/pieces", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Personnel.GESTION)
    public DossierVue deposerPiece(@PathVariable UUID id, @RequestParam("fichier") MultipartFile fichier,
            @RequestParam String nature, @RequestParam String objet, @RequestParam(required = false) String reference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate datePiece)
            throws IOException {
        return dossiers.deposerPiece(id, Personnel.valeur(NaturePiece.class, nature, "Nature"), reference, datePiece,
                objet, fichier.getBytes(), fichier.getOriginalFilename());
    }

    @GetMapping("/api/v1/personnel/pieces/{pieceId}")
    @PreAuthorize(Personnel.PERSONNEL)
    public ResponseEntity<byte[]> piece(@PathVariable UUID pieceId) {
        Telechargement t = dossiers.piece(pieceId);
        return fichier(t.contenu(), MediaType.parseMediaType(t.type()), t.nom());
    }

    @DeleteMapping("/api/v1/personnel/pieces/{pieceId}")
    @PreAuthorize(Personnel.GESTION)
    public DossierVue retirerPiece(@PathVariable UUID pieceId) {
        return dossiers.retirerPiece(pieceId);
    }

    @PostMapping("/api/v1/personnel/signalements/{signalementId}/traitement")
    @PreAuthorize(Personnel.GESTION)
    public DossierVue traiterSignalement(@PathVariable UUID signalementId, @RequestBody DemandeTexte d) {
        return dossiers.traiterSignalement(signalementId, d.texte());
    }

    // ------------------------------------------------------------------ actes

    @PostMapping("/api/v1/personnel/{id}/actes")
    @PreAuthorize(Personnel.GESTION)
    public ActeVue etablirActe(@PathVariable UUID id, @RequestBody DemandeActe d) {
        if (d.type() == null) {
            throw new IllegalArgumentException("Type d'acte obligatoire");
        }
        return actes.etablir(id, d.type(), d.dateEffet(), d.complement());
    }

    @PostMapping("/api/v1/personnel/actes/{acteId}/signature")
    @PreAuthorize(Personnel.CHEF)
    public ActeVue signer(@PathVariable UUID acteId) {
        return actes.signer(acteId);
    }

    @PostMapping("/api/v1/personnel/actes/{acteId}/annulation")
    @PreAuthorize(Personnel.CHEF)
    public ActeVue annulerActe(@PathVariable UUID acteId, @RequestBody DemandeTexte d) {
        return actes.annuler(acteId, d.texte());
    }

    @GetMapping("/api/v1/personnel/actes/{acteId}/pdf")
    @PreAuthorize(Personnel.PERSONNEL)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID acteId) {
        Document d = actes.pdf(acteId);
        return fichier(d.contenu(), MediaType.APPLICATION_PDF, d.nom());
    }

    // ------------------------------------------------------------------ absences

    @GetMapping("/api/v1/personnel/absences")
    @PreAuthorize(Personnel.LECTURE)
    public List<AbsenceVue> suiviAbsences() {
        return absences.suivi();
    }

    @PostMapping("/api/v1/personnel/{id}/absences")
    @PreAuthorize(Personnel.GESTION)
    public AbsenceVue saisirAbsence(@PathVariable UUID id, @RequestBody DonneesAbsence d) {
        return absences.saisir(id, d);
    }

    @PostMapping("/api/v1/personnel/absences/{absenceId}/decision")
    @PreAuthorize(Personnel.CHEF)
    public AbsenceVue decider(@PathVariable UUID absenceId, @RequestBody DemandeDecision d) {
        return absences.decider(absenceId, d.accorder(), d.reponse());
    }

    @PostMapping("/api/v1/personnel/absences/{absenceId}/annulation")
    @PreAuthorize(Personnel.PERSONNEL)
    public AbsenceVue annulerAbsence(@PathVariable UUID absenceId, @RequestBody(required = false) DemandeTexte d) {
        return absences.annuler(absenceId, d == null ? null : d.texte());
    }

    // ------------------------------------------------------------------ mon dossier

    @GetMapping("/api/v1/mon-dossier")
    @PreAuthorize(Personnel.PERSONNEL)
    public DossierVue monDossier() {
        return dossiers.monDossier();
    }

    @PostMapping("/api/v1/mon-dossier/signalements")
    @PreAuthorize(Personnel.PERSONNEL)
    public DossierVue signaler(@RequestBody DemandeTexte d) {
        return dossiers.signaler(d.texte());
    }

    @PostMapping("/api/v1/mon-dossier/absences")
    @PreAuthorize(Personnel.PERSONNEL)
    public AbsenceVue demanderAbsence(@RequestBody DonneesAbsence d) {
        return absences.demander(d);
    }

    private static ResponseEntity<byte[]> fichier(byte[] contenu, MediaType type, String nom) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nom, StandardCharsets.UTF_8).build().toString())
                .header("Cache-Control", "no-store")
                .body(contenu);
    }
}
