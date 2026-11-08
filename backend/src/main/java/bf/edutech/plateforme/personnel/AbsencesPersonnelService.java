package bf.edutech.plateforme.personnel;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.messagerie.MessagerieService;
import bf.edutech.plateforme.personnel.Agents.Agent;
import bf.edutech.plateforme.personnel.Vues.AbsenceVue;
import bf.edutech.plateforme.personnel.Vues.StatutAbsence;
import bf.edutech.plateforme.personnel.Vues.TypeAbsence;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Congés, autorisations d'absence, missions et absences du personnel (v0.51). L'agent demande depuis « Mon
 * dossier » (ou le secrétariat demande pour lui) ; le chef d'établissement accorde ou refuse. Une absence
 * accordée produit l'autorisation d'absence, signée et numérotée. Une absence irrégulière est constatée par la
 * gestion, sans demande.
 */
@Service
public class AbsencesPersonnelService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record DonneesAbsence(TypeAbsence type, LocalDate debut, LocalDate fin, String motif) {
    }

    private final JdbcTemplate jdbc;
    private final Agents agents;
    private final ActesService actes;
    private final MessagerieService messagerie;
    private final MembresService membres;
    private final AuditService audit;
    private final Clock horloge;

    AbsencesPersonnelService(JdbcTemplate jdbc, Agents agents, ActesService actes, MessagerieService messagerie,
            MembresService membres, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.actes = actes;
        this.messagerie = messagerie;
        this.membres = membres;
        this.audit = audit;
        this.horloge = horloge;
    }

    private static final String SELECT = """
            select b.id, b.agent_id, a.nom || ' ' || a.prenoms, b.type, b.debut, b.fin, b.motif, b.statut,
                   ud.nom || ' ' || ud.prenoms, b.demandee_le, uc.nom || ' ' || uc.prenoms, b.decidee_le, b.reponse,
                   b.piece_id,
                   (select x.id from acte_agent x where x.absence_id = b.id and x.annule_le is null limit 1)
            from absence_agent b
            join agent a on a.id = b.agent_id
            left join utilisateur ud on ud.id = b.demandee_par
            left join utilisateur uc on uc.id = b.decidee_par
            """;

    List<AbsenceVue> lister(UUID agentId) {
        return jdbc.query(SELECT + " where b.agent_id = ? order by b.debut desc", (r, n) -> lire(r), agentId);
    }

    /** Demandes à décider, et absences en cours ou à venir (accordées ou constatées). */
    @Transactional(readOnly = true)
    public List<AbsenceVue> suivi() {
        Personnel.exigerLecture();
        LocalDate jour = aujourdhui();
        return jdbc.query(SELECT + """
                 where b.statut = 'DEMANDEE' or (b.statut in ('ACCORDEE', 'CONSTATEE') and b.fin >= ?)
                 order by case when b.statut = 'DEMANDEE' then 0 else 1 end, b.debut""",
                (r, n) -> lire(r), jour.minusDays(7));
    }

    /** L'agent demande une absence depuis « Mon dossier ». */
    @Transactional
    public AbsenceVue demander(DonneesAbsence d) {
        Agent a = agents.moi();
        if (d.type() == TypeAbsence.ABSENCE_IRREGULIERE) {
            throw new IllegalArgumentException("Type d'absence non disponible");
        }
        UUID id = enregistrer(a, d, StatutAbsence.DEMANDEE);
        prevenirChefs(a, d);
        return vue(id);
    }

    /** La gestion enregistre une demande pour un agent, ou constate une absence irrégulière. */
    @Transactional
    public AbsenceVue saisir(UUID agentId, DonneesAbsence d) {
        Personnel.exigerGestionnaire();
        Agent a = agents.charger(agentId);
        boolean irreguliere = d.type() == TypeAbsence.ABSENCE_IRREGULIERE;
        UUID id = enregistrer(a, d, irreguliere ? StatutAbsence.CONSTATEE : StatutAbsence.DEMANDEE);
        if (!irreguliere && !Personnel.chef()) {
            prevenirChefs(a, d);
        }
        return vue(id);
    }

    /** Le chef d'établissement accorde (autorisation d'absence signée) ou refuse (motif obligatoire). */
    @Transactional
    public AbsenceVue decider(UUID id, boolean accorder, String reponse) {
        Personnel.exigerChef();
        AbsenceVue b = vue(id);
        if (b.statut() != StatutAbsence.DEMANDEE) {
            throw new RegleMetierException("ABSENCE_DECIDEE", "Cette demande a déjà été traitée");
        }
        String rep = accorder ? Personnel.facultatif(reponse, "Réponse", 400)
                : Personnel.obligatoire(reponse, "Motif du refus", 400);
        jdbc.update("""
                update absence_agent set statut = ?, decidee_par = ?, decidee_le = now(), reponse = ? where id = ?""",
                accorder ? "ACCORDEE" : "REFUSEE", UtilisateurConnecte.id(), rep, id);
        Agent a = agents.charger(b.agentId());
        if (accorder) {
            actes.etablirAutorisation(a, id, b.type(), b.debut(), b.fin(), b.jours(), b.reprise(), b.motif());
        }
        audit.enregistrer(accorder ? "ABSENCE_PERSONNEL_ACCORDEE" : "ABSENCE_PERSONNEL_REFUSEE", a.nomComplet(),
                Map.of("du", b.debut().toString(), "au", b.fin().toString()));
        if (a.utilisateurId() != null) {
            String objet = "Votre demande d'absence du " + b.debut().format(JOUR) + " : "
                    + (accorder ? "accordée" : "refusée");
            String corps = accorder
                    ? "Votre demande (" + b.type().libelle + ", du " + b.debut().format(JOUR) + " au " + b.fin().format(JOUR)
                            + ") est accordée. L'autorisation d'absence est dans « Mon dossier »."
                            + (rep != null ? "\n\n" + rep : "")
                    : "Votre demande (" + b.type().libelle + ", du " + b.debut().format(JOUR) + " au " + b.fin().format(JOUR)
                            + ") n'est pas accordée.\n\nMotif : " + rep;
            transmettre(a.utilisateurId(), objet, corps);
        }
        return vue(id);
    }

    /** L'agent retire sa demande tant qu'elle n'est pas traitée ; le chef annule une absence accordée. */
    @Transactional
    public AbsenceVue annuler(UUID id, String motif) {
        AbsenceVue b = vue(id);
        Agent a = agents.charger(b.agentId());
        boolean proprietaire = UtilisateurConnecte.id().equals(a.utilisateurId());
        if (b.statut() == StatutAbsence.DEMANDEE && (proprietaire || Personnel.gestionnaire())) {
            jdbc.update("update absence_agent set statut = 'ANNULEE' where id = ?", id);
        } else if ((b.statut() == StatutAbsence.ACCORDEE || b.statut() == StatutAbsence.CONSTATEE) && Personnel.chef()) {
            String m = Personnel.obligatoire(motif, "Motif de l'annulation", 300);
            jdbc.update("update absence_agent set statut = 'ANNULEE', reponse = ? where id = ?", m, id);
            if (b.acteId() != null) {
                actes.annulerInterne(b.acteId(), m);
            }
        } else {
            throw new AccesRefuseException("Cette absence ne peut plus être annulée");
        }
        return vue(id);
    }

    // ------------------------------------------------------------------ outils

    private UUID enregistrer(Agent a, DonneesAbsence d, StatutAbsence statut) {
        if (d == null || d.type() == null || d.debut() == null || d.fin() == null) {
            throw new IllegalArgumentException("Type, début et fin obligatoires");
        }
        if (d.fin().isBefore(d.debut())) {
            throw new IllegalArgumentException("La fin précède le début");
        }
        if (ChronoUnit.DAYS.between(d.debut(), d.fin()) > 366) {
            throw new IllegalArgumentException("Une absence dure un an au plus");
        }
        String motif = Personnel.obligatoire(d.motif(), "Motif", 400);
        Integer chevauchement = jdbc.queryForObject("""
                select count(*) from absence_agent where agent_id = ? and statut in ('DEMANDEE', 'ACCORDEE', 'CONSTATEE')
                   and daterange(debut, fin, '[]') && daterange(?, ?, '[]')""", Integer.class, a.id(), d.debut(), d.fin());
        if (chevauchement != null && chevauchement > 0) {
            throw new RegleMetierException("ABSENCE_CHEVAUCHEMENT", "Une autre absence couvre déjà une partie de ces dates");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into absence_agent (tenant_id, id, agent_id, type, debut, fin, motif, statut, demandee_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, a.id(), d.type().name(), d.debut(), d.fin(), motif, statut.name(), UtilisateurConnecte.id());
        return id;
    }

    private void prevenirChefs(Agent a, DonneesAbsence d) {
        String objet = "Demande d'absence : " + a.nomComplet();
        String corps = a.nomComplet() + " demande une absence (" + d.type().libelle + ") du " + d.debut().format(JOUR)
                + " au " + d.fin().format(JOUR) + ".\n\nMotif : " + d.motif().strip()
                + "\n\nÀ décider dans « Personnel », onglet « Absences ».";
        membres.lister().stream().filter(m -> m.actif() && m.role() == Role.ADMIN_ECOLE)
                .map(m -> m.utilisateurId()).distinct()
                .filter(u -> !u.equals(UtilisateurConnecte.id()))
                .forEach(u -> transmettre(u, objet, corps));
    }

    /** Message interne ; un compte qui n'est plus membre actif ne bloque pas la décision. */
    private void transmettre(UUID destinataire, String objet, String corps) {
        try {
            messagerie.transmettre(destinataire, objet, corps, "Personnel");
        } catch (IllegalArgumentException e) {
            // destinataire sans rôle actif dans l'établissement : pas de message
        }
    }

    AbsenceVue vue(UUID id) {
        return jdbc.query(SELECT + " where b.id = ?", (r, n) -> lire(r), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Absence introuvable"));
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(Personnel.FUSEAU));
    }

    /** Jour de reprise : le lendemain de la fin, le dimanche reporté au lundi. */
    static LocalDate reprise(LocalDate fin) {
        LocalDate r = fin.plusDays(1);
        return r.getDayOfWeek() == DayOfWeek.SUNDAY ? r.plusDays(1) : r;
    }

    private static AbsenceVue lire(java.sql.ResultSet r) throws java.sql.SQLException {
        LocalDate debut = r.getObject(5, LocalDate.class);
        LocalDate fin = r.getObject(6, LocalDate.class);
        return new AbsenceVue(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3),
                TypeAbsence.valueOf(r.getString(4)), debut, fin, (int) ChronoUnit.DAYS.between(debut, fin) + 1,
                reprise(fin), r.getString(7), StatutAbsence.valueOf(r.getString(8)), r.getString(9),
                ActesService.instant(r.getObject(10, OffsetDateTime.class)), r.getString(11),
                ActesService.instant(r.getObject(12, OffsetDateTime.class)), r.getString(13), r.getObject(14, UUID.class),
                r.getObject(15, UUID.class));
    }
}
