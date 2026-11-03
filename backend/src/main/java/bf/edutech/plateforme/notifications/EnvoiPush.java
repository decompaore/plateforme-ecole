package bf.edutech.plateforme.notifications;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.notifications.PasserellePush.Appareil;
import bf.edutech.plateforme.notifications.PasserellePush.Resultat;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Worker des notifications push (v0.50). Toutes les 5 secondes (par défaut) : prend les
 * notifications dues (délai de correction écoulé), les remet aux appareils du parent, puis
 * enregistre le résultat dans le cloisonnement de l'établissement. Sans appareil joignable, ou
 * après plusieurs échecs, le SMS de secours est mis en file. Un appareil dont l'abonnement a
 * expiré est retiré.
 */
@Component
public class EnvoiPush {

    private static final Logger LOG = LoggerFactory.getLogger(EnvoiPush.class);
    private static final int ECHECS_MAX_APPAREIL = 10;

    private record AEnvoyer(UUID id, UUID utilisateurId, String type, String titre, String message,
            String messageDiscret, String lien, String cle, int tentatives, String smsCle, String smsDestinataire,
            String smsLangue, String smsMessage) {
    }

    private record Cible(UUID id, Appareil appareil) {
    }

    private final JdbcTemplate jdbc;
    private final PasserellePush passerelle;
    private final PushProperties proprietes;
    private final NotificationsService sms;
    private final TransactionTemplate transaction;

