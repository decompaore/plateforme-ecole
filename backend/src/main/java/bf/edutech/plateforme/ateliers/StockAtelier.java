package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Quantité d'une matière d'œuvre dans un atelier, avec son seuil d'alerte. */
@Entity
@Table(name = "stock_atelier")
public class StockAtelier extends EntiteCloisonnee {

    @Column(name = "atelier_id", nullable = false, updatable = false)
    private UUID atelierId;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "quantite", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantite = BigDecimal.ZERO;

    @Column(name = "seuil_alerte", precision = 12, scale = 2)
    private BigDecimal seuilAlerte;

    protected StockAtelier() {
    }

    StockAtelier(UUID atelierId, UUID articleId) {
        this.atelierId = atelierId;
        this.articleId = articleId;
    }

    void ajouter(BigDecimal variation) {
        this.quantite = quantite.add(variation);
    }

    void definirSeuil(BigDecimal seuil) {
        this.seuilAlerte = seuil;
    }

    boolean sousLeSeuil() {
        return seuilAlerte != null && quantite.compareTo(seuilAlerte) <= 0;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public BigDecimal getQuantite() {
        return quantite;
    }

    public BigDecimal getSeuilAlerte() {
        return seuilAlerte;
    }
}
