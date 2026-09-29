package bf.edutech.plateforme.mobilemoney;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Paiement Mobile Money initié par un parent, suivi jusqu'à sa confirmation. */
@Entity
@Table(name = "transaction_mobile_money")
public class TransactionMobileMoney extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "montant", nullable = false, updatable = false)
    private long montant;

    @Enumerated(EnumType.STRING)
    @Column(name = "operateur", nullable = false, length = 12, updatable = false)
    private Operateur operateur;

    @Column(name = "telephone", nullable = false, length = 20, updatable = false)
    private String telephone;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 12)
    private StatutTransaction statut = StatutTransaction.INITIEE;

    @Column(name = "reference", nullable = false, length = 40, updatable = false)
    private String reference;

    @Column(name = "reference_agregateur", length = 100)
    private String referenceAgregateur;

    @Column(name = "cle_idempotence", nullable = false, length = 64, updatable = false)
    private String cleIdempotence;

    @Column(name = "montant_recu")
    private Long montantRecu;

    @Column(name = "paiement_id")
    private UUID paiementId;

    @Column(name = "message", length = 300)
    private String message;

    @Column(name = "initiee_par", updatable = false)
    private UUID initieePar;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe;

    @Column(name = "expire_le", nullable = false, updatable = false)
    private Instant expireLe;

    @Column(name = "termine_le")
    private Instant termineLe;

    @Column(name = "derniere_tentative")
    private Instant derniereTentative;

    protected TransactionMobileMoney() {
    }

    TransactionMobileMoney(UUID inscriptionId, long montant, Operateur operateur, String telephone,
            String cleIdempotence, UUID initieePar, Instant creeLe, Instant expireLe) {
        this.inscriptionId = inscriptionId;
        this.montant = montant;
        this.operateur = operateur;
        this.telephone = telephone;
        this.cleIdempotence = cleIdempotence;
        this.initieePar = initieePar;
        this.creeLe = creeLe;
        this.expireLe = expireLe;
        this.reference = "MM-" + getId().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    void enAttente(String referenceAgregateur) {
        this.statut = StatutTransaction.EN_ATTENTE;
        this.referenceAgregateur = referenceAgregateur;
    }

    void confirmer(UUID paiementId, long montantRecu, String referenceAgregateur, Instant le) {
        this.statut = StatutTransaction.CONFIRMEE;
        this.paiementId = paiementId;
        this.montantRecu = montantRecu;
        if (referenceAgregateur != null) {
            this.referenceAgregateur = referenceAgregateur;
        }
        this.termineLe = le;
        this.message = null;
    }

    /** Vérification impossible (agrégateur injoignable…) : la tâche réessaiera plus tard. */
    void noterTentative(Instant le) {
        this.derniereTentative = le;
    }

    UUID getInitieePar() {
        return initieePar;
    }

    void terminer(StatutTransaction statut, Long montantRecu, String message, Instant le) {
        this.statut = statut;
        this.montantRecu = montantRecu;
        this.message = message == null ? null : message.length() > 300 ? message.substring(0, 300) : message;
        this.termineLe = le;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    long getMontant() {
        return montant;
    }

    Operateur getOperateur() {
        return operateur;
    }

    String getTelephone() {
        return telephone;
    }

    StatutTransaction getStatut() {
        return statut;
    }

    String getReference() {
        return reference;
    }

    String getReferenceAgregateur() {
        return referenceAgregateur;
    }

    String getCleIdempotence() {
        return cleIdempotence;
    }

    Long getMontantRecu() {
        return montantRecu;
    }

    UUID getPaiementId() {
        return paiementId;
    }

    String getMessage() {
        return message;
    }

    Instant getCreeLe() {
        return creeLe;
    }

    Instant getExpireLe() {
        return expireLe;
    }

    Instant getTermineLe() {
        return termineLe;
    }
}
