package bf.edutech.plateforme.socle.securite;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Paramètres de sécurité (préfixe {@code app.securite}).
 * <p>
 * Validés au démarrage : si le secret JWT est absent ou trop court,
 * l'application refuse de démarrer.
 *
 * @param jwtSecret                  clé HMAC encodée en Base64 (32 octets minimum)
 * @param emetteur                   valeur de la revendication « iss »
 * @param dureeJetonAcces            durée de vie du jeton d'accès (ex. 15m)
 * @param dureeJetonSelection        durée du jeton de choix d'établissement (ex. 5m)
 * @param dureeJetonRafraichissement durée du jeton de rafraîchissement (ex. 30d)
 * @param verifierSousDomaine        exiger que le sous-domaine corresponde à l'établissement
 * @param cookieSecurise             attribut Secure du cookie (faux uniquement en local)
 * @param originesAutorisees         origines CORS autorisées (application Angular)
 * @param maxEchecsConnexion         échecs avant verrouillage temporaire
 * @param dureeVerrouillage          durée du verrouillage
 * @param graceRafraichissement      délai pendant lequel un jeton qui vient d'être renouvelé reste accepté une
 *                                   fois (réponse perdue sur un réseau faible) ; 20 secondes par défaut
 */
@Validated
@ConfigurationProperties(prefix = "app.securite")
public record SecuriteProperties(
        @NotBlank @Size(min = 44, message = "Le secret JWT doit faire au moins 32 octets encodés en Base64") String jwtSecret,
        @NotBlank String emetteur,
        @NotNull Duration dureeJetonAcces,
        @NotNull Duration dureeJetonSelection,
        @NotNull Duration dureeJetonRafraichissement,
        boolean verifierSousDomaine,
        boolean cookieSecurise,
        List<String> originesAutorisees,
        int maxEchecsConnexion,
        @NotNull Duration dureeVerrouillage,
        Duration graceRafraichissement) {

    public SecuriteProperties {
        originesAutorisees = originesAutorisees == null ? List.of() : List.copyOf(originesAutorisees);
        if (maxEchecsConnexion <= 0) {
            maxEchecsConnexion = 5;
        }
        if (graceRafraichissement == null || graceRafraichissement.isNegative()) {
            graceRafraichissement = Duration.ofSeconds(20);
        }
    }
}
