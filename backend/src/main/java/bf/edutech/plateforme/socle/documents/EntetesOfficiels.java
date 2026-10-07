package bf.edutech.plateforme.socle.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * En-tête officiel de l'établissement actif, d'après son rattachement (pays, ministère,
 * directions) et son logo. Utilisé par tous les documents PDF : bulletins, reçus, fiches,
 * exports de tableaux.
 */
@Service
public class EntetesOfficiels {

    private final JdbcTemplate jdbc;

    EntetesOfficiels(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** En-tête de l'établissement actif (transaction ouverte pour lui : le logo est cloisonné). */
    @Transactional(readOnly = true)
    public EnteteOfficiel courant() {
        UUID tenant = TenantContext.courant()
                .orElseThrow(() -> new IllegalStateException("Aucun établissement actif"));
        String[] t = jdbc.queryForObject("select nom, direction_id::text from tenant where id = ?",
                (l, n) -> new String[] { l.getString(1), l.getString(2) }, tenant);
        List<byte[]> logos = jdbc.query("select contenu from logo_etablissement where tenant_id = ?",
                (l, n) -> l.getBytes(1), tenant);
        byte[] logo = logos.isEmpty() ? null : logos.get(0);
        if (t[1] == null) {
            return new EnteteOfficiel(null, null, List.of(), t[0], logo);
        }
        List<String> autorites = new ArrayList<>();
        String[] pays = jdbc.queryForObject("""
                select p.nom, p.devise_nationale, m.nom
                from direction d join ministere m on m.id = d.ministere_id join pays p on p.id = m.pays_id
                where d.id = ?::uuid""", (l, n) -> new String[] { l.getString(1), l.getString(2), l.getString(3) },
                t[1]);
        autorites.add(pays[2]);
        autorites.addAll(jdbc.queryForList("select nom from directions_ascendantes(?::uuid)", String.class, t[1]));
        return new EnteteOfficiel(pays[0], pays[1], autorites, t[0], logo);
    }
}
