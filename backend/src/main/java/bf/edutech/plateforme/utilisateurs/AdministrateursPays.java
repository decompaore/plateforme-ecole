package bf.edutech.plateforme.utilisateurs;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Pays administré par un compte, s'il est administrateur pays actif. */
@Component
class AdministrateursPays {

    private final JdbcTemplate jdbc;

    AdministrateursPays(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PaysAdministre> de(UUID utilisateurId) {
        return jdbc.query("""
                select p.id, p.code, p.nom from administrateur_pays a join pays p on p.id = a.pays_id
                where a.utilisateur_id = ? and a.actif""",
                (l, n) -> new PaysAdministre(l.getObject(1, UUID.class), l.getString(2), l.getString(3)), utilisateurId)
                .stream().findFirst();
    }
}
