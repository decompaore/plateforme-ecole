package bf.edutech.plateforme.utilisateurs;

import java.time.Instant;
import java.util.UUID;

/**
 * Appareil connecté à un compte. {@code courant} : l'appareil qui fait la demande ;
 * {@code etablissement} : établissement de la session (null pour la plateforme).
 */
public record AppareilVue(UUID id, String appareil, Instant ouverteLe, Instant dernierUsage, String etablissement,
        boolean courant) {
}
