package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Entrée, sortie ou correction d'inventaire d'une matière d'œuvre (jamais modifié ni supprimé). */
@Entity
@Table(name = "mouvement_stock")
public class MouvementStock extends EntiteCloisonnee {

    @Column(name = "atelier_id", nullable = false, updatable = false)
    private UUID atelierId;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private TypeMouvement type;

    @Column(name = "quantite", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal quantite;

    @Column(name = "stock_apres", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal stockApres;

    @Column(name = "date_mouvement", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "motif", length = 200, updatable = false)
    private String motif;

    @Column(name = "inventaire_id", updatable = false)
    private UUID inventaireId;

    @Column(name = "auteur", updatable = false)
    private UUID auteur;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe;

    protected MouvementStock() {
    }

    MouvementStock(UUID atelierId, UUID articleId, TypeMouvement type, BigDecimal quantite, BigDecimal stockApres,
            LocalDate date, String motif, UUID inventaireId, UUID auteur, Instant maintenant) {
        this.atelierId = atelierId;
        this.articleId = articleId;
        this.type = type;
        this.quantite = quantite;
        this.stockApres = stockApres;
        this.date = date;
        this.motif = motif;
        this.inventaireId = inventaireId;
        this.auteur = auteur;
        this.creeLe = maintenant;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public TypeMouvement getType() {
        return type;
    }

    public BigDecimal getQuantite() {
        return quantite;
    }

    public BigDecimal getStockApres() {
        return stockApres;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getMotif() {
        return motif;
    }

    public UUID getInventaireId() {
        return inventaireId;
    }

    public UUID getAuteur() {
        return auteur;
    }

    public Instant getCreeLe() {
        return creeLe;
    }
}
