package bf.edutech.plateforme.scolarite;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Paramètres de scolarité de l'établissement : taux de prise en charge par défaut
 * (boursier 100 %, semi-boursier 50 %) et délai minimal entre deux relances.
 * Valeurs par défaut créées au premier accès.
 */
@Service
public class ParametresScolariteService {

    public record ParametresScolarite(BigDecimal tauxBoursier, BigDecimal tauxSemiBoursier,
            Integer delaiRelanceJours) {

        /** Part de l'organisme (en %) pour un statut, en l'absence de prise en charge enregistrée. */
        BigDecimal tauxParDefaut(StatutBourse statut) {
            return switch (statut) {
                case BOURSIER -> tauxBoursier;
                case SEMI_BOURSIER -> tauxSemiBoursier;
                case NON_BOURSIER -> BigDecimal.ZERO;
            };
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    ParametresScolariteService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ParametresScolarite lire() {
        UtilisateurConnecte.etablissementActif();
        jdbc.update("insert into parametres_scolarite (tenant_id) values (tenant_courant()) on conflict do nothing");
        return jdbc.queryForObject("""
                select taux_boursier, taux_semi_boursier, delai_relance_jours
                from parametres_scolarite where tenant_id = tenant_courant()""",
                (l, n) -> new ParametresScolarite(l.getBigDecimal(1), l.getBigDecimal(2), l.getInt(3)));
    }

    /** Paramètres en vigueur, sans rien écrire (valeurs par défaut si jamais enregistrés). */
    @Transactional(readOnly = true)
    public ParametresScolarite valeurs() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select taux_boursier, taux_semi_boursier, delai_relance_jours
                from parametres_scolarite where tenant_id = tenant_courant()""",
                (l, n) -> new ParametresScolarite(l.getBigDecimal(1), l.getBigDecimal(2), l.getInt(3)))
                .stream().findFirst()
                .orElse(new ParametresScolarite(BigDecimal.valueOf(100), BigDecimal.valueOf(50), 7));
    }

    @Transactional
    public ParametresScolarite modifier(ParametresScolarite p) {
        lire();
        if (!valide(p.tauxBoursier()) || !valide(p.tauxSemiBoursier())) {
            throw new IllegalArgumentException("Les taux de prise en charge sont compris entre 1 et 100 %");
        }
        if (p.delaiRelanceJours() == null || p.delaiRelanceJours() < 1 || p.delaiRelanceJours() > 90) {
            throw new IllegalArgumentException("Le délai entre deux relances est compris entre 1 et 90 jours");
        }
        jdbc.update("""
                update parametres_scolarite set taux_boursier = ?, taux_semi_boursier = ?, delai_relance_jours = ?
                where tenant_id = tenant_courant()""",
                p.tauxBoursier(), p.tauxSemiBoursier(), p.delaiRelanceJours());
        audit.enregistrer("PARAMETRES_SCOLARITE_MODIFIES", null, null);
        return lire();
    }

    private static boolean valide(BigDecimal taux) {
        return taux != null && taux.compareTo(BigDecimal.ONE) >= 0 && taux.compareTo(BigDecimal.valueOf(100)) <= 0
                && taux.scale() <= 2;
    }
}
