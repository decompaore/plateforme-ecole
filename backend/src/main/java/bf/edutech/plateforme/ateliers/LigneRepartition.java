package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Part d'un article livré attribuée à un atelier. */
@Entity
@Table(name = "ligne_repartition")
public class LigneRepartition extends EntiteCloisonnee {

    @Column(name = "livraison_id", nullable = false, updatable = false)
    private UUID livraisonId;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "atelier_id", nullable = false, updatable = false)
    private UUID atelierId;

    @Column(name = "quantite", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantite;

    protected LigneRepartition() {
    }

    LigneRepartition(UUID livraisonId, UUID articleId, UUID atelierId, BigDecimal quantite) {
        this.livraisonId = livraisonId;
        this.articleId = articleId;
        this.atelierId = atelierId;
        this.quantite = quantite;
    }

    public UUID getLivraisonId() {
        return livraisonId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public BigDecimal getQuantite() {
        return quantite;
    }
}
