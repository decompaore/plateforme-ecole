package bf.edutech.plateforme.reversibilite;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres de l'export complet (préfixe {@code app.exports}).
 *
 * @param repertoire        dossier où les archives sont préparées et gardées (volume persistant en production)
 * @param dureeConservation durée pendant laquelle une archive prête reste téléchargeable (7 jours par défaut)
 * @param dureeMax          au-delà, un export resté « en cours » est considéré comme interrompu (1 heure)
 * @param dureeLien         validité d'un lien de téléchargement (10 minutes)
 * @param maxParJour        exports qu'un établissement peut demander en 24 heures (5)
 * @param purgeAutomatique  effacement horaire des archives expirées
 */
@ConfigurationProperties(prefix = "app.exports")
public record ExportsProperties(
        Path repertoire,
        Duration dureeConservation,
        Duration dureeMax,
        Duration dureeLien,
        int maxParJour,
        Boolean purgeAutomatique) {

    public ExportsProperties {
        if (repertoire == null) {
            repertoire = Path.of(System.getProperty("java.io.tmpdir"), "plateforme-exports");
        }
        if (dureeConservation == null || dureeConservation.isNegative() || dureeConservation.isZero()) {
            dureeConservation = Duration.ofDays(7);
        }
        if (dureeMax == null || dureeMax.isNegative() || dureeMax.isZero()) {
            dureeMax = Duration.ofHours(1);
        }
        if (dureeLien == null || dureeLien.isNegative() || dureeLien.isZero()) {
            dureeLien = Duration.ofMinutes(10);
        }
        if (maxParJour <= 0) {
            maxParJour = 5;
        }
        if (purgeAutomatique == null) {
            purgeAutomatique = true;
        }
    }
}
