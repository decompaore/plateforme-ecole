package bf.edutech.plateforme.scolarite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

import bf.edutech.plateforme.scolarite.EncaissementsService.DonneesPaiement;
import bf.edutech.plateforme.scolarite.ParametresScolariteService.ParametresScolarite;
import bf.edutech.plateforme.scolarite.Vues.DonneesFrais;
import bf.edutech.plateforme.scolarite.Vues.EtatClasseVue;
import bf.edutech.plateforme.scolarite.Vues.ExonerationVue;
import bf.edutech.plateforme.scolarite.Vues.FraisVue;
import bf.edutech.plateforme.scolarite.Vues.JournalVue;
import bf.edutech.plateforme.scolarite.Vues.OrganismeVue;
import bf.edutech.plateforme.scolarite.Vues.PaiementVue;
import bf.edutech.plateforme.scolarite.Vues.PriseEnChargeVue;
import bf.edutech.plateforme.scolarite.Vues.ResultatRelancesVue;
import bf.edutech.plateforme.scolarite.Vues.SituationVue;
import bf.edutech.plateforme.scolarite.Vues.VerificationRecuVue;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;

/** Scolarité : frais, bourses, exonérations, encaissements, reçus, états, relances, espace parent. */
@RestController
public class ScolariteController {

    static final String GESTION = "hasAnyRole('INTENDANT','ADMIN_ECOLE')";
    static final String CONSULTATION = "hasAnyRole('INTENDANT','ADMIN_ECOLE','SECRETARIAT')";
    private static final MediaType EXCEL =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public record DemandeOrganisme(@NotBlank @Size(max = 120) String nom, @NotNull TypeOrganisme type,
            @Size(max = 20) String telephone, Boolean actif) {
    }

    public record DemandePriseEnCharge(@NotNull UUID organismeId, BigDecimal taux,
            @Size(max = 60) String referenceDecision, LocalDate dateDecision) {
    }

    public record DemandeExoneration(@NotNull Long montant, @NotBlank @Size(max = 200) String motif) {
    }

    public record DemandeAnnulation(@NotBlank @Size(max = 200) String motif) {
    }

    public record DemandeParametres(@NotNull BigDecimal tauxBoursier, @NotNull BigDecimal tauxSemiBoursier,
            @NotNull Integer delaiRelanceJours) {
    }

    private final ParametresScolariteService parametres;
    private final OrganismesService organismes;
    private final FraisService frais;
    private final BoursesService bourses;
    private final SituationsService situations;
    private final EncaissementsService encaissements;
    private final RelancesService relances;

    ScolariteController(ParametresScolariteService parametres, OrganismesService organismes, FraisService frais,
            BoursesService bourses, SituationsService situations, EncaissementsService encaissements,
            RelancesService relances) {
        this.parametres = parametres;
        this.organismes = organismes;
        this.frais = frais;
        this.bourses = bourses;
        this.situations = situations;
        this.encaissements = encaissements;
        this.relances = relances;
    }

    // ---------------- Paramètres et organismes ----------------

    @GetMapping("/api/v1/parametres/scolarite")
    @PreAuthorize(CONSULTATION)
    public ParametresScolarite parametres() {
        return parametres.lire();
    }

    @PutMapping("/api/v1/parametres/scolarite")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ParametresScolarite modifierParametres(@Valid @RequestBody DemandeParametres d) {
        return parametres.modifier(new ParametresScolarite(d.tauxBoursier(), d.tauxSemiBoursier(),
                d.delaiRelanceJours()));
    }

    @GetMapping("/api/v1/organismes")
    @PreAuthorize(CONSULTATION)
    public List<OrganismeVue> organismes() {
        return organismes.lister();
    }

    @PostMapping("/api/v1/organismes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public OrganismeVue creerOrganisme(@Valid @RequestBody DemandeOrganisme d) {
        return organismes.creer(d.nom(), d.type(), d.telephone());
    }

    @PutMapping("/api/v1/organismes/{id}")
    @PreAuthorize(GESTION)
    public OrganismeVue modifierOrganisme(@PathVariable UUID id, @Valid @RequestBody DemandeOrganisme d) {
        return organismes.modifier(id, d.nom(), d.type(), d.telephone(), d.actif() == null || d.actif());
    }

    // ---------------- Frais ----------------

    @GetMapping("/api/v1/annees/{anneeId}/frais")
    @PreAuthorize(CONSULTATION)
    public List<FraisVue> frais(@PathVariable UUID anneeId) {
        return frais.lister(anneeId);
    }

    /**
     * {"libelle":"Scolarité","montant":75000,"portee":"FILIERES","filieres":["…"],
     * "tranches":[{"dateLimite":"2026-10-15","montant":25000},…]}.
     */
    @PostMapping("/api/v1/annees/{anneeId}/frais")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public FraisVue creerFrais(@PathVariable UUID anneeId, @RequestBody DonneesFrais d) {
        return frais.creer(anneeId, d);
    }

    @PutMapping("/api/v1/frais/{id}")
    @PreAuthorize(GESTION)
    public FraisVue modifierFrais(@PathVariable UUID id, @RequestBody DonneesFrais d) {
        return frais.modifier(id, d);
    }

    @DeleteMapping("/api/v1/frais/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void supprimerFrais(@PathVariable UUID id) {
        frais.supprimer(id);
    }

    /** Souscription d'un élève à un frais facultatif. */
    @PutMapping("/api/v1/inscriptions/{id}/frais/{fraisId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void souscrire(@PathVariable UUID id, @PathVariable UUID fraisId) {
        frais.souscrire(id, fraisId);
    }

