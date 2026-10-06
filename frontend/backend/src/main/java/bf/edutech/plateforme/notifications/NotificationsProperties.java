package bf.edutech.plateforme.notifications;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres d'envoi (préfixe {@code app.notifications}).
 *
 * @param envoiAutomatique active le worker planifié (désactivé pendant les tests)
 * @param tailleLot        nombre de messages réservés à chaque passage
 * @param bail             durée de réservation d'un message par une instance
 * @param maxTentatives    tentatives avant l'échec définitif
 * @param seuilCoupeCircuit échecs consécutifs qui suspendent les envois
 * @param pauseCoupeCircuit durée de la suspension
 */
@ConfigurationProperties(prefix = "app.notifications")
public record NotificationsProperties(boolean envoiAutomatique, int tailleLot, Duration bail, int maxTentatives,
        int seuilCoupeCircuit, Duration pauseCoupeCircuit) {
}
