package bf.edutech.plateforme.utilisateurs;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Intérim du chef d'établissement (v0.47) : en son absence, le censeur (ou le chef des travaux) assure la
 * suppléance. Le chef d'établissement le désigne pour une période ; pendant celle-ci, l'intérimaire reçoit les
 * droits du chef d'établissement à sa prochaine connexion ou au prochain renouvellement de sa session
 * (fonction SQL {@code auth_etablissements_utilisateur}). Un intérimaire ne désigne pas lui-même d'intérim.
 */
@Service
public class InterimsService {

    static final int DUREE_MAX_JOURS = 180;

    public record InterimVue(UUID id, UUID utilisateurId, String interimaire, LocalDate du, LocalDate au,
            String motif, String designePar, boolean enCours, boolean termine) {
    }

    private final JdbcTemplate jdbc;
    private final MembresService membres;
    private final AuditService audit;
    private final Clock horloge;

    InterimsService(JdbcTemplate jdbc, MembresService membres, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.membres = membres;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<InterimVue> lister() {
        UtilisateurConnecte.etablissementActif();
        LocalDate aujourdhui = LocalDate.now(horloge);
        return jdbc.query("""
                select i.id, i.utilisateur_id, u.nom || ' ' || u.prenoms, i.du, least(i.au, coalesce(i.termine_le, i.au)),
                       i.motif, d.nom || ' ' || d.prenoms
                from interim_direction i
                join utilisateur u on u.id = i.utilisateur_id
                left join utilisateur d on d.id = i.designe_par
                order by i.du desc""", (rs, n) -> {
            LocalDate du = rs.getDate(4).toLocalDate();
            LocalDate au = rs.getDate(5).toLocalDate();
            return new InterimVue(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), du, au,
                    rs.getString(6), rs.getString(7), !aujourdhui.isBefore(du) && !aujourdhui.isAfter(au),
                    aujourdhui.isAfter(au) || au.isBefore(du));
        });
    }

    @Transactional
    public InterimVue designer(UUID utilisateurId, LocalDate du, LocalDate au, String motif) {
        exigerChefEnTitre();
        if (utilisateurId == null || du == null || au == null) {
            throw new IllegalArgumentException("Indiquez l'intérimaire et la période");
        }
        String m = motif == null ? "" : motif.strip();
        if (m.isEmpty() || m.length() > 200) {
            throw new IllegalArgumentException("Indiquez le motif de l'absence (200 caractères au plus)");
        }
        LocalDate aujourdhui = LocalDate.now(horloge);
        if (du.isBefore(aujourdhui)) {
            throw new IllegalArgumentException("L'intérim ne peut pas commencer dans le passé");
        }
        if (au.isBefore(du)) {
            throw new IllegalArgumentException("La fin de l'intérim doit suivre son début");
        }
        if (ChronoUnit.DAYS.between(du, au) + 1 > DUREE_MAX_JOURS) {
            throw new IllegalArgumentException("Un intérim dure " + DUREE_MAX_JOURS + " jours au plus");
        }
        if (utilisateurId.equals(UtilisateurConnecte.id())) {
            throw new IllegalArgumentException("Désignez une autre personne que vous-même");
        }
        boolean eligible = membres.lister().stream().anyMatch(x -> x.actif() && x.utilisateurId().equals(utilisateurId)
                && (x.role() == Role.CENSEUR || x.role() == Role.CHEF_TRAVAUX));
        if (!eligible) {
            throw new RegleMetierException("INTERIMAIRE_NON_ELIGIBLE",
                    "L'intérim du chef d'établissement est assuré par le censeur ou, à défaut, le chef des travaux");
        }
        Boolean chevauche = jdbc.queryForObject("""
                select exists(select 1 from interim_direction
                              where du <= ? and least(au, coalesce(termine_le, au)) >= ?
                                and coalesce(termine_le, au) >= du)""", Boolean.class, Date.valueOf(au),
                Date.valueOf(du));
        if (Boolean.TRUE.equals(chevauche)) {
            throw new RegleMetierException("INTERIM_EXISTANT", "Un intérim est déjà prévu sur cette période");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into interim_direction (tenant_id, id, utilisateur_id, du, au, motif, designe_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?)""", id, utilisateurId, Date.valueOf(du), Date.valueOf(au),
                m, UtilisateurConnecte.id());
        audit.enregistrer("INTERIM_DESIGNE", utilisateurId.toString(), Map.of("du", du, "au", au));
        return trouver(id);
    }

    /** Fin anticipée (retour du chef d'établissement) : l'intérimaire perd ses droits au renouvellement de session. */
    @Transactional
    public InterimVue terminer(UUID id) {
        exigerChefEnTitre();
        InterimVue i = trouver(id);
        if (i.termine()) {
            throw new RegleMetierException("INTERIM_TERMINE", "Cet intérim est déjà terminé");
        }
        LocalDate hier = LocalDate.now(horloge).minusDays(1);
        LocalDate fin = hier.isBefore(i.du()) ? i.du().minusDays(1) : hier;
        jdbc.update("update interim_direction set termine_le = ? where id = ?", Date.valueOf(fin), id);
        audit.enregistrer("INTERIM_TERMINE", i.utilisateurId().toString(), Map.of("fin", fin));
        return trouver(id);
    }

    private InterimVue trouver(UUID id) {
        return lister().stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Intérim introuvable"));
    }

    /** Le chef d'établissement en titre (membre ADMIN_ECOLE), pas un intérimaire. */
    private void exigerChefEnTitre() {
        UUID moi = UtilisateurConnecte.id();
        boolean titulaire = membres.lister().stream()
                .anyMatch(x -> x.actif() && x.utilisateurId().equals(moi) && x.role() == Role.ADMIN_ECOLE);
        if (!titulaire) {
            throw new AccesRefuseException("Seul le chef d'établissement en titre désigne ou termine un intérim");
        }
    }
}
