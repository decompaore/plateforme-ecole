package bf.edutech.plateforme.mobilemoney;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tâches planifiées : chaque minute, les transactions dont le délai de confirmation est dépassé
 * (statut consulté chez l'agrégateur, puis confirmée ou expirée) ; chaque nuit à 2 h 30
 * (heure de Ouagadougou), le rapprochement de la veille pour toutes les écoles.
 * Plusieurs instances de l'API peuvent tourner : chaque traitement est idempotent et verrouillé.
 */
@Component
class TachesMobileMoney {

    private static final Logger LOG = LoggerFactory.getLogger(TachesMobileMoney.class);
    private static final ZoneId OUAGADOUGOU = ZoneId.of("Africa/Ouagadougou");

    private final PaiementsMobileMoneyService paiements;
    private final RapprochementService rapprochement;
    private final MobileMoneyProperties proprietes;
    private final Clock horloge;

    TachesMobileMoney(PaiementsMobileMoneyService paiements, RapprochementService rapprochement,
            MobileMoneyProperties proprietes, Clock horloge) {
        this.paiements = paiements;
        this.rapprochement = rapprochement;
        this.proprietes = proprietes;
        this.horloge = horloge;
    }

    @Scheduled(fixedDelayString = "${app.mobile-money.intervalle-expiration-ms:60000}", initialDelay = 60_000)
    void expirer() {
        if (proprietes.tachesAutomatiques()) {
            try {
                paiements.traiterEchues();
            } catch (RuntimeException e) {
                LOG.warn("Traitement des transactions Mobile Money échues interrompu : {}", e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${app.mobile-money.cron-rapprochement:0 30 2 * * *}", zone = "Africa/Ouagadougou")
    void rapprocherLaVeille() {
        if (proprietes.tachesAutomatiques()) {
            LocalDate hier = LocalDate.now(horloge.withZone(OUAGADOUGOU)).minusDays(1);
            LOG.info("Rapprochement Mobile Money du {} : {} école(s)", hier, rapprochement.rapprocherToutes(hier));
        }
    }
}