    EnvoiPush(JdbcTemplate jdbc, PasserellePush passerelle, PushProperties proprietes, NotificationsService sms,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.passerelle = passerelle;
        this.proprietes = proprietes;
        this.sms = sms;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${app.push.intervalle-ms:5000}", initialDelayString = "${app.push.intervalle-ms:5000}")
    void passagePlanifie() {
        if (proprietes.envoiAutomatique()) {
            try {
                traiterLot();
            } catch (RuntimeException e) {
                LOG.warn("Passage du worker des notifications push interrompu : {}", e.getMessage());
            }
        }
    }

    /** Traite les notifications dues de toutes les écoles ; renvoie le nombre de notifications remises. */
    public synchronized int traiterLot() {
        List<UUID[]> dues = jdbc.query("select tenant_id, id from notifications_parents_dues(?)",
                (rs, i) -> new UUID[] { rs.getObject(1, UUID.class), rs.getObject(2, UUID.class) },
                proprietes.tailleLot());
        int remises = 0;
        for (UUID[] d : dues) {
            try {
                if (TenantContext.executerPour(d[0], () -> traiter(d[1]))) {
                    remises++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Notification push {} non traitée : {}", d[1], e.getMessage());
            }
        }
        return remises;
    }

    private boolean traiter(UUID id) {
        // 1. Réservation (bail) et appareils du parent
        record Reserve(AEnvoyer n, List<Cible> cibles, boolean discret) {
        }
        Reserve r = transaction.execute(s -> {
            List<AEnvoyer> l = jdbc.query("""
                    update notification_parent
                       set push_statut = 'EN_COURS', push_verrou = now() + make_interval(secs => ?),
                           push_tentatives = push_tentatives + 1
                     where id = ? and ((push_statut = 'A_ENVOYER' and visible_le <= now())
                                    or (push_statut = 'EN_COURS' and push_verrou < now()))
                    returning id, utilisateur_id, type, titre, message, message_discret, lien, cle, push_tentatives,
                              sms_cle, sms_destinataire, sms_langue, sms_message""",
                    (rs, i) -> new AEnvoyer(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getInt(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)),
                    (int) proprietes.bail().toSeconds(), id);
            if (l.isEmpty()) {
                return null;
            }
            AEnvoyer n = l.get(0);
            List<Cible> cibles = jdbc.query("""
                    select id, endpoint, cle_p256dh, cle_auth from abonnement_push
                    where utilisateur_id = ? and actif order by cree_le""",
                    (rs, i) -> new Cible(rs.getObject(1, UUID.class),
                            new Appareil(rs.getString(2), rs.getString(3), rs.getString(4))),
                    n.utilisateurId());
            Boolean discret = jdbc.query(
                    "select bool_or(notifications_discretes) from responsable where utilisateur_id = ?",
                    rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, n.utilisateurId());
            return new Reserve(n, cibles, Boolean.TRUE.equals(discret));
        });
        if (r == null) {
            return false;
        }

        // 2. Remise aux services push (hors transaction)
        byte[] charge = charge(r.n(), r.discret());
        List<UUID> remis = new ArrayList<>();
        List<UUID> expires = new ArrayList<>();
        List<UUID> enErreur = new ArrayList<>();
        for (Cible c : r.cibles()) {
            Resultat res;
            try {
                res = passerelle.envoyer(c.appareil(), charge);
            } catch (RuntimeException e) {
                res = Resultat.ERREUR;
            }
            (res == Resultat.REMIS ? remis : res == Resultat.EXPIRE ? expires : enErreur).add(c.id());
        }

        // 3. Résultat, et SMS de secours si aucun appareil n'a reçu la notification
        AEnvoyer n = r.n();
        transaction.executeWithoutResult(s -> {
            for (UUID a : remis) {
                jdbc.update("update abonnement_push set dernier_envoi = now(), echecs = 0 where id = ?", a);
            }
            for (UUID a : expires) {
                jdbc.update("update abonnement_push set actif = false where id = ?", a);
            }
            for (UUID a : enErreur) {
                jdbc.update("update abonnement_push set echecs = echecs + 1, actif = (echecs + 1 < ?) where id = ?",
                        ECHECS_MAX_APPAREIL, a);
            }
            if (!remis.isEmpty()) {
                jdbc.update("""
                        update notification_parent set push_statut = 'ENVOYE', push_envoye_le = now(),
                            push_verrou = null, push_erreur = null where id = ? and push_statut = 'EN_COURS'""", n.id());
            } else if (!enErreur.isEmpty() && n.tentatives() < proprietes.maxTentatives()) {
                // Nouvelle tentative après 1, 2, 4… minutes : le bail sert d'échéance
                jdbc.update("""
                        update notification_parent set push_verrou = now() + make_interval(mins => ?),
                            push_erreur = 'Service push indisponible' where id = ? and push_statut = 'EN_COURS'""",
                        1 << Math.min(n.tentatives() - 1, 5), n.id());
            } else {
                int maj = jdbc.update("""
                        update notification_parent set push_statut = ?, push_verrou = null, push_erreur = ?
                         where id = ? and push_statut = 'EN_COURS'""",
                        r.cibles().size() == expires.size() ? "SANS_ABONNEMENT" : "ECHEC",
                        r.cibles().isEmpty() ? "Aucun appareil" : "Aucun appareil n'a reçu la notification", n.id());
                if (maj > 0 && n.smsCle() != null) {
                    sms.planifierSms(n.smsCle(), n.smsDestinataire(), n.smsLangue(), n.smsMessage());
                }
            }
        });
        return !remis.isEmpty();
    }

    /** Message lu par le service worker Angular (format de {@code SwPush} : objet « notification »). */
    private static byte[] charge(AEnvoyer n, boolean discret) {
        String titre = discret && !"ESSAI".equals(n.type()) ? "Espace parent" : n.titre();
        return chargeJson(titre, discret ? n.messageDiscret() : n.message(), n.lien(), n.cle());
    }

    static byte[] chargeJson(String titre, String corps, String lien, String cle) {
        String url = lien == null ? "/parent/notifications" : lien;
        String json = "{\"notification\":{\"title\":" + json(titre) + ",\"body\":" + json(corps)
                + ",\"icon\":\"/icons/icon-192x192.png\",\"badge\":\"/icons/icon-72x72.png\",\"tag\":" + json(cle)
                + ",\"renotify\":true,\"data\":{\"url\":" + json(url)
                + ",\"onActionClick\":{\"default\":{\"operation\":\"navigateLastFocusedOrOpen\",\"url\":" + json(url)
                + "}}}}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static String json(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
