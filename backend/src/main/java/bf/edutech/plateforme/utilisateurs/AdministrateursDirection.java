package bf.edutech.plateforme.utilisateurs;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Direction d'un compte, s'il est compte de direction actif (v0.37). */
@Component
class AdministrateursDirection {

    private final JdbcTemplate jdbc;

    AdministrateursDirection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<DirectionAdministree> de(UUID utilisateurId) {
        return jdbc.query("""
                select d.id, d.code, d.nom, chemin_direction(d.id), m.pays_id
                from administrateur_direction a join direction d on d.id = a.direction_id
                join ministere m on m.id = d.ministere_id
                where a.utilisateur_id = ? and a.actif""",
                (l, n) -> new DirectionAdministree(l.getObject(1, UUID.class), l.getString(2), l.getString(3),
                        l.getString(4), l.getObject(5, UUID.class)), utilisateurId)
                .stream().findFirst();
    }
}
