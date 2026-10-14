package bf.edutech.plateforme.progression;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.progression.AccesMatiere.Contexte;
import bf.edutech.plateforme.progression.Vues.Domaine;
import bf.edutech.plateforme.progression.Vues.SuiviProgressionVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Visa mensuel des cahiers de textes (v0.47) : chaque mois, le censeur (matières générales) et le chef des
 * travaux (matières techniques) visent le cahier de textes de chaque classe pour vérifier la conformité et le rythme
 * des cours. Un mois visé est figé : l'enseignant n'y ajoute, ne modifie ni ne supprime plus de séance.
 */
@Service
public class VisasCahierService {

    public record VisaCahierVue(UUID id, UUID classeId, UUID matiereId, String mois, int seances,
            String observation, String visePar, Instant viseLe) {
    }

    public record SuiviCahierVue(UUID classeId, String classeCode, UUID matiereId, String matiereLibelle,
            Domaine domaine, String enseignant, String mois, int seances, boolean vise, String visePar,
            Instant viseLe) {
    }

    private final JdbcTemplate jdbc;
    private final AccesMatiere acces;
    private final ProgressionService progression;
    private final AuditService audit;
    private final Clock horloge;

    VisasCahierService(JdbcTemplate jdbc, AccesMatiere acces, ProgressionService progression, AuditService audit,
            Clock horloge) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.progression = progression;
        this.audit = audit;
        this.horloge = horloge;
    }

    /** Vise le cahier de textes d'une matière pour un mois écoulé ou en cours ; un mois ne se vise qu'une fois. */
    @Transactional
    public VisaCahierVue viser(UUID classeId, UUID matiereId, YearMonth mois, String observation) {
        Contexte c = acces.contexte(classeId, matiereId);
        if (!c.superviseur()) {
            throw new AccesRefuseException("Le visa du cahier de textes de " + c.matiere().matiereLibelle()
                    + " revient à la direction qui supervise cette matière");
        }
        if (mois == null) {
            throw new IllegalArgumentException("Indiquez le mois visé (AAAA-MM)");
        }
        if (mois.isAfter(YearMonth.now(horloge))) {
            throw new RegleMetierException("MOIS_A_VENIR", "On ne vise pas un mois qui n'a pas commencé");
        }
        String obs = observation == null || observation.isBlank() ? null : observation.strip();
        if (obs != null && obs.length() > 500) {
            throw new IllegalArgumentException("Observation de 500 caractères au plus");
        }
        if (estVise(classeId, matiereId, mois.atDay(1))) {
            throw new RegleMetierException("CAHIER_DEJA_VISE", "Le cahier de textes de " + c.matiere().matiereLibelle()
                    + " en " + c.classe().code() + " est déjà visé pour " + mois);
        }
        Integer seances = jdbc.queryForObject("""
                select count(*) from seance_cahier
                where classe_id = ? and matiere_id = ? and date_seance >= ? and date_seance < ?""", Integer.class,
                classeId, matiereId, Date.valueOf(mois.atDay(1)), Date.valueOf(mois.plusMonths(1).atDay(1)));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into visa_cahier (tenant_id, id, classe_id, matiere_id, mois, seances, observation, vise_par, vise_le)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?)""", id, classeId, matiereId,
                Date.valueOf(mois.atDay(1)), seances, obs, UtilisateurConnecte.id(),
                java.sql.Timestamp.from(horloge.instant()));
        audit.enregistrer("CAHIER_TEXTES_VISE", c.classe().code() + " " + c.matiere().matiereLibelle(),
                Map.of("mois", mois.toString(), "seances", seances));
        return visas(classeId, matiereId).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    /** Visas d'une matière, le plus récent d'abord : l'enseignant et la direction du domaine. */
    @Transactional(readOnly = true)
    public List<VisaCahierVue> visas(UUID classeId, UUID matiereId) {
        acces.contexte(classeId, matiereId).exigerLecture();
        return jdbc.query("""
                select v.id, v.mois, v.seances, v.observation, u.nom || ' ' || u.prenoms, v.vise_le
                from visa_cahier v left join utilisateur u on u.id = v.vise_par
                where v.classe_id = ? and v.matiere_id = ? order by v.mois desc""",
                (rs, n) -> new VisaCahierVue(rs.getObject(1, UUID.class), classeId, matiereId,
                        YearMonth.from(rs.getDate(2).toLocalDate()).toString(), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()),
                classeId, matiereId);
    }

    /** Tableau du mois pour la direction : chaque matière de son domaine, séances notées, visée ou non. */
    @Transactional(readOnly = true)
    public List<SuiviCahierVue> suivi(UUID anneeId, YearMonth mois) {
        YearMonth m = mois != null ? mois : YearMonth.now(horloge);
        List<SuiviProgressionVue> lignes = progression.suivi(anneeId);
        Map<String, Integer> seances = new HashMap<>();
        jdbc.query("""
                select s.classe_id, s.matiere_id, count(*) from seance_cahier s
                join classe c on c.tenant_id = s.tenant_id and c.id = s.classe_id
                where c.annee_id = ? and s.date_seance >= ? and s.date_seance < ?
                group by s.classe_id, s.matiere_id""", (java.sql.ResultSet rs) -> {
            seances.put(rs.getObject(1, UUID.class) + "/" + rs.getObject(2, UUID.class), rs.getInt(3));
        }, anneeId, Date.valueOf(m.atDay(1)), Date.valueOf(m.plusMonths(1).atDay(1)));
        Map<String, Object[]> visas = new HashMap<>();
        jdbc.query("""
                select v.classe_id, v.matiere_id, u.nom || ' ' || u.prenoms, v.vise_le from visa_cahier v
                join classe c on c.tenant_id = v.tenant_id and c.id = v.classe_id
                left join utilisateur u on u.id = v.vise_par
                where c.annee_id = ? and v.mois = ?""", (java.sql.ResultSet rs) -> {
            visas.put(rs.getObject(1, UUID.class) + "/" + rs.getObject(2, UUID.class),
                    new Object[] { rs.getString(3), rs.getTimestamp(4).toInstant() });
        }, anneeId, Date.valueOf(m.atDay(1)));
        return lignes.stream().map(l -> {
            String cle = l.classeId() + "/" + l.matiereId();
            Object[] v = visas.get(cle);
            return new SuiviCahierVue(l.classeId(), l.classeCode(), l.matiereId(), l.matiereLibelle(), l.domaine(),
                    l.enseignant(), m.toString(), seances.getOrDefault(cle, 0), v != null,
                    v == null ? null : (String) v[0], v == null ? null : (Instant) v[1]);
        }).toList();
    }

    /** Le mois de cette date est-il visé pour cette matière ? (le cahier y est alors figé) */
    boolean estVise(UUID classeId, UUID matiereId, LocalDate jour) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from visa_cahier where classe_id = ? and matiere_id = ? and mois = ?)""",
                Boolean.class, classeId, matiereId, Date.valueOf(YearMonth.from(jour).atDay(1))));
    }
}
