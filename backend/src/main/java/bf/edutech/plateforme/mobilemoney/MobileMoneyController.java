package bf.edutech.plateforme.mobilemoney;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.mobilemoney.ConfigurationMobileMoneyService.ConfigurationVue;
import bf.edutech.plateforme.mobilemoney.PaiementsMobileMoneyService.TransactionVue;
import bf.edutech.plateforme.mobilemoney.RapprochementService.EcartVue;
import bf.edutech.plateforme.mobilemoney.RapprochementService.RapprochementVue;

/** Mobile Money : configuration, paiement par le parent, notification de l'agrégateur, suivi, rapprochement. */
@RestController
public class MobileMoneyController {

    static final String GESTION = "hasAnyRole('INTENDANT','ADMIN_ECOLE')";

    public record DemandeConfiguration(@NotBlank @Size(max = 20) String agregateur,
            @NotBlank @Size(max = 80) String identifiantMarchand, @Size(max = 500) String cleApi,
            @Size(max = 500) String secretWebhook, Boolean actif) {
    }

    public record DemandePaiement(@NotNull Long montant, @NotNull Operateur operateur, @NotBlank String telephone,
            @Size(max = 64) String cleIdempotence) {
    }

    public record DemandeMotif(@NotBlank @Size(max = 300) String motif) {
    }

    private final ConfigurationMobileMoneyService configuration;
    private final PaiementsMobileMoneyService paiements;
    private final RapprochementService rapprochement;

    MobileMoneyController(ConfigurationMobileMoneyService configuration, PaiementsMobileMoneyService paiements,
            RapprochementService rapprochement) {
        this.configuration = configuration;
        this.paiements = paiements;
        this.rapprochement = rapprochement;
    }

    // ---------------- Configuration (compte marchand de l'école) ----------------

    @GetMapping("/api/v1/parametres/mobile-money")
    @PreAuthorize(GESTION)
    public ConfigurationVue configuration() {
        return configuration.lire();
    }

    @PutMapping("/api/v1/parametres/mobile-money")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public ConfigurationVue configurer(@Valid @RequestBody DemandeConfiguration d) {
        return configuration.modifier(d.agregateur(), d.identifiantMarchand(), d.cleApi(), d.secretWebhook(),
                d.actif() == null || d.actif());
    }

    // ---------------- Parent ----------------

    /** {"montant":25000,"operateur":"ORANGE_MONEY","telephone":"70112233","cleIdempotence":"…"} → 202. */
    @PostMapping("/api/v1/espace-parent/inscriptions/{inscriptionId}/mobile-money")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('PARENT')")
    public TransactionVue payer(@PathVariable UUID inscriptionId, @Valid @RequestBody DemandePaiement d) {
        return paiements.initier(inscriptionId, d.montant(), d.operateur(), d.telephone(), d.cleIdempotence());
    }

    @GetMapping("/api/v1/espace-parent/mobile-money/{id}")
    @PreAuthorize("hasRole('PARENT')")
    public TransactionVue suivre(@PathVariable UUID id) {
        return paiements.suivrePourParent(id);
    }

    // ---------------- Notification de l'agrégateur (sans connexion, signée) ----------------

    @PostMapping("/api/v1/webhooks/mobile-money/{ecoleId}")
    public String notification(@PathVariable UUID ecoleId, @RequestBody byte[] corps,
            @RequestHeader HttpHeaders entetes) {
        paiements.notifier(ecoleId, corps, entetes);
        return "OK";
    }

    // ---------------- Intendance ----------------

    @GetMapping("/api/v1/mobile-money/transactions")
    @PreAuthorize(GESTION)
    public List<TransactionVue> transactions(@RequestParam LocalDate du, @RequestParam LocalDate au) {
        return paiements.lister(du, au);
    }

    @GetMapping("/api/v1/mobile-money/transactions/a-verifier")
    @PreAuthorize(GESTION)
    public List<TransactionVue> aVerifier() {
        return paiements.aVerifier();
    }

    @PostMapping("/api/v1/mobile-money/transactions/{id}/verification")
    @PreAuthorize(GESTION)
    public TransactionVue verifier(@PathVariable UUID id) {
        return paiements.verifier(id);
    }

    @PostMapping("/api/v1/mobile-money/transactions/{id}/regularisation")
    @PreAuthorize(GESTION)
    public TransactionVue regulariser(@PathVariable UUID id, @Valid @RequestBody DemandeMotif d) {
        return paiements.regulariser(id, d.motif());
    }

    @GetMapping("/api/v1/mobile-money/rapprochements")
    @PreAuthorize(GESTION)
    public List<RapprochementVue> rapprochements(@RequestParam LocalDate du, @RequestParam LocalDate au) {
        return rapprochement.lister(du, au);
    }

    @PostMapping("/api/v1/mobile-money/rapprochements")
    @PreAuthorize(GESTION)
    public RapprochementVue rapprocher(@RequestParam LocalDate date) {
        return rapprochement.rapprocher(date);
    }

    @GetMapping("/api/v1/mobile-money/rapprochements/{id}/ecarts")
    @PreAuthorize(GESTION)
    public List<EcartVue> ecarts(@PathVariable UUID id) {
        return rapprochement.ecarts(id);
    }

    @PostMapping("/api/v1/mobile-money/ecarts/{id}/traitement")
    @PreAuthorize(GESTION)
    public EcartVue traiter(@PathVariable UUID id, @Valid @RequestBody DemandeMotif d) {
        return rapprochement.traiter(id, d.motif());
    }
}
