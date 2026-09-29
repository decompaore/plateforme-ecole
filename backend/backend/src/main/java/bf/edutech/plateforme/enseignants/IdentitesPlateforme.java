package bf.edutech.plateforme.enseignants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.enseignants.Vues.InvitationVue;

/**
 * Accès aux informations de niveau plateforme, hors cloisonnement, par les
 * fonctions SQL SECURITY DEFINER de la migration V5 : elles ne renvoient que le
 * strict nécessaire (un identifiant, un booléen, les invitations du compte connecté).
 */
@Component
class IdentitesPlateforme {

    private final JdbcTemplate jdbc;

    IdentitesPlateforme(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Identité existante pour ce téléphone ou ce matricule de la fonction publique. */
    Optional<UUID> rechercher(String telephone, String matriculeFp) {
        return Optional.ofNullable(jdbc.queryForObject("select enseignant_par_identifiant(?, ?)", UUID.class,
                telephone, matriculeFp));
    }

    /** Poste de titulaire (invité ou actif) sur la période, dans n'importe quel établissement. */
    boolean posteTitulaireOccupe(UUID enseignantId, LocalDate debut, LocalDate fin) {
        Boolean occupe = jdbc.queryForObject("select poste_titulaire_occupe(?, ?, ?)", Boolean.class, enseignantId,
                debut, fin);
        return Boolean.TRUE.equals(occupe);
    }

    List<InvitationVue> invitations(UUID utilisateurId) {
        return jdbc.query("""
                select engagement_id, tenant_id, tenant_nom, type, debut, fin, taux_horaire
                from invitations_enseignant(?)""",
                (ligne, n) -> new InvitationVue(ligne.getObject("engagement_id", UUID.class),
                        ligne.getObject("tenant_id", UUID.class), ligne.getString("tenant_nom"),
                        TypeEngagement.valueOf(ligne.getString("type")),
                        ligne.getObject("debut", LocalDate.class), ligne.getObject("fin", LocalDate.class),
                        ligne.getObject("taux_horaire", BigDecimal.class)),
                utilisateurId);
    }
}
