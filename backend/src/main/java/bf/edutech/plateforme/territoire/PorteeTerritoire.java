package bf.edutech.plateforme.territoire;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.Portee;

/**
 * Vérifie qu'un administrateur pays n'agit que dans son pays (pays, ministère, direction,
 * établissement). Sans effet pour le super administrateur. Un établissement non rattaché n'a
 * pas de pays : seul le super administrateur le gère.
 */
@Component
public class PorteeTerritoire {

    private static final String HORS_PAYS = "Hors du pays que vous administrez";

    private final JdbcTemplate jdbc;

    PorteeTerritoire(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Pays de l'administrateur pays ; vide pour le super administrateur. */
    public Optional<UUID> pays() {
        return Portee.pays();
    }

    public void verifierPays(UUID paysId) {
        pays().ifPresent(p -> exiger(p.equals(paysId)));
    }

    public void verifierMinistere(UUID ministereId) {
        pays().ifPresent(p -> exiger(p.equals(premier("select pays_id from ministere where id = ?", ministereId))));
    }

    public void verifierDirection(UUID directionId) {
        pays().ifPresent(p -> exiger(p.equals(premier("""
                select m.pays_id from direction d join ministere m on m.id = d.ministere_id where d.id = ?""", directionId))));
    }

    public void verifierEtablissement(UUID tenantId) {
        pays().ifPresent(p -> exiger(p.equals(paysEtablissement(tenantId))));
    }

    /** Pays d'un établissement d'après son rattachement (null s'il n'est pas rattaché). */
    public UUID paysEtablissement(UUID tenantId) {
        return premier("""
                select m.pays_id from tenant t join direction d on d.id = t.direction_id
                join ministere m on m.id = d.ministere_id where t.id = ?""", tenantId);
    }

    private UUID premier(String sql, UUID id) {
        List<UUID> r = jdbc.queryForList(sql, UUID.class, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private static void exiger(boolean condition) {
        if (!condition) {
            throw new AccesRefuseException(HORS_PAYS);
        }
    }
}
