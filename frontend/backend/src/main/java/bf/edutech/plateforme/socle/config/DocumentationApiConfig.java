package bf.edutech.plateforme.socle.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Documentation OpenAPI (Swagger UI en profil dev) avec authentification par
 * jeton : bouton « Authorize » pour coller le jeton d'accès.
 */
@Configuration(proxyBeanMethods = false)
public class DocumentationApiConfig {

    private static final String SCHEMA = "jetonAcces";

    @Bean
    OpenAPI documentationApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("API – Plateforme de gestion des établissements secondaires")
                        .version("v1")
                        .description("Socle : établissements, comptes, connexion multi-établissements, membres, audit."))
                .addSecurityItem(new SecurityRequirement().addList(SCHEMA))
                .components(new Components().addSecuritySchemes(SCHEMA,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
