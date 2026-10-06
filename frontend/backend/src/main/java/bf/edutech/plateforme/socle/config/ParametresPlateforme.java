package bf.edutech.plateforme.socle.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Paramètres généraux (préfixe {@code app.plateforme}).
 *
 * @param indicatifTelephone indicatif ajouté aux numéros saisis sans indicatif (ex. +226)
 */
@Validated
@ConfigurationProperties(prefix = "app.plateforme")
public record ParametresPlateforme(
        @NotBlank @Pattern(regexp = "^\\+[0-9]{1,4}$") String indicatifTelephone) {
}