    @DeleteMapping("/api/v1/inscriptions/{id}/frais/{fraisId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void resilier(@PathVariable UUID id, @PathVariable UUID fraisId) {
        frais.resilier(id, fraisId);
    }

    // ---------------- Bourses et exonérations ----------------

    @GetMapping("/api/v1/inscriptions/{id}/prise-en-charge")
    @PreAuthorize(CONSULTATION)
    public PriseEnChargeVue priseEnCharge(@PathVariable UUID id) {
        return bourses.priseEnCharge(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune prise en charge pour cette inscription"));
    }

    @PutMapping("/api/v1/inscriptions/{id}/prise-en-charge")
    @PreAuthorize(GESTION)
    public PriseEnChargeVue definirPriseEnCharge(@PathVariable UUID id, @Valid @RequestBody DemandePriseEnCharge d) {
        return bourses.definirPriseEnCharge(id, d.organismeId(), d.taux(), d.referenceDecision(), d.dateDecision());
    }

    @DeleteMapping("/api/v1/inscriptions/{id}/prise-en-charge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void supprimerPriseEnCharge(@PathVariable UUID id) {
        bourses.supprimerPriseEnCharge(id);
    }

    @PutMapping("/api/v1/inscriptions/{id}/exonerations/{fraisId}")
    @PreAuthorize(GESTION)
    public ExonerationVue exonerer(@PathVariable UUID id, @PathVariable UUID fraisId,
            @Valid @RequestBody DemandeExoneration d) {
        return bourses.accorderExoneration(id, fraisId, d.montant(), d.motif());
    }

    @DeleteMapping("/api/v1/inscriptions/{id}/exonerations/{fraisId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void retirerExoneration(@PathVariable UUID id, @PathVariable UUID fraisId) {
        bourses.retirerExoneration(id, fraisId);
    }

    // ---------------- Situation et encaissements ----------------

    @GetMapping("/api/v1/inscriptions/{id}/scolarite")
    @PreAuthorize(CONSULTATION)
    public SituationVue situation(@PathVariable UUID id) {
        return situations.situation(id);
    }

    /** {"montant":25000,"moyen":"ESPECES","payeur":"FAMILLE","deposant":"M. Ouédraogo","cleIdempotence":"…"}. */
    @PostMapping("/api/v1/inscriptions/{id}/paiements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public PaiementVue encaisser(@PathVariable UUID id, @RequestBody DonneesPaiement d) {
        return encaissements.encaisser(id, d);
    }

    @PostMapping("/api/v1/paiements/{id}/annulation")
    @PreAuthorize(GESTION)
    public PaiementVue annuler(@PathVariable UUID id, @Valid @RequestBody DemandeAnnulation d) {
        return encaissements.annuler(id, d.motif());
    }

    @GetMapping("/api/v1/paiements/{id}/recu")
    @PreAuthorize(CONSULTATION)
    public ResponseEntity<byte[]> recu(@PathVariable UUID id) {
        return fichier(encaissements.recuPdf(id), MediaType.APPLICATION_PDF, "recu-" + id + ".pdf", true);
    }

    @GetMapping("/api/v1/paiements")
    @PreAuthorize(GESTION)
    public JournalVue journal(@RequestParam LocalDate du, @RequestParam LocalDate au) {
        return encaissements.journal(du, au);
    }

    @GetMapping("/api/v1/classes/{classeId}/scolarite")
    @PreAuthorize(CONSULTATION)
    public EtatClasseVue etatClasse(@PathVariable UUID classeId) {
        return situations.etatClasse(classeId);
    }

    @GetMapping("/api/v1/classes/{classeId}/scolarite/retards")
    @PreAuthorize(CONSULTATION)
    public ResponseEntity<byte[]> retards(@PathVariable UUID classeId) {
        return fichier(situations.retardsExcel(classeId), EXCEL, "retards-" + classeId + ".xlsx", false);
    }

    /** Relance les familles en retard d'une classe (ou de toute l'école sans {@code classeId}). */
    @PostMapping("/api/v1/scolarite/relances")
    @PreAuthorize(GESTION)
    public ResultatRelancesVue relancer(@RequestParam(required = false) UUID classeId) {
        return relances.relancer(classeId);
    }

    // ---------------- Espace parent ----------------

    @GetMapping("/api/v1/espace-parent/enfants/{eleveId}/scolarite")
    @PreAuthorize("hasRole('PARENT')")
    public List<SituationVue> scolariteDeMonEnfant(@PathVariable UUID eleveId) {
        return situations.deMonEnfant(eleveId);
    }

    @GetMapping("/api/v1/espace-parent/paiements/{id}/recu")
    @PreAuthorize("hasRole('PARENT')")
    public ResponseEntity<byte[]> recuPourParent(@PathVariable UUID id) {
        return fichier(encaissements.recuPdfPourParent(id), MediaType.APPLICATION_PDF, "recu-" + id + ".pdf", true);
    }

    // ---------------- Vérification publique (sans connexion) ----------------

    @GetMapping("/api/v1/verification/recus/{code}")
    public VerificationRecuVue verifier(@PathVariable String code) {
        return encaissements.verifier(code);
    }

    private static ResponseEntity<byte[]> fichier(byte[] contenu, MediaType type, String nom, boolean enLigne) {
        ContentDisposition disposition = (enLigne ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(nom).build();
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(contenu);
    }
}
