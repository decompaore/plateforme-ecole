package bf.edutech.plateforme.mobilemoney;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres du paiement Mobile Money (préfixe {@code app.mobile-money}).
 *
 * @param cleChiffrement     clé AES-256 en Base64 (32 octets) qui chiffre les clés des agrégateurs en base
 * @param dureeValidite      délai laissé au parent pour confirmer sur son téléphone
 * @param tachesAutomatiques expiration et rapprochement planifiés (désactivés pendant les tests)
 * @param simulateurAutorise autorise l'agrégateur SIMULATEUR (développement et tests uniquement)
 */
@ConfigurationProperties(prefix = "app.mobile-money")
public record MobileMoneyProperties(String cleChiffrement, Duration dureeValidite, boolean tachesAutomatiques,
        boolean simulateurAutorise) {
}
