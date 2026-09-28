package bf.edutech.plateforme.etablissement;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Indique si des élèves sont inscrits dans une classe.
 * <p>
 * Requête SQL directe plutôt qu'une dépendance vers le module Élèves : le
 * module Établissement ne doit pas dépendre des modules qui l'utilisent.
 */
@Component
class OccupationClasses {

    private final JdbcTemplate jdbc;

    OccupationClasses(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean aDesInscrits(UUID classeId) {
        Boolean existe = jdbc.queryForObject("select exists(select 1 from inscription where classe_id = ?)",
                Boolean.class, classeId);
        return Boolean.TRUE.equals(existe);
    }
}
