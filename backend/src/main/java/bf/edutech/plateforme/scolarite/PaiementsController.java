package bf.edutech.plateforme.scolarite;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bf.edutech.plateforme.scolarite.MoyensPaiementService.CompteBancaireVue;
import bf.edutech.plateforme.scolarite.MoyensPaiementService.DonneesCompte;
import bf.edutech.plateforme.scolarite.MoyensPaiementService.DonneesParametres;
import bf.edutech.plateforme.scolarite.MoyensPaiementService.MoyensParentVue;
import bf.edutech.plateforme.scolarite.MoyensPaiementService.ParametresPaiementVue;
import bf.edutech.plateforme.scolarite.VirementsService.DonneesVirement;
import bf.edutech.plateforme.scolarite.VirementsService.Preuve;
import bf.edutech.plateforme.scolarite.VirementsService.StatutVirement;
import bf.edutech.plateforme.scolarite.VirementsService.VirementVue;

/**
 * Moyens de paiement de l'établissement et virements déclarés par les parents (v0.38).
 * La direction (administrateur) configure ; l'intendance consulte et traite les virements.
 */
@RestController
public class PaiementsController {

    public record DemandeValidation(Long montantRecu) {
    }

    public record DemandeRefus(@NotBlank @Size(max = 300) String motif) {
    }

    private final MoyensPaiementService moyens;
    private final VirementsService virements;

    PaiementsController(MoyensPaiementService moyens, VirementsService virements) {
        this.moyens = moyens;
        this.virements = virements;
    }

    // ---------------- Configuration ----------------

    @GetMapping("/api/v1/parametres/paiement")
    @PreAuthorize(ScolariteController.CONSULTATION)
    public ParametresPaiementVue parametres() {
        return moyens.lire();
    }

    @PutMapping("/api/v1/parametres/paiement")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ParametresPaiementVue modifier(@RequestBody DonneesParametres d) {
        return moyens.modifier(d);
    }

    @PostMapping("/api/v1/comptes-bancaires")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public CompteBancaireVue creerCompte(@RequestBody DonneesCompte d) {
        return moyens.creerCompte(d);
    }

    @PutMapping("/api/v1/comptes-bancaires/{id}")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public CompteBancaireVue modifierCompte(@PathVariable UUID id, @RequestBody DonneesCompte d) {
        return moyens.modifierCompte(id, d);
    }

    // ---------------- Intendance : virements déclarés ----------------

    /** {@code statut} : EN_ATTENTE (par défaut, les plus anciens d'abord), VALIDE, REFUSE ou TOUS. */
    @GetMapping("/api/v1/virements")
    @PreAuthorize(ScolariteController.GESTION)
    public List<VirementVue> virements(@RequestParam(required = false) String statut) {
        if ("TOUS".equalsIgnoreCase(statut)) {
            return virements.lister(null);
        }
        return virements.lister(statut == null || statut.isBlank() ? StatutVirement.EN_ATTENTE
                : StatutVirement.valueOf(statut.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    @GetMapping("/api/v1/virements/{id}/preuve")
    @PreAuthorize(ScolariteController.GESTION)
    public ResponseEntity<byte[]> preuve(@PathVariable UUID id) {
        Preuve p = virements.preuve(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.type()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(VirementsService.nomFichierPreuve(id, p.type())).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(p.contenu());
    }

    @PostMapping("/api/v1/virements/{id}/validation")
    @PreAuthorize(ScolariteController.GESTION)
    public VirementVue valider(@PathVariable UUID id, @RequestBody(required = false) DemandeValidation d) {
        return virements.valider(id, d == null ? null : d.montantRecu());
    }

    @PostMapping("/api/v1/virements/{id}/refus")
    @PreAuthorize(ScolariteController.GESTION)
    public VirementVue refuser(@PathVariable UUID id, @Valid @RequestBody DemandeRefus d) {
        return virements.refuser(id, d.motif());
    }

    // ---------------- Parent ----------------

    @GetMapping("/api/v1/espace-parent/inscriptions/{inscriptionId}/moyens-paiement")
    @PreAuthorize("hasRole('PARENT')")
    public MoyensParentVue moyensParent(@PathVariable UUID inscriptionId) {
        return moyens.pourParent(inscriptionId);
    }

    @GetMapping("/api/v1/espace-parent/inscriptions/{inscriptionId}/virements")
    @PreAuthorize("hasRole('PARENT')")
    public List<VirementVue> mesVirements(@PathVariable UUID inscriptionId) {
        return virements.mesVirements(inscriptionId);
    }

    /** Formulaire multipart : champs du virement et, facultatif, « fichier » (photo ou PDF de l'avis). */
    @PostMapping(path = "/api/v1/espace-parent/inscriptions/{inscriptionId}/virements",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PARENT')")
    public VirementVue declarer(@PathVariable UUID inscriptionId, @RequestParam UUID compteId,
            @RequestParam Long montant, @RequestParam LocalDate dateVirement, @RequestParam String reference,
            @RequestParam(required = false) String donneurOrdre, @RequestParam(required = false) String cleIdempotence,
            @RequestParam(name = "fichier", required = false) MultipartFile fichier) throws IOException {
        Preuve preuve = fichier == null || fichier.isEmpty() ? null : new Preuve(fichier.getBytes(), fichier.getContentType());
        return virements.declarer(inscriptionId,
                new DonneesVirement(compteId, montant, dateVirement, reference, donneurOrdre, cleIdempotence), preuve);
    }
}
