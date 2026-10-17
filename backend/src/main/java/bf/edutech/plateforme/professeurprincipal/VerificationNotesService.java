package bf.edutech.plateforme.professeurprincipal;

import static bf.edutech.plateforme.professeurprincipal.ProfesseursPrincipauxService.instant;
import static bf.edutech.plateforme.professeurprincipal.ProfesseursPrincipauxService.texte;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.professeurprincipal.ProfesseursPrincipauxService.Classe;
import bf.edutech.plateforme.professeurprincipal.Vues.DonneesReclamation;
import bf.edutech.plateforme.professeurprincipal.Vues.ReclamationVue;
import bf.edutech.plateforme.professeurprincipal.Vues.StatutReclamation;
import bf.edutech.plateforme.professeurprincipal.Vues.StatutVerification;
import bf.edutech.plateforme.professeurprincipal.Vues.VerificationVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Vérification des notes d'une période par le professeur principal, avec les élèves (v0.49.1).
 * <ol>
 * <li>Le professeur principal parcourt avec la classe les notes et moyennes de la période (résultats de la classe) et
 * enregistre chaque réclamation : élève, matière, évaluation éventuelle (la note contestée est relevée), motif.
 * L'enseignant de la matière la reçoit dans sa messagerie.</li>
 * <li>L'enseignant (ou la direction des études) répond : note <b>corrigée</b> (la correction se fait dans la saisie
 * des notes, la période n'étant pas verrouillée ; la nouvelle note est relevée) ou <b>maintenue</b>, avec
 * justification.</li>
 * <li>Quand plus aucune réclamation n'attend, le professeur principal <b>atteste</b> la vérification. Une réclamation
 * enregistrée ensuite rouvre la vérification. Le conseil de classe et les bulletins attendent cette attestation.</li>
 * </ol>
 */
@Service
public class VerificationNotesService {

    private static final String[] DIRECTION = { "ADMIN_ECOLE", "CENSEUR" };

    private final JdbcTemplate jdbc;
    private final ProfesseursPrincipauxService pp;
    private final AuditService audit;

    VerificationNotesService(JdbcTemplate jdbc, ProfesseursPrincipauxService pp, AuditService audit) {
        this.jdbc = jdbc;
        this.pp = pp;
        this.audit = audit;
    }

    private record Periode(String libelle, boolean verrouillee) {
    }

    // ================================================================== consultation

    @Transactional(readOnly = true)
    public VerificationVue verification(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        Classe c = pp.classe(classeId);
        boolean estPp = pp.estProfesseurPrincipal(classeId);
        boolean direction = UtilisateurConnecte.aUnRole(DIRECTION);
        UUID moi = UtilisateurConnecte.id();
        if (!estPp && !direction && !pp.enseigneDans(moi, classeId)) {
            throw new AccesRefuseException("Vous n'enseignez pas dans la " + c.code());
        }
        Periode p = periode(c, periodeId);
        List<Object[]> etat = jdbc.query("""
                select v.statut, v.validee_le, u.nom || ' ' || u.prenoms, v.observations from verification_notes v
                left join utilisateur u on u.id = v.validee_par where v.classe_id = ? and v.periode_id = ?""",
                (l, n) -> new Object[] { l.getString(1), instant(l.getTimestamp(2)), l.getString(3), l.getString(4) },
                classeId, periodeId);
        StatutVerification statut = etat.isEmpty() ? StatutVerification.A_FAIRE
                : StatutVerification.valueOf((String) etat.get(0)[0]);
        String filtre = estPp || direction ? "" : " and r.enseignant_id = ?";
        Object[] args = estPp || direction ? new Object[] { classeId, periodeId }
                : new Object[] { classeId, periodeId, moi };
        List<ReclamationVue> reclamations = jdbc.query(SELECT + " where r.classe_id = ? and r.periode_id = ?" + filtre
                + " order by (r.statut <> 'OUVERTE'), r.enregistree_le", (l, n) -> reclamation(l), args);
        boolean ouvertes = reclamations.stream().anyMatch(r -> r.statut() == StatutReclamation.OUVERTE);
        String professeur = pp.professeurDe(classeId).map(pp::nom).orElse(null);
        return new VerificationVue(classeId, c.code(), periodeId, p.libelle(), p.verrouillee(), professeur, statut,
                etat.isEmpty() ? null : (java.time.Instant) etat.get(0)[1],
                etat.isEmpty() ? null : (String) etat.get(0)[2], etat.isEmpty() ? null : (String) etat.get(0)[3],
                reclamations, estPp, estPp && statut != StatutVerification.VALIDEE && !ouvertes);
    }

