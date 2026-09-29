package bf.edutech.plateforme.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.telephone.NumeroTelephone;

/**
 * File d'envoi des notifications de l'établissement actif.
 * <p>
 * {@link #planifierSms} s'utilise DANS la transaction de l'événement métier
 * (outbox) : si la transaction est annulée, le SMS n'est jamais envoyé ; si
 * elle réussit, il le sera, même si la passerelle est momentanément indisponible.
 */
@Service
public class NotificationsService {

    public enum StatutNotification {
        EN_ATTENTE, EN_COURS, ENVOYE, ECHEC, ANNULE
    }

    /** Notification vue par l'établissement (numéro masqué). */
    public record NotificationVue(UUID id, String cle, String destinataire, String message,
            StatutNotification statut, int tentatives, String derniereErreur, Instant creeLe, Instant envoyeLe) {
    }

    private static final int LONGUEUR_MAX = 480;

    private final JdbcTemplate jdbc;

    NotificationsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ajoute un SMS à la file. Idempotent par {@code cle} : un second appel avec
     * la même clé est ignoré, sauf si le message précédent avait été annulé (il
     * est alors remis en file).
     */
    public void planifierSms(String cle, String telephone, String langue, String message) {
        UtilisateurConnecte.etablissementActif();
        exigerTransaction();
        String texte = message.length() > LONGUEUR_MAX ? message.substring(0, LONGUEUR_MAX - 1) + "…" : message;
        jdbc.update("""
                insert into notification (tenant_id, id, cle, canal, destinataire, langue, message)
                values (tenant_courant(), ?, ?, 'SMS', ?, ?, ?)
                on conflict (tenant_id, cle) do update
                   set statut = 'EN_ATTENTE', message = excluded.message, destinataire = excluded.destinataire,
                       tentatives = 0, prochaine_tentative = now(), derniere_erreur = null
                 where notification.statut = 'ANNULE'""",
                UUID.randomUUID(), cle, telephone, langue != null ? langue : "FR", texte);
    }

    /** Annule un message pas encore envoyé (ex. absence corrigée par l'enseignant) ; faux s'il est déjà parti. */
    public boolean annuler(String cle) {
        UtilisateurConnecte.etablissementActif();
        exigerTransaction();
        return jdbc.update("update notification set statut = 'ANNULE' where cle = ? and statut = 'EN_ATTENTE'",
                cle) > 0;
    }

    /** Nom de l'établissement actif, pour signer les messages. */
    @Transactional(readOnly = true)
    public String nomEtablissement() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.queryForObject("select nom from tenant where id = tenant_courant()", String.class);
    }

    /** Statut du message de cette clé, s'il existe (ex. savoir si une convocation est déjà partie). */
    @Transactional(readOnly = true)
    public java.util.Optional<StatutNotification> statut(String cle) {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("select statut from notification where cle = ?",
                (l, n) -> StatutNotification.valueOf(l.getString(1)), cle).stream().findFirst();
    }

    /** Dernières notifications de l'établissement (suivi des envois). */
    @Transactional(readOnly = true)
    public List<NotificationVue> dernieres(StatutNotification statut, int limite) {
        UtilisateurConnecte.etablissementActif();
        String filtre = statut != null ? " where statut = ?" : "";
        Object[] parametres = statut != null ? new Object[] { statut.name(), Math.clamp(limite, 1, 500) }
                : new Object[] { Math.clamp(limite, 1, 500) };
        return jdbc.query("""
                select id, cle, destinataire, message, statut, tentatives, derniere_erreur, cree_le, envoye_le
                from notification""" + filtre + " order by cree_le desc limit ?",
                (l, n) -> new NotificationVue(l.getObject("id", UUID.class), l.getString("cle"),
                        NumeroTelephone.masquer(l.getString("destinataire")), l.getString("message"),
                        StatutNotification.valueOf(l.getString("statut")), l.getInt("tentatives"),
                        l.getString("derniere_erreur"), instant(l.getTimestamp("cree_le")),
                        instant(l.getTimestamp("envoye_le"))),
                parametres);
    }

    private static Instant instant(java.sql.Timestamp horodatage) {
        return horodatage != null ? horodatage.toInstant() : null;
    }

    private static void exigerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Une notification se planifie dans la transaction de l'événement");
        }
    }
}
