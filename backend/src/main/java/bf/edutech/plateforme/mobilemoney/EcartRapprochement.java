package bf.edutech.plateforme.mobilemoney;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Écart entre la plateforme et le relevé de l'agrégateur :
 * NON_ENREGISTRE (argent reçu, aucun paiement enregistré), ABSENT_DU_RELEVE
 * (paiement enregistré, absent du relevé), MONTANT_DIFFERENT.
 */
@Entity
@Table(name = "ecart_rapprochement")
public class EcartRapprochement extends EntiteCloisonnee {

    @Column(name = "rapprochement_id", nullable = false, updatable = false)
    private UUID rapprochementId;

    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private String type;

    @Column(name = "reference", nullable = false, length = 100, updatable = false)
    private String reference;

    @Column(name = "montant_attendu", updatable = false)
    private Long montantAttendu;

    @Column(name = "montant_recu", updatable = false)
    private Long montantRecu;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "traite", nullable = false)
    private boolean traite;

    @Column(name = "traite_par")
    private UUID traitePar;

    @Column(name = "traite_le")
    private Instant traiteLe;

    @Column(name = "commentaire", length = 300)
    private String commentaire;

    protected EcartRapprochement() {
    }

    EcartRapprochement(UUID rapprochementId, String type, String reference, Long montantAttendu, Long montantRecu,
            UUID transactionId) {
        this.rapprochementId = rapprochementId;
        this.type = type;
        this.reference = reference.length() > 100 ? reference.substring(0, 100) : reference;
        this.montantAttendu = montantAttendu;
        this.montantRecu = montantRecu;
        this.transactionId = transactionId;
    }

    void traiter(String commentaire, UUID par, Instant le) {
        this.traite = true;
        this.commentaire = commentaire;
        this.traitePar = par;
        this.traiteLe = le;
    }

    UUID getRapprochementId() {
        return rapprochementId;
    }

    String getType() {
        return type;
    }

    String getReference() {
        return reference;
    }

    Long getMontantAttendu() {
        return montantAttendu;
    }

    Long getMontantRecu() {
        return montantRecu;
    }

    UUID getTransactionId() {
        return transactionId;
    }

    boolean isTraite() {
        return traite;
    }

    String getCommentaire() {
        return commentaire;
    }
}