    /** Réclamations adressées à l'enseignant connecté (à traiter d'abord). */
    @Transactional(readOnly = true)
    public List<ReclamationVue> mesReclamations() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query(SELECT + " where r.enseignant_id = ? order by (r.statut <> 'OUVERTE'), r.enregistree_le desc"
                + " limit 200", (l, n) -> reclamation(l), UtilisateurConnecte.id());
    }

    // ================================================================== réclamations

    @Transactional
    public VerificationVue reclamer(UUID classeId, DonneesReclamation d) {
        UtilisateurConnecte.etablissementActif();
        Classe c = pp.classe(classeId);
        if (!pp.estProfesseurPrincipal(classeId)) {
            throw new AccesRefuseException("Le professeur principal de la " + c.code() + " recueille les réclamations");
        }
        if (d == null || d.periodeId() == null || d.inscriptionId() == null || d.matiereId() == null) {
            throw new IllegalArgumentException("Période, élève et matière obligatoires");
        }
        String motif = texte(d.motif(), 1000, "Motif");
        Periode p = periode(c, d.periodeId());
        if (p.verrouillee()) {
            throw new RegleMetierException("PERIODE_VERROUILLEE", "La période « " + p.libelle()
                    + " » est verrouillée : les notes ne peuvent plus être corrigées");
        }
        List<String> eleve = jdbc.queryForList("""
                select e.nom || ' ' || e.prenoms from inscription i join eleve e on e.tenant_id = i.tenant_id
                and e.id = i.eleve_id where i.id = ? and i.classe_id = ?""", String.class, d.inscriptionId(), classeId);
        if (eleve.isEmpty()) {
            throw new IllegalArgumentException("Élève hors de la " + c.code());
        }
        List<Object[]> matiere = jdbc.query("""
                select m.libelle, s.utilisateur_id from classe_matiere cm
                join matiere m on m.tenant_id = cm.tenant_id and m.id = cm.matiere_id
                left join engagement_enseignant g on g.tenant_id = cm.tenant_id and g.id = cm.engagement_id
                left join enseignant s on s.id = g.enseignant_id
                where cm.classe_id = ? and cm.matiere_id = ?""",
                (l, n) -> new Object[] { l.getString(1), l.getObject(2, UUID.class) }, classeId, d.matiereId());
        if (matiere.isEmpty()) {
            throw new IllegalArgumentException("Matière hors de la " + c.code());
        }
        String libelle = (String) matiere.get(0)[0];
        UUID enseignant = (UUID) matiere.get(0)[1];
        BigDecimal note = null;
        String evaluation = null;
        if (d.evaluationId() != null) {
            List<String> ev = jdbc.queryForList("""
                    select libelle from evaluation where id = ? and classe_id = ? and matiere_id = ? and periode_id = ?""",
                    String.class, d.evaluationId(), classeId, d.matiereId(), d.periodeId());
            if (ev.isEmpty()) {
                throw new IllegalArgumentException("L'évaluation n'est pas de cette matière et de cette période");
            }
            evaluation = ev.get(0);
            note = noteActuelle(d.evaluationId(), d.inscriptionId());
        }
        UUID id = UUID.randomUUID();
        UUID moi = UtilisateurConnecte.id();
        jdbc.update("""
                insert into reclamation_note (tenant_id, id, classe_id, periode_id, inscription_id, matiere_id,
                    evaluation_id, note_contestee, motif, enseignant_id, enregistree_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, classeId, d.periodeId(),
                d.inscriptionId(), d.matiereId(), d.evaluationId(), note, motif, enseignant, moi);
        // Une réclamation (re)met la vérification en cours
        jdbc.update("""
                insert into verification_notes (tenant_id, classe_id, periode_id) values (tenant_courant(), ?, ?)
                on conflict (tenant_id, classe_id, periode_id) do update
                set statut = 'EN_COURS', validee_le = null, validee_par = null""", classeId, d.periodeId());
        pp.informer(enseignant, "Réclamation de note — " + libelle + ", " + c.code(),
                "Le professeur principal de la " + c.code() + " vous transmet une réclamation (" + p.libelle()
                        + ").\n\nÉlève : " + eleve.get(0) + "\nMatière : " + libelle
                        + (evaluation == null ? "" : "\nÉvaluation : " + evaluation)
                        + (note == null ? "" : "\nNote contestée : " + nombre(note) + "/20") + "\n\nMotif : " + motif
                        + "\n\nCorrigez la note dans la saisie des notes si la réclamation est fondée, puis répondez "
                        + "dans « Réclamations de notes ».");
        audit.enregistrer("RECLAMATION_NOTE", c.code() + " / " + libelle, Map.of("eleve", eleve.get(0)));
        return verification(classeId, d.periodeId());
    }

    /** Réponse de l'enseignant de la matière (ou de la direction des études) : corrigée ou maintenue. */
    @Transactional
    public ReclamationVue repondre(UUID id, StatutReclamation decision, String reponseSaisie) {
        UtilisateurConnecte.etablissementActif();
        ReclamationVue r = jdbc.query(SELECT + " where r.id = ?", (l, n) -> reclamation(l), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Réclamation introuvable"));
        UUID moi = UtilisateurConnecte.id();
        List<UUID> titulaire = jdbc.queryForList("""
                select s.utilisateur_id from classe_matiere cm
                join engagement_enseignant g on g.tenant_id = cm.tenant_id and g.id = cm.engagement_id
                join enseignant s on s.id = g.enseignant_id where cm.classe_id = ? and cm.matiere_id = ?""",
                UUID.class, r.classeId(), r.matiereId());
        UUID enseignant = jdbc.queryForObject("select enseignant_id from reclamation_note where id = ?", UUID.class, id);
        if (!moi.equals(enseignant) && !titulaire.contains(moi) && !UtilisateurConnecte.aUnRole(DIRECTION)) {
            throw new AccesRefuseException("L'enseignant de " + r.matiere() + " répond à cette réclamation");
        }
        if (r.statut() != StatutReclamation.OUVERTE) {
            throw new RegleMetierException("RECLAMATION_TRAITEE", "Cette réclamation a déjà reçu une réponse");
        }
        if (decision == null || decision == StatutReclamation.OUVERTE) {
            throw new IllegalArgumentException("Décision attendue : note corrigée ou maintenue");
        }
        String reponse = decision == StatutReclamation.MAINTENUE ? texte(reponseSaisie, 1000, "Justification")
                : reponseSaisie == null || reponseSaisie.isBlank() ? null : texte(reponseSaisie, 1000, "Réponse");
        BigDecimal corrigee = null;
        if (decision == StatutReclamation.CORRIGEE && r.evaluationId() != null) {
            corrigee = noteActuelle(r.evaluationId(), r.inscriptionId());
            boolean inchangee = corrigee == null ? r.noteContestee() == null
                    : r.noteContestee() != null && corrigee.compareTo(r.noteContestee()) == 0;
            if (inchangee) {
                throw new RegleMetierException("NOTE_INCHANGEE", "La note de " + r.eleve() + " (" + r.evaluation()
                        + ") n'a pas changé : corrigez-la d'abord dans la saisie des notes");
            }
        }
        jdbc.update("""
                update reclamation_note set statut = ?, note_corrigee = ?, reponse = ?, repondue_par = ?,
                    repondue_le = now() where id = ?""", decision.name(), corrigee, reponse, moi, id);
        BigDecimal nouvelle = corrigee;
        pp.professeurDe(r.classeId()).ifPresent(prof -> pp.informer(prof,
                "Réclamation de note : " + (decision == StatutReclamation.CORRIGEE ? "note corrigée" : "note maintenue"),
                pp.nom(moi) + " a répondu à la réclamation de " + r.eleve() + " en " + r.matiere() + " ("
                        + r.periode() + ") : "
                        + (decision == StatutReclamation.CORRIGEE
                                ? "note corrigée" + (nouvelle == null ? "" : " (" + nombre(nouvelle) + "/20)")
                                : "note maintenue")
                        + "." + (reponse == null ? "" : "\n\n" + reponse)));
        audit.enregistrer("RECLAMATION_NOTE_" + decision.name(), r.classeCode() + " / " + r.matiere(),
                Map.of("eleve", r.eleve()));
        return jdbc.query(SELECT + " where r.id = ?", (l, n) -> reclamation(l), id).get(0);
    }

    // ================================================================== attestation

    @Transactional
    public VerificationVue valider(UUID classeId, UUID periodeId, String observationsSaisies) {
        UtilisateurConnecte.etablissementActif();
        Classe c = pp.classe(classeId);
        if (!pp.estProfesseurPrincipal(classeId)) {
            throw new AccesRefuseException("Le professeur principal de la " + c.code() + " atteste la vérification");
        }
        if (periodeId == null) {
            throw new IllegalArgumentException("Période obligatoire");
        }
        Periode p = periode(c, periodeId);
        Integer ouvertes = jdbc.queryForObject("""
                select count(*) from reclamation_note where classe_id = ? and periode_id = ? and statut = 'OUVERTE'""",
                Integer.class, classeId, periodeId);
        if (ouvertes != null && ouvertes > 0) {
            throw new RegleMetierException("RECLAMATIONS_EN_ATTENTE",
                    ouvertes + " réclamation(s) attendent la réponse de l'enseignant");
        }
        String observations = observationsSaisies == null || observationsSaisies.isBlank() ? null
                : texte(observationsSaisies, 2000, "Observations");
        UUID moi = UtilisateurConnecte.id();
        jdbc.update("""
                insert into verification_notes (tenant_id, classe_id, periode_id, statut, validee_le, validee_par,
                    observations) values (tenant_courant(), ?, ?, 'VALIDEE', now(), ?, ?)
                on conflict (tenant_id, classe_id, periode_id) do update
                set statut = 'VALIDEE', validee_le = now(), validee_par = excluded.validee_par,
                    observations = excluded.observations""", classeId, periodeId, moi, observations);
        pp.ayantRole(Role.CENSEUR).forEach(dst -> pp.informer(dst, "Notes vérifiées — " + c.code(),
                "Le professeur principal de la " + c.code() + " a vérifié avec les élèves les notes et moyennes « "
                        + p.libelle() + " ». La classe est prête pour le conseil de classe."
                        + (observations == null ? "" : "\n\nObservations : " + observations)));
        audit.enregistrer("VERIFICATION_NOTES_VALIDEE", c.code() + " / " + p.libelle(), null);
        return verification(classeId, periodeId);
    }

    // ================================================================== outils

    private Periode periode(Classe c, UUID periodeId) {
        List<Periode> p = jdbc.query("""
                select p.libelle, p.verrouillee, p.annee_id = k.annee_id and p.profil_id = f.profil_id
                from periode p, classe k join filiere f on f.tenant_id = k.tenant_id and f.id = k.filiere_id
                where p.id = ? and k.id = ?""",
                (l, n) -> l.getBoolean(3) ? new Periode(l.getString(1), l.getBoolean(2)) : null, periodeId, c.id());
        if (p.isEmpty()) {
            throw new RessourceIntrouvableException("Période introuvable");
        }
        if (p.get(0) == null) {
            throw new RegleMetierException("PERIODE_HORS_CLASSE", "La période ne concerne pas la " + c.code());
        }
        return p.get(0);
    }

    private BigDecimal noteActuelle(UUID evaluationId, UUID inscriptionId) {
        List<BigDecimal> n = jdbc.queryForList("select valeur from note where evaluation_id = ? and inscription_id = ?",
                BigDecimal.class, evaluationId, inscriptionId);
        return n.isEmpty() ? null : n.get(0);
    }

    private static String nombre(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static final String SELECT = """
            select r.id, r.classe_id, k.code, r.periode_id, p.libelle, r.inscription_id, e.nom || ' ' || e.prenoms,
                   r.matiere_id, m.libelle, r.evaluation_id, ev.libelle, r.note_contestee, r.motif,
                   ut.nom || ' ' || ut.prenoms, ur.nom || ' ' || ur.prenoms, r.enregistree_le, r.statut,
                   r.note_corrigee, r.reponse, urp.nom || ' ' || urp.prenoms, r.repondue_le, r.enseignant_id
            from reclamation_note r
            join classe k on k.tenant_id = r.tenant_id and k.id = r.classe_id
            join periode p on p.tenant_id = r.tenant_id and p.id = r.periode_id
            join inscription i on i.tenant_id = r.tenant_id and i.id = r.inscription_id
            join eleve e on e.tenant_id = i.tenant_id and e.id = i.eleve_id
            join matiere m on m.tenant_id = r.tenant_id and m.id = r.matiere_id
            left join evaluation ev on ev.tenant_id = r.tenant_id and ev.id = r.evaluation_id
            left join utilisateur ut on ut.id = r.enseignant_id
            join utilisateur ur on ur.id = r.enregistree_par
            left join utilisateur urp on urp.id = r.repondue_par""";

    private static ReclamationVue reclamation(ResultSet l) throws SQLException {
        StatutReclamation statut = StatutReclamation.valueOf(l.getString(17));
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        boolean peut = statut == StatutReclamation.OUVERTE
                && ((moi != null && moi.equals(l.getObject(22, UUID.class))) || UtilisateurConnecte.aUnRole(DIRECTION));
        return new ReclamationVue(l.getObject(1, UUID.class), l.getObject(2, UUID.class), l.getString(3),
                l.getObject(4, UUID.class), l.getString(5), l.getObject(6, UUID.class), l.getString(7),
                l.getObject(8, UUID.class), l.getString(9), l.getObject(10, UUID.class), l.getString(11),
                l.getBigDecimal(12), l.getString(13), l.getString(14), l.getString(15), instant(l.getTimestamp(16)),
                statut, l.getBigDecimal(18), l.getString(19), l.getString(20), instant(l.getTimestamp(21)), peut);
    }
}
