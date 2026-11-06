package bf.edutech.plateforme.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Notifications aux familles (v0.50) : dans l'application (Web Push et centre de notifications de
 * l'espace parent) pour les parents qui l'ont activée sur leur téléphone, par SMS pour les autres
 * et pour ceux que l'établissement a mis « toujours par SMS » (parents sans smartphone).
 *
 * <p>Comme pour les SMS, tout s'écrit dans la transaction de l'événement (outbox). L'envoi attend
 * le délai de correction de l'établissement : si l'enseignant corrige son appel entre-temps, rien
 * ne part. Une notification push qui n'atteint aucun appareil se replie sur le SMS (contact
 * prioritaire de l'élève).
 */
@Service
public class NotificationsFamilles {

    public enum TypeEvenement {
        ABSENCE,
        RETARD
    }

    public enum CanalParent {
        /** L'application si le parent l'a activée sur un appareil, sinon SMS. */
        AUTO,
        /** Toujours par SMS (parent sans smartphone), choisi par l'établissement. */
        SMS
    }

    /**
     * Un responsable de l'élève. {@code utilisateurId} : son compte de l'espace parent (null s'il n'en a
     * pas) ; seul le {@code contactPrioritaire} reçoit les SMS.
     */
    public record DestinataireFamille(UUID utilisateurId, String telephone, String langue, CanalParent canal,
            boolean notifierRetards, boolean contactPrioritaire) {
    }

    /**
     * L'événement : {@code message} est le texte complet (centre de notifications et push), {@code sms}
     * le texte du SMS (null : pas de SMS pour cet événement).
     */
    public record EvenementFamille(String cle, UUID eleveId, TypeEvenement type, String titre, String message,
            String lien, String sms) {
    }

    public record ReglageNotifications(int delaiCorrectionMinutes, boolean smsRetards, boolean pushDisponible) {
    }

    private final JdbcTemplate jdbc;
    private final NotificationsService sms;
    private final PushProperties push;

    NotificationsFamilles(JdbcTemplate jdbc, NotificationsService sms, PushProperties push) {
        this.jdbc = jdbc;
        this.sms = sms;
        this.push = push;
    }

    // ------------------------------------------------------------------ réglages de l'établissement

