package bf.edutech.plateforme.utilisateurs;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteUuid;

/**
 * Jeton de rafraîchissement (seul son haché SHA-256 est stocké).
 * <p>
 * Chaque utilisation le révoque et en crée un nouveau de la même « famille ».
 * Réutiliser un jeton déjà révoqué signale un vol : toute la famille est révoquée.
 */
@Entity
@Table(name = "jeton_rafraichissement")
public class JetonRafraichissement extends EntiteUuid {

    @Column(name = "utilisateur_id", nullable = false, updatable = false)
    private UUID utilisateurId;

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "hache", nullable = false, unique = true, length = 64, updatable = false)
    private String hache;

    @Column(name = "famille", nullable = false, updatable = false)
    private UUID famille;

    @Column(name = "expire_le", nullable = false, updatable = false)
    private Instant expireLe;

    @Column(name = "revoque_le")
    private Instant revoqueLe;

    /** Jeton émis en échange de celui-ci (rotation), pour tolérer une réponse perdue. */
    @Column(name = "remplace_par")
    private UUID remplacePar;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe = Instant.now();

    protected JetonRafraichissement() {
    }

    JetonRafraichissement(UUID utilisateurId, UUID tenantId, String hache, UUID famille, Instant expireLe) {
        this.utilisateurId = utilisateurId;
        this.tenantId = tenantId;
        this.hache = hache;
        this.famille = famille;
        this.expireLe = expireLe;
    }

    boolean estRevoque() {
        return revoqueLe != null;
    }

    boolean estExpire(Instant maintenant) {
        return !expireLe.isAfter(maintenant);
    }

    void revoquer(Instant maintenant) {
        if (revoqueLe == null) {
            revoqueLe = maintenant;
        }
    }

    /** Rotation : révoqué, remplacé par {@code successeur}. */
    void remplacer(UUID successeur, Instant maintenant) {
        revoquer(maintenant);
        this.remplacePar = successeur;
    }

    /**
     * Renouvelé il y a moins de {@code grace} : le client n'a peut-être jamais reçu le
     * successeur (réponse perdue sur un réseau faible).
     */
    boolean renouveleRecemment(Instant maintenant, Duration grace) {
        return remplacePar != null && revoqueLe != null && !revoqueLe.plus(grace).isBefore(maintenant);
    }

    UUID getRemplacePar() {
        return remplacePar;
    }

    UUID getUtilisateurId() {
        return utilisateurId;
    }

    UUID getTenantId() {
        return tenantId;
    }

    UUID getFamille() {
        return famille;
    }
}
