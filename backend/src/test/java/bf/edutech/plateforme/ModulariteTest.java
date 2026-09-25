package bf.edutech.plateforme;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Vérifie les frontières entre modules : pas de dépendance circulaire,
 * pas d'accès aux éléments internes d'un autre module.
 * Ce test n'a pas besoin de base de données.
 */
class ModulariteTest {

    @Test
    void lesModulesRespectentLeursFrontieres() {
        ApplicationModules.of(PlateformeApplication.class).verify();
    }
}
