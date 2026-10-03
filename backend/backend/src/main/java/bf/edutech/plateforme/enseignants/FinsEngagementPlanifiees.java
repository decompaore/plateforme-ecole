package bf.edutech.plateforme.enseignants;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Clôture quotidienne des engagements arrivés à échéance (fin programmée ou fin de
 * contrat de vacataire), chaque nuit à 0 h 15, heure de Ouagadougou : statut TERMINE,
 * rôle d'enseignant retiré, matières libérées. Sans effet si rien n'est échu : plusieurs
 * instances de l'API peuvent exécuter la tâche sans risque.
 */
@Component
class FinsEngagementPlanifiees {

    private static final Logger LOG = LoggerFactory.getLogger(FinsEngagementPlanifiees.class);

    private final EnseignantsService service;
    private final JdbcTemplate jdbc;

    FinsEngagementPlanifiees(EnseignantsService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${app.enseignants.cron-fins:0 15 0 * * *}", zone = "Africa/Ouagadougou")
    void clore() {
        cloreToutesLesEcoles();
    }

    int cloreToutesLesEcoles() {
        List<UUID> ecoles = jdbc.queryForList("select tenant_id from ecoles_actives()", UUID.class);
        int closes = 0;
        for (UUID ecole : ecoles) {
            try {
                closes += TenantContext.executerPour(ecole, service::terminerEchus);
            } catch (RuntimeException e) {
                LOG.warn("Clôture des engagements échus impossible pour l'école {} : {}", ecole, e.getMessage());
            }
        }
        if (closes > 0) {
            LOG.info("Engagements échus clos : {}", closes);
        }
        return closes;
    }
}
