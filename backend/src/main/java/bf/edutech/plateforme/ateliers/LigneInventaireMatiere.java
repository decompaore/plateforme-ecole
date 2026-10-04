package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Ligne d'inventaire d'une matière d'œuvre : quantité théorique (à l'ouverture) et constatée. */
@Entity
@Table(name = "ligne_inventaire_matiere")
public class LigneInventaireMatiere extends EntiteCloisonnee {

    @Column(name = "inventaire_id", nullable = false, updatable = false)
    private UUID inventaireId;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "quantite_theorique", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantiteTheorique;

    @Column(name = "quantite_constatee", precision = 12, scale = 2)
    private BigDecimal quantiteConstatee;

    protected LigneInventaireMatiere() {
    }

    LigneInventaireMatiere(UUID inventaireId, UUID articleId, BigDecimal quantiteTheorique) {
        this.inventaireId = inventaireId;
        this.articleId = articleId;
        this.quantiteTheorique = quantiteTheorique;
    }

    void constater(BigDecimal quantite) {
        this.quantiteConstatee = quantite;
    }

    public UUID getInventaireId() {
        return inventaireId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public BigDecimal getQuantiteTheorique() {
        return quantiteTheorique;
    }

    public BigDecimal getQuantiteConstatee() {
        return quantiteConstatee;
    }
}
