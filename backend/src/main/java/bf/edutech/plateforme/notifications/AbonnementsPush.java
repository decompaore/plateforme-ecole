package bf.edutech.plateforme.notifications;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Appareils sur lesquels l'utilisateur connecté a activé les notifications (v0.50). */
@Service
public class AbonnementsPush {

    public record ConfigurationPush(boolean disponible, String clePublique) {
    }

    public record DonneesAbonnement(String endpoint, String p256dh, String auth, String appareil) {
    }

    public record AppareilVue(UUID id, String appareil, java.time.Instant creeLe, java.time.Instant dernierEnvoi) {
    }

    private final JdbcTemplate jdbc;
    private final PushProperties push;
    private final PasserellePushHttp passerelle;

    AbonnementsPush(JdbcTemplate jdbc, PushProperties push, PasserellePushHttp passerelle) {
        this.jdbc = jdbc;
        this.push = push;
        this.passerelle = passerelle;
    }

    public ConfigurationPush configuration() {
        return push.actif() ? new ConfigurationPush(true, passerelle.webPush().clePublique())
                : new ConfigurationPush(false, null);
    }

    @Transactional
    public List<AppareilVue> abonner(DonneesAbonnement d) {
        UtilisateurConnecte.etablissementActif();
        if (!push.actif()) {
            throw new RegleMetierException("PUSH_INDISPONIBLE",
                    "Les notifications dans l'application ne sont pas encore activées sur la plateforme");
        }
        if (d == null || d.endpoint() == null || !d.endpoint().startsWith("https://") || d.endpoint().length() > 1000
                || d.p256dh() == null || d.auth() == null) {
            throw new IllegalArgumentException("Abonnement incomplet");
        }
        try {
            WebPush.publique(WebPush.D64.decode(d.p256dh()));
            if (WebPush.D64.decode(d.auth()).length != 16) {
                throw new IllegalArgumentException("Secret d'authentification invalide");
            }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Clés de l'appareil invalides");
        }
        String appareil = d.appareil() == null || d.appareil().isBlank() ? null : NotificationsFamilles.tronquer(d.appareil().strip(), 120);
        jdbc.update("""
                insert into abonnement_push (tenant_id, id, utilisateur_id, endpoint, cle_p256dh, cle_auth, appareil)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, endpoint) do update
                   set utilisateur_id = excluded.utilisateur_id, cle_p256dh = excluded.cle_p256dh,
                       cle_auth = excluded.cle_auth, appareil = coalesce(excluded.appareil, abonnement_push.appareil),
                       actif = true, echecs = 0""",
                UUID.randomUUID(), UtilisateurConnecte.id(), d.endpoint(), d.p256dh(), d.auth(), appareil);
        return mesAppareils();
    }

    @Transactional
    public List<AppareilVue> desabonner(String endpoint) {
        UtilisateurConnecte.etablissementActif();
        jdbc.update("update abonnement_push set actif = false where utilisateur_id = ? and endpoint = ?",
                UtilisateurConnecte.id(), endpoint);
        return mesAppareils();
    }

    @Transactional(readOnly = true)
    public List<AppareilVue> mesAppareils() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select id, appareil, cree_le, dernier_envoi from abonnement_push
                where utilisateur_id = ? and actif order by cree_le desc""",
                (rs, i) -> new AppareilVue(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                UtilisateurConnecte.id());
    }

    /** Notification d'essai sur les appareils de l'utilisateur ; renvoie leur nombre. */
    @Transactional
    public int essai() {
        UtilisateurConnecte.etablissementActif();
        List<AppareilVue> appareils = mesAppareils();
        if (appareils.isEmpty()) {
            throw new RegleMetierException("AUCUN_APPAREIL", "Activez d'abord les notifications sur ce téléphone");
        }
        jdbc.update("""
                insert into notification_parent (tenant_id, id, utilisateur_id, cle, type, titre, message, message_discret,
                    lien, visible_le, push_statut)
                values (tenant_courant(), ?, ?, ?, 'ESSAI', 'Notifications activées',
                    'Vous recevrez ici les absences et les retards de vos enfants.', 'Notifications activées',
                    '/parent/notifications', now(), 'A_ENVOYER')""",
                UUID.randomUUID(), UtilisateurConnecte.id(), "essai:" + UUID.randomUUID());
        return appareils.size();
    }
}
