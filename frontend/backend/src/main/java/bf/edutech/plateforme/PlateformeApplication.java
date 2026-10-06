package bf.edutech.plateforme;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Point d'entrée de l'API.
 * <p>
 * Monolithe modulaire (Spring Modulith) : chaque sous-paquetage direct
 * (socle, utilisateurs, plateforme, ...) est un module métier.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PlateformeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlateformeApplication.class, args);
    }
}
