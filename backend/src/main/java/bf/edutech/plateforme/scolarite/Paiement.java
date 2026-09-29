package bf.edutech.plateforme.scolarite;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Encaissement. Jamais modifié ni supprimé (garanti aussi par la base) :
 * une erreur se corrige en l'annulant, avec un motif, puis en encaissant à nouveau.
 */
@Entity
@Table(name = "paiement")
public class Paiement extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "montant", nullable = false, updatable = false)
    private long montant;

    @Enumerated(EnumType.STRING)
    @Column(name = "moyen", nullable = false, length = 12, updatable = false)
    private MoyenPaiement moyen;

    @Enumerated(EnumType.STRING)
    @Column(name = "payeur", nullable = false, length = 10, updatable = false)
    private Payeur payeur;

    @Column(name = "organisme_id", updatable = false)
    private UUID organismeId;

    @Column(name = "reference_externe", length = 60, updatable = false)
    private String referenceExterne;

    @Column(name = "deposant", length = 120, updatable = false)
    private String deposant;

    @Column(name = "date_paiement", nullable = false, updatable = false)
    private LocalDate datePaiement;

    @Column(name = "cle_idempotence", nullable = false, length = 64, updatable = false)
    private String cleIdempotence;

    @Column(name = "encaisse_par", updatable = false)
    private UUID encaissePar;

    @Column(name = "enregistre_le", nullable = false, updatable = false)
    private Instant enregistreLe;

    @Column(name = "annule_le")
    private Instant annuleLe;

    @Column(name = "annule_par")
    private UUID annulePar;

    @Column(name = "motif_annulation", length = 200)
    private String motifAnnulation;

    protected Paiement() {
    }

    Paiement(UUID inscriptionId, long montant, MoyenPaiement moyen, Payeur payeur, UUID organismeId,
            String referenceExterne, String deposant, LocalDate datePaiement, String cleIdempotence,
            UUID encaissePar, Instant enregistreLe) {
        this.inscriptionId = inscriptionId;
        this.montant = montant;
        this.moyen = moyen;
        this.payeur = payeur;
        this.organismeId = organismeId;
        this.referenceExterne = referenceExterne;
        this.deposant = deposant;
        this.datePaiement = datePaiement;
        this.cleIdempotence = cleIdempotence;
        this.encaissePar = encaissePar;
        this.enregistreLe = enregistreLe;
    }

    void annuler(String motif, UUID par, Instant le) {
        if (estAnnule()) {
            throw new RegleMetierException("PAIEMENT_DEJA_ANNULE", "Ce paiement est déjà annulé");
        }
        this.motifAnnulation = motif;
        this.annulePar = par;
        this.annuleLe = le;
    }

    boolean estAnnule() {
        return annuleLe != null;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    long getMontant() {
        return montant;
    }

    MoyenPaiement getMoyen() {
        return moyen;
    }

    Payeur getPayeur() {
        return payeur;
    }

    UUID getOrganismeId() {
        return organismeId;
    }

    String getReferenceExterne() {
        return referenceExterne;
    }

    String getDeposant() {
        return deposant;
    }

    LocalDate getDatePaiement() {
        return datePaiement;
    }

    String getCleIdempotence() {
        return cleIdempotence;
    }

    Instant getEnregistreLe() {
        return enregistreLe;
    }

    Instant getAnnuleLe() {
        return annuleLe;
    }

    String getMotifAnnulation() {
        return motifAnnulation;
    }
}
