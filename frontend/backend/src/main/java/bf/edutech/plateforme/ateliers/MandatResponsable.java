package bf.edutech.plateforme.ateliers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Mandat d'un responsable d'atelier : un enseignant technique de la filière, désigné pour une
 * durée propre à l'établissement. Un seul mandat en cours par atelier ; les anciens restent
 * dans l'historique.
 */
@Entity
@Table(name = "mandat_responsable")
public class MandatResponsable extends EntiteCloisonnee {

    @Column(name = "atelier_id", nullable = false, updatable = false)
    private UUID atelierId;

    @Column(name = "engagement_id", nullable = false, updatable = false)
    private UUID engagementId;

    @Column(name = "debut", nullable = false)
    private LocalDate debut;

    @Column(name = "fin_prevue")
    private LocalDate finPrevue;

    @Column(name = "fin")
    private LocalDate fin;

    @Column(name = "motif_fin", length = 200)
    private String motifFin;

    @Column(name = "designe_par", updatable = false)
    private UUID designePar;

    @Column(name = "designe_le", nullable = false, updatable = false)
    private Instant designeLe;

    protected MandatResponsable() {
    }

    MandatResponsable(UUID atelierId, UUID engagementId, LocalDate debut, LocalDate finPrevue, UUID designePar,
            Instant maintenant) {
        this.atelierId = atelierId;
        this.engagementId = engagementId;
        this.debut = debut;
        this.finPrevue = finPrevue;
        this.designePar = designePar;
        this.designeLe = maintenant;
    }

    void terminer(LocalDate date, String motif) {
        this.fin = date.isBefore(debut) ? debut : date;
        this.motifFin = motif;
    }

    boolean enCours() {
        return fin == null;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public UUID getEngagementId() {
        return engagementId;
    }

    public LocalDate getDebut() {
        return debut;
    }

    public LocalDate getFinPrevue() {
        return finPrevue;
    }

    public LocalDate getFin() {
        return fin;
    }

    public String getMotifFin() {
        return motifFin;
    }

    public Instant getDesigneLe() {
        return designeLe;
    }
}
