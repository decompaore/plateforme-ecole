package bf.edutech.plateforme.mobilemoney;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.scolarite.DisponibiliteMobileMoney;

/** Compte marchand Mobile Money de l'établissement actif : enregistré et actif. */
@Component
class DisponibiliteMobileMoneyEtablissement implements DisponibiliteMobileMoney {

    private final JdbcTemplate jdbc;

    DisponibiliteMobileMoneyEtablissement(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean configure() {
        return !jdbc.queryForList(
                "select 1 from configuration_mobile_money where tenant_id = tenant_courant() and actif", Integer.class)
                .isEmpty();
    }
}
