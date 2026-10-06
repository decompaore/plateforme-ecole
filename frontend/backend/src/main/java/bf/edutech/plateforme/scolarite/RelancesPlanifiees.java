package bf.edutech.plateforme.scolarite;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.scolarite.Vues.ResultatRelancesVue;
import bf.edutech.plateforme.socle.modules.Module;
import bf.edutech.plateforme.socle.modules.ModulesEtablissement;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Relance automatique des familles en retard, chaque lundi à 7 h 30 (heure de Ouagadougou),
 * pour toutes les écoles actives qui ne l'ont pas désactivée. Le délai minimal entre deux
 * relances d'un même élève (7 jours par défaut) et la clé des SMS évitent tout doublon, même
 * si plusieurs instances de l'API exécutent la tâche.
 */
@Component
class RelancesPlanifiees {

    private static final Logger LOG = LoggerFactory.getLogger(RelancesPlanifiees.class);

    private final RelancesService relances;
    private final JdbcTemplate jdbc;
    private final boolean actives;

    private final ModulesEtablissement modules;

    RelancesPlanifiees(RelancesService relances, JdbcTemplate jdbc, ModulesEtablissement modules,
            @Value("${app.scolarite.relances-automatiques:true}") boolean actives) {
        this.relances = relances;
        this.modules = modules;
        this.jdbc = jdbc;
        this.actives = actives;
    }

    @Scheduled(cron = "${app.scolarite.cron-relances:0 30 7 * * MON}", zone = "Africa/Ouagadougou")
    void relancerChaqueSemaine() {
        if (actives) {
            relancerToutesLesEcoles();
        }
    }

    int relancerToutesLesEcoles() {
        List<UUID> ecoles = jdbc.queryForList("select tenant_id from ecoles_actives()", UUID.class);
        int envoyees = 0;
        for (UUID ecole : ecoles) {
            if (!modules.actif(ecole, Module.SCOLARITE)) {
                continue;
            }
            try {
                envoyees += TenantContext.executerPour(ecole, relances::relancerSiAutomatique)
                        .map(ResultatRelancesVue::envoyees).orElse(0);
            } catch (RuntimeException e) {
                LOG.warn("Relances automatiques impossibles pour l'école {} : {}", ecole, e.getMessage());
            }
        }
        LOG.info("Relances automatiques : {} SMS pour {} école(s)", envoyees, ecoles.size());
        return envoyees;
    }
}
