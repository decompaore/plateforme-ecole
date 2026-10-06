package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.bulletins.ParametresBulletinsService.ParametresBulletins;
import bf.edutech.plateforme.bulletins.Vues.AppreciationVue;
import bf.edutech.plateforme.bulletins.Vues.AvisVue;
import bf.edutech.plateforme.bulletins.Vues.BulletinEleveVue;
import bf.edutech.plateforme.bulletins.Vues.GenerationVue;
import bf.edutech.plateforme.bulletins.Vues.SaisieAppreciation;
import bf.edutech.plateforme.bulletins.Vues.SaisieAvis;
import bf.edutech.plateforme.bulletins.Vues.VerificationVue;

/** Bulletins : appréciations, conseil de classe, génération, PDF, publication, espace parent, vérification. */
@RestController
public class BulletinsController {

    static final String DIRECTION = "hasAnyRole('CENSEUR','ADMIN_ECOLE')";
    static final String CONSULTATION = "hasAnyRole('CENSEUR','ADMIN_ECOLE','SECRETARIAT')";
    static final String APPRECIATIONS = "hasAnyRole('ENSEIGNANT','CENSEUR','ADMIN_ECOLE')";
    static final String BASE = "/api/v1/classes/{classeId}/periodes/{periodeId}";

    public record DemandeAppreciations(@NotNull UUID matiereId,
            @NotNull @Size(max = 200) List<@NotNull SaisieAppreciation> appreciations) {
    }

    public record DemandeAvis(@NotNull @Size(max = 200) List<@NotNull SaisieAvis> avis) {
    }

    public record DemandeParametres(
            @NotBlank @Size(max = 120) String entetePays,
            @Size(max = 120) String enteteDevise,
            @Size(max = 200) String enteteMinistere,
            @Size(max = 200) String enteteDirection,
            @Size(max = 200) String adresse,
            @NotNull @DecimalMin("0") @DecimalMax("20") BigDecimal seuilTableauHonneur,
            @NotNull @DecimalMin("0") @DecimalMax("20") BigDecimal seuilEncouragements,
            @NotNull @DecimalMin("0") @DecimalMax("20") BigDecimal seuilFelicitations,
            @NotNull @DecimalMin("0") @DecimalMax("20") BigDecimal seuilAvertissement) {
    }

    private final BulletinsService bulletins;
    private final ConseilService conseil;
    private final ParametresBulletinsService parametres;

    BulletinsController(BulletinsService bulletins, ConseilService conseil, ParametresBulletinsService parametres) {
        this.bulletins = bulletins;
        this.conseil = conseil;
        this.parametres = parametres;
    }

    // ---------------- Appréciations des enseignants ----------------

    @GetMapping(BASE + "/appreciations")
    @PreAuthorize(APPRECIATIONS)
    public List<AppreciationVue> appreciations(@PathVariable UUID classeId, @PathVariable UUID periodeId,
            @RequestParam UUID matiereId) {
        return conseil.appreciations(classeId, periodeId, matiereId);
    }

    /** {"matiereId":"…","appreciations":[{"inscriptionId":"…","texte":"Bon travail"}]} ; texte vide : efface. */
    @PutMapping(BASE + "/appreciations")
    @PreAuthorize(APPRECIATIONS)
    public List<AppreciationVue> saisirAppreciations(@PathVariable UUID classeId, @PathVariable UUID periodeId,
            @Valid @RequestBody DemandeAppreciations d) {
        return conseil.saisirAppreciations(classeId, periodeId, d.matiereId(), d.appreciations());
    }

    // ---------------- Conseil de classe ----------------

    @GetMapping(BASE + "/conseil")
    @PreAuthorize(DIRECTION)
    public List<AvisVue> conseil(@PathVariable UUID classeId, @PathVariable UUID periodeId) {
        return conseil.avis(classeId, periodeId);
    }

    /** {"avis":[{"inscriptionId":"…","distinction":"ENCOURAGEMENTS","appreciation":"Élève sérieux"}]}. */
    @PutMapping(BASE + "/conseil")
    @PreAuthorize(DIRECTION)
    public List<AvisVue> saisirAvis(@PathVariable UUID classeId, @PathVariable UUID periodeId,
            @Valid @RequestBody DemandeAvis d) {
        return conseil.saisirAvis(classeId, periodeId, d.avis());
    }

    // ---------------- Génération, PDF, publication ----------------

    @PostMapping(BASE + "/bulletins")
    @PreAuthorize(DIRECTION)
    public GenerationVue generer(@PathVariable UUID classeId, @PathVariable UUID periodeId) {
        return bulletins.generer(classeId, periodeId);
    }

    @GetMapping(BASE + "/bulletins")
    @PreAuthorize(CONSULTATION)
    public GenerationVue consulter(@PathVariable UUID classeId, @PathVariable UUID periodeId) {
        return bulletins.consulter(classeId, periodeId);
    }

    /** Tous les bulletins de la classe en un seul PDF (impression). */
    @GetMapping(BASE + "/bulletins/pdf")
    @PreAuthorize(CONSULTATION)
    public ResponseEntity<byte[]> pdfClasse(@PathVariable UUID classeId, @PathVariable UUID periodeId) {
        return pdf(bulletins.pdfClasse(classeId, periodeId), "bulletins-" + classeId + ".pdf");
    }

    @PostMapping(BASE + "/bulletins/publication")
    @PreAuthorize(DIRECTION)
    public GenerationVue publier(@PathVariable UUID classeId, @PathVariable UUID periodeId) {
        return bulletins.publier(classeId, periodeId);
    }

    @GetMapping("/api/v1/bulletins/{id}/pdf")
    @PreAuthorize(CONSULTATION)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return pdf(bulletins.pdf(id), "bulletin-" + id + ".pdf");
    }

    // ---------------- Paramètres ----------------

    @GetMapping("/api/v1/parametres/bulletins")
    @PreAuthorize(CONSULTATION)
    public ParametresBulletins parametres() {
        return parametres.lire();
    }

    @PutMapping("/api/v1/parametres/bulletins")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ParametresBulletins modifierParametres(@Valid @RequestBody DemandeParametres d) {
        return parametres.modifier(new ParametresBulletins(d.entetePays(), d.enteteDevise(), d.enteteMinistere(),
                d.enteteDirection(), d.adresse(), d.seuilTableauHonneur(), d.seuilEncouragements(),
                d.seuilFelicitations(), d.seuilAvertissement()));
    }

    // ---------------- Espace parent ----------------

    @GetMapping("/api/v1/espace-parent/enfants/{eleveId}/bulletins")
    @PreAuthorize("hasRole('PARENT')")
    public List<BulletinEleveVue> bulletinsDeMonEnfant(@PathVariable UUID eleveId) {
        return bulletins.deMonEnfant(eleveId);
    }

    @GetMapping("/api/v1/espace-parent/bulletins/{id}/pdf")
    @PreAuthorize("hasRole('PARENT')")
    public ResponseEntity<byte[]> pdfPourParent(@PathVariable UUID id) {
        return pdf(bulletins.pdfPourParent(id), "bulletin-" + id + ".pdf");
    }

    // ---------------- Vérification publique (sans connexion) ----------------

    @GetMapping("/api/v1/verification/bulletins/{code}")
    public VerificationVue verifier(@PathVariable String code) {
        return bulletins.verifier(code);
    }

    private static ResponseEntity<byte[]> pdf(byte[] contenu, String nomFichier) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(nomFichier).build().toString())
                .body(contenu);
    }
}