    @Transactional(readOnly = true)
    public ReglageNotifications reglages() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("select delai_correction_minutes, sms_retards from reglage_notifications",
                (rs, i) -> new ReglageNotifications(rs.getInt(1), rs.getBoolean(2), push.actif())).stream().findFirst()
                .orElse(new ReglageNotifications(0, false, push.actif()));
    }

    @Transactional
    public ReglageNotifications modifierReglages(int delaiCorrectionMinutes, boolean smsRetards) {
        UtilisateurConnecte.etablissementActif();
        if (delaiCorrectionMinutes < 0 || delaiCorrectionMinutes > 60) {
            throw new RegleMetierException("DELAI_INVALIDE", "Le délai de correction va de 0 à 60 minutes");
        }
        UUID auteur = UtilisateurConnecte.idSiConnecte().orElse(null);
        jdbc.update("""
                insert into reglage_notifications (tenant_id, delai_correction_minutes, sms_retards, modifie_par)
                values (tenant_courant(), ?, ?, ?)
                on conflict (tenant_id) do update set delai_correction_minutes = excluded.delai_correction_minutes,
                    sms_retards = excluded.sms_retards, modifie_par = excluded.modifie_par, modifie_le = now()""",
                (short) delaiCorrectionMinutes, smsRetards, auteur);
        return reglages();
    }

    // ------------------------------------------------------------------ événements

    /** Prévient les responsables de l'élève ; dans la transaction de l'événement. */
    public void notifier(EvenementFamille e, List<DestinataireFamille> destinataires) {
        UtilisateurConnecte.etablissementActif();
        exigerTransaction();
        ReglageNotifications r = reglages();
        Duration delai = Duration.ofMinutes(r.delaiCorrectionMinutes());
        boolean smsPermis = e.sms() != null && (e.type() == TypeEvenement.ABSENCE || r.smsRetards());
        for (DestinataireFamille d : destinataires) {
            boolean voulu = e.type() != TypeEvenement.RETARD || d.notifierRetards();
            boolean parSms = smsPermis && voulu && d.contactPrioritaire() && d.telephone() != null;
            if (d.utilisateurId() == null) {
                if (parSms) {
                    sms.planifierSms(e.cle(), d.telephone(), d.langue(), e.sms(), delai);
                }
                continue;
            }
            boolean pushVoulu = voulu && d.canal() == CanalParent.AUTO && push.actif();
            boolean abonne = pushVoulu && appareilsActifs(d.utilisateurId()) > 0;
            String statut = !pushVoulu ? "NON_DEMANDE" : abonne ? "A_ENVOYER" : "SANS_ABONNEMENT";
            boolean secours = parSms && abonne;
            jdbc.update("""
                    insert into notification_parent (tenant_id, id, utilisateur_id, eleve_id, cle, type, titre, message,
                        message_discret, lien, visible_le, push_statut, sms_cle, sms_destinataire, sms_langue, sms_message)
                    values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(secs => ?), ?, ?, ?, ?, ?)
                    on conflict (tenant_id, utilisateur_id, cle) do update
                       set titre = excluded.titre, message = excluded.message, message_discret = excluded.message_discret,
                           lien = excluded.lien, cree_le = now(), visible_le = excluded.visible_le, lue_le = null,
                           corrigee_le = null, push_statut = excluded.push_statut, push_tentatives = 0,
                           push_verrou = null, push_envoye_le = null, push_erreur = null,
                           sms_cle = excluded.sms_cle, sms_destinataire = excluded.sms_destinataire,
                           sms_langue = excluded.sms_langue, sms_message = excluded.sms_message
                     where notification_parent.push_statut = 'ANNULE' or notification_parent.corrigee_le is not null""",
                    UUID.randomUUID(), d.utilisateurId(), e.eleveId(), e.cle(), e.type().name(), tronquer(e.titre(), 120),
                    tronquer(e.message(), 480), "Nouvelle information de l'établissement sur votre enfant",
                    e.lien(), (int) delai.toSeconds(), statut, secours ? e.cle() : null,
                    secours ? d.telephone() : null, secours ? d.langue() : null, secours ? e.sms() : null);
            if (parSms && !abonne) {
                sms.planifierSms(e.cle(), d.telephone(), d.langue(), e.sms(), delai);
            }
        }
    }

    /**
     * L'établissement revient sur l'information (élève finalement présent) : ce qui n'est pas encore
     * parti est annulé ; ce que le parent a déjà reçu est marqué « corrigé » dans son centre.
     */
    public void annuler(String cle) {
        UtilisateurConnecte.etablissementActif();
        exigerTransaction();
        sms.annuler(cle);
        jdbc.update("""
                update notification_parent
                   set push_statut = case when visible_le > now() or push_statut = 'A_ENVOYER' then 'ANNULE'
                                          else push_statut end,
                       corrigee_le = case when visible_le <= now() then now() end
                 where cle = ? and corrigee_le is null and push_statut <> 'ANNULE'""", cle);
    }

    int appareilsActifs(UUID utilisateurId) {
        Integer n = jdbc.queryForObject("select count(*) from abonnement_push where utilisateur_id = ? and actif",
                Integer.class, utilisateurId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ centre de notifications du parent

    public record NotificationParentVue(UUID id, UUID eleveId, TypeEvenement type, String titre, String message,
            String lien, Instant recueLe, Instant lueLe, Instant corrigeeLe) {
    }

    public record CentreNotifications(int nonLues, List<NotificationParentVue> notifications) {
    }

    @Transactional(readOnly = true)
    public CentreNotifications centre(int limite) {
        UtilisateurConnecte.etablissementActif();
        UUID moi = UtilisateurConnecte.id();
        List<NotificationParentVue> liste = jdbc.query("""
                select id, eleve_id, type, titre, message, lien, visible_le, lue_le, corrigee_le
                from notification_parent
                where utilisateur_id = ? and type <> 'ESSAI' and visible_le <= now()
                  and (push_statut <> 'ANNULE' or corrigee_le is not null)
                order by visible_le desc limit ?""",
                (rs, i) -> new NotificationParentVue(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        TypeEvenement.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant(), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9))),
                moi, Math.clamp(limite, 1, 200));
        Integer nonLues = jdbc.queryForObject("""
                select count(*) from notification_parent
                where utilisateur_id = ? and type <> 'ESSAI' and visible_le <= now() and lue_le is null
                  and (push_statut <> 'ANNULE' or corrigee_le is not null)""", Integer.class, moi);
        return new CentreNotifications(nonLues == null ? 0 : nonLues, liste);
    }

    /** Marque comme lues les notifications données, ou toutes si la liste est vide. */
    @Transactional
    public CentreNotifications marquerLues(List<UUID> ids) {
        UtilisateurConnecte.etablissementActif();
        UUID moi = UtilisateurConnecte.id();
        if (ids == null || ids.isEmpty()) {
            jdbc.update("update notification_parent set lue_le = now() where utilisateur_id = ? and lue_le is null "
                    + "and visible_le <= now()", moi);
        } else {
            jdbc.update("update notification_parent set lue_le = now() where utilisateur_id = ? and lue_le is null "
                    + "and id = any(?)", moi, (Object) ids.toArray(UUID[]::new));
        }
        return centre(50);
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static String tronquer(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void exigerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Une notification se planifie dans la transaction de l'événement");
        }
    }
}
