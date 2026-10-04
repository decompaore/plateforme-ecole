package bf.edutech.plateforme.ateliers;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.ParametresVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Paramètres des ateliers, propres à chaque établissement : durée du mandat des responsables
 * (2 ans par défaut, ou sans limite) et fréquence des inventaires (semestrielle par défaut).
 */
@Service
public class ParametresAteliersService {

    static final ParametresVue DEFAUT = new ParametresVue(24, FrequenceInventaire.SEMESTRIELLE);

    private final JdbcTemplate jdbc;
    private final AccesAteliers acces;
    private final AuditService audit;

    ParametresAteliersService(JdbcTemplate jdbc, AccesAteliers acces, AuditService audit) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ParametresVue lire() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select duree_mandat_mois, frequence_inventaire from parametres_ateliers
                where tenant_id = tenant_courant()""",
                (l, n) -> new ParametresVue(l.getObject(1, Integer.class),
                        FrequenceInventaire.valueOf(l.getString(2))))
                .stream().findFirst().orElse(DEFAUT);
    }

    @Transactional
    public ParametresVue modifier(ParametresVue p) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("paramètres des ateliers");
        if (p.dureeMandatMois() != null && (p.dureeMandatMois() < 6 || p.dureeMandatMois() > 120)) {
            throw new IllegalArgumentException("La durée du mandat est comprise entre 6 et 120 mois (ou sans limite)");
        }
        FrequenceInventaire frequence = p.frequenceInventaire() == null ? FrequenceInventaire.SEMESTRIELLE
                : p.frequenceInventaire();
        jdbc.update("""
                insert into parametres_ateliers (tenant_id, duree_mandat_mois, frequence_inventaire)
                values (tenant_courant(), ?, ?)
                on conflict (tenant_id) do update
                set duree_mandat_mois = excluded.duree_mandat_mois, frequence_inventaire = excluded.frequence_inventaire""",
                p.dureeMandatMois(), frequence.name());
        audit.enregistrer("PARAMETRES_ATELIERS_MODIFIES", "ateliers",
                Map.of("dureeMandatMois", p.dureeMandatMois() == null ? "sans limite" : p.dureeMandatMois(),
                        "frequenceInventaire", frequence.name()));
        return new ParametresVue(p.dureeMandatMois(), frequence);
    }
}
