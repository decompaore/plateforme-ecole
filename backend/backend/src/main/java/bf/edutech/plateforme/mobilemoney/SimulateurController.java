package bf.edutech.plateforme.mobilemoney;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.mobilemoney.PaiementsMobileMoneyService.TransactionVue;

/**
 * Profil « dev » uniquement : joue le rôle du parent qui confirme (ou refuse) sur son
 * téléphone, puis déclenche la vérification comme le ferait la notification de l'agrégateur.
 */
@RestController
@Profile("dev")
class SimulateurController {

    private final AgregateurSimule simulateur;
    private final ConfigurationMobileMoneyService configuration;
    private final PaiementsMobileMoneyService paiements;

    SimulateurController(AgregateurSimule simulateur, ConfigurationMobileMoneyService configuration,
            PaiementsMobileMoneyService paiements) {
        this.simulateur = simulateur;
        this.configuration = configuration;
        this.paiements = paiements;
    }

    @PostMapping("/api/v1/simulateur/mobile-money/{transactionId}")
    @PreAuthorize("hasAnyRole('INTENDANT','ADMIN_ECOLE','PARENT')")
    public TransactionVue simuler(@PathVariable UUID transactionId, @RequestParam(defaultValue = "true") boolean accepter,
            @RequestParam(required = false) Long montant) {
        String marchand = configuration.active().identifiants().identifiantMarchand();
        String reference = paiements.verifier(transactionId).reference();
        if (!accepter) {
            simulateur.refuser(marchand, reference);
        } else if (montant != null) {
            simulateur.confirmer(marchand, reference, montant);
        } else {
            simulateur.confirmer(marchand, reference);
        }
        return paiements.verifier(transactionId);
    }
}
