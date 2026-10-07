package bf.edutech.plateforme.adoption;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres de la mesure de l'adoption (préfixe {@code app.adoption}).
 *
 * @param calculAutomatique calcul nocturne des mesures de la veille, et au démarrage des jours manquants
 * @param historiqueJours   au démarrage, jours passés recalculés s'ils ne l'ont jamais été (400 par défaut)
 */
@ConfigurationProperties(prefix = "app.adoption")
public record AdoptionProperties(Boolean calculAutomatique, int historiqueJours) {

    public AdoptionProperties {
        if (calculAutomatique == null) {
            calculAutomatique = true;
        }
        if (historiqueJours <= 0) {
            historiqueJours = 400;
        }
    }
}
