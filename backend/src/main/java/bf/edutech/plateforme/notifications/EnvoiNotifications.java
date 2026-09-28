package bf.edutech.plateforme.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker d'envoi. Toutes les 10 secondes (par défaut) : réserve un lot de
 * messages de toutes les écoles (fonction SQL SECURITY DEFINER, avec bail et
 * SKIP LOCKED : plusieurs instances de l'API ne se gênent pas), les envoie,
 * puis enregistre le résultat. Un échec reporte le message (2, 4, 8… minutes) ;
 * après plusieurs échecs consécutifs, le coupe-circuit suspend les envois un moment.
 */
@Component
public class EnvoiNotifications {

    private static final Logger LOG = LoggerFactory.getLogger(EnvoiNotifications.class);

    private record AEnvoyer(UUID id, UUID tenantId, String destinataire, String message) {
    }

    private final JdbcTemplate jdbc;
    private final PasserelleSms passerelle;
    private final NotificationsProperties proprietes;
    private final Clock horloge;

    private int echecsConsecutifs;
    private Instant suspenduJusqua = Instant.EPOCH;

    EnvoiNotifications(JdbcTemplate jdbc, PasserelleSms passerelle, NotificationsProperties proprietes,
            Clock horloge) {
        this.jdbc = jdbc;
        this.passerelle = passerelle;
        this.proprietes = proprietes;
        this.horloge = horloge;
    }

    @Scheduled(fixedDelayString = "${app.notifications.intervalle-ms:10000}",
            initialDelayString = "${app.notifications.intervalle-ms:10000}")
    void passagePlanifie() {
        if (proprietes.envoiAutomatique()) {
            try {
                traiterLot();
            } catch (RuntimeException e) {
                LOG.warn("Passage du worker de notifications interrompu : {}", e.getMessage());
            }
        }
    }

    /** Traite un lot ; renvoie le nombre de messages envoyés. */
    public synchronized int traiterLot() {
        if (horloge.instant().isBefore(suspenduJusqua)) {
            return 0;
        }
        List<AEnvoyer> lot = jdbc.query("""
                select id, tenant_id, destinataire, message from reserver_notifications(?, ?)""",
                (l, n) -> new AEnvoyer(l.getObject("id", UUID.class), l.getObject("tenant_id", UUID.class),
                        l.getString("destinataire"), l.getString("message")),
                proprietes.tailleLot(), (int) proprietes.bail().toSeconds());
        int envoyes = 0;
        for (AEnvoyer n : lot) {
            if (horloge.instant().isBefore(suspenduJusqua)) {
                // Coupe-circuit ouvert en cours de lot : rendu sans compter de tentative
                jdbc.queryForList("select liberer_notification(?, ?)", n.tenantId(), n.id());
                continue;
            }
            String reference;
            try {
                reference = passerelle.envoyer(n.destinataire(), n.message());
            } catch (RuntimeException e) {
                terminer(n, false, null, e.getMessage(), proprietes.maxTentatives());
                if (++echecsConsecutifs >= proprietes.seuilCoupeCircuit()) {
                    suspenduJusqua = horloge.instant().plus(proprietes.pauseCoupeCircuit());
                    echecsConsecutifs = 0;
                    LOG.warn("Passerelle SMS en échec : envois suspendus jusqu'à {}", suspenduJusqua);
                }
                continue;
            }
            echecsConsecutifs = 0;
            envoyes++;
            try {
                terminer(n, true, reference, null, proprietes.maxTentatives());
            } catch (RuntimeException e) {
                // SMS parti mais résultat non enregistré : le message sera repris à l'expiration du bail
                LOG.error("SMS {} envoyé (référence {}) mais statut non enregistré : {}", n.id(), reference,
                        e.getMessage());
            }
        }
        return envoyes;
    }

    private void terminer(AEnvoyer n, boolean succes, String reference, String erreur, int maxTentatives) {
        jdbc.queryForList("select terminer_notification(?, ?, ?, ?, ?, ?)", n.tenantId(), n.id(), succes, reference,
                erreur, maxTentatives);
    }
}
