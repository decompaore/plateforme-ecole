package bf.edutech.plateforme.notifications;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notifications « push » dans l'application (préfixe {@code app.push}, v0.50).
 *
 * @param clePublique      clé publique VAPID (base64url) ; vide : notifications push désactivées, tout part par SMS
 * @param clePrivee        clé privée VAPID (base64url), fournie par l'environnement, jamais par le dépôt
 * @param sujet            contact de l'exploitant donné aux services push ({@code mailto:…})
 * @param envoiAutomatique active le worker planifié (désactivé pendant les tests)
 * @param tailleLot        notifications traitées à chaque passage
 * @param bail             réservation d'une notification par une instance
 * @param maxTentatives    tentatives avant l'échec (et le SMS de secours)
 * @param dureeVie         durée pendant laquelle le service push garde un message pour un téléphone éteint
 */
@ConfigurationProperties(prefix = "app.push")
public record PushProperties(@DefaultValue("") String clePublique, @DefaultValue("") String clePrivee,
        @DefaultValue("mailto:exploitation@plateforme-ecoles.bf") String sujet,
        @DefaultValue("true") boolean envoiAutomatique, @DefaultValue("50") int tailleLot,
        @DefaultValue("2m") Duration bail, @DefaultValue("4") int maxTentatives,
        @DefaultValue("12h") Duration dureeVie) {

    public boolean actif() {
        return clePublique != null && !clePublique.isBlank() && clePrivee != null && !clePrivee.isBlank();
    }
}
