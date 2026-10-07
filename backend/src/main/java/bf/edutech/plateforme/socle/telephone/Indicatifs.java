package bf.edutech.plateforme.socle.telephone;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import bf.edutech.plateforme.socle.config.ParametresPlateforme;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Indicatif téléphonique à appliquer aux numéros saisis sans indicatif : celui du pays de
 * l'établissement actif (d'après son rattachement), sinon celui de la plateforme.
 */
@Service
public class Indicatifs {

    /** Indicatif (ex. +226) et longueur d'un numéro national (ex. 8 chiffres). */
    public record Indicatif(String prefixe, int longueur) {
    }

    private final JdbcTemplate jdbc;
    private final ParametresPlateforme parametres;

    Indicatifs(JdbcTemplate jdbc, ParametresPlateforme parametres) {
        this.jdbc = jdbc;
        this.parametres = parametres;
    }

    /** Indicatif de l'établissement actif, ou celui de la plateforme hors établissement. */
    public Indicatif courant() {
        return TenantContext.courant().map(this::pour).orElseGet(this::plateforme);
    }

    public Indicatif pour(UUID tenantId) {
        List<Indicatif> trouves = jdbc.query("""
                select p.indicatif_telephone, p.longueur_numero
                from tenant t join direction d on d.id = t.direction_id
                join ministere m on m.id = d.ministere_id join pays p on p.id = m.pays_id
                where t.id = ?""", (l, n) -> new Indicatif(l.getString(1), l.getInt(2)), tenantId);
        return trouves.isEmpty() ? plateforme() : trouves.get(0);
    }

    public Indicatif plateforme() {
        return new Indicatif(parametres.indicatifTelephone(), NumeroTelephone.LONGUEUR_NATIONALE);
    }

    /** Numéro au format international, selon l'établissement actif. */
    public String normaliser(String saisie) {
        Indicatif i = courant();
        return NumeroTelephone.normaliser(saisie, i.prefixe(), i.longueur());
    }
}
