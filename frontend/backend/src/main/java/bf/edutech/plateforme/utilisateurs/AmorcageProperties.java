package bf.edutech.plateforme.utilisateurs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Création du premier super administrateur (préfixe {@code app.amorcage}).
 * Ignoré si aucun téléphone n'est fourni ou si un super administrateur existe déjà.
 */
@ConfigurationProperties(prefix = "app.amorcage")
public record AmorcageProperties(String superAdminTelephone, String superAdminMotDePasse, String superAdminNom,
        String superAdminPrenoms) {
}
