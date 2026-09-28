package bf.edutech.plateforme.pedagogie;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Indique si un profil est déjà utilisé par une filière.
 * <p>
 * Requête SQL directe plutôt qu'une dépendance vers le module Établissement :
 * le module Pédagogie ne doit pas dépendre des modules qui l'utilisent.
 */
@Component
class UtilisationProfils {

    private final JdbcTemplate jdbc;

    UtilisationProfils(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean estUtilise(UUID profilId) {
        Boolean existe = jdbc.queryForObject("select exists(select 1 from filiere where profil_id = ?)",
                Boolean.class, profilId);
        return Boolean.TRUE.equals(existe);
    }
}
