package bf.edutech.plateforme.bulletins;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Visa d'une matière sur les bulletins d'une classe pour une période (v0.44) : l'enseignant atteste ses
 * notes et ses appréciations ; le bulletin imprime alors sa signature devant la matière.
 */
@Entity
@Table(name = "visa_matiere")
class VisaMatiere extends EntiteCloisonnee {

    @Column(name = "classe_id", nullable = false, updatable = false)
    private UUID classeId;

    @Column(name = "matiere_id", nullable = false, updatable = false)
    private UUID matiereId;

    @Column(name = "periode_id", nullable = false, updatable = false)
    private UUID periodeId;

    @Column(name = "engagement_id", nullable = false, updatable = false)
    private UUID engagementId;

    @Column(name = "vise_par", nullable = false, updatable = false)
    private UUID visePar;

    @Column(name = "vise_le", nullable = false, updatable = false)
    private Instant viseLe;

    protected VisaMatiere() {
    }

    VisaMatiere(UUID classeId, UUID matiereId, UUID periodeId, UUID engagementId, UUID visePar, Instant viseLe) {
        this.classeId = classeId;
        this.matiereId = matiereId;
        this.periodeId = periodeId;
        this.engagementId = engagementId;
        this.visePar = visePar;
        this.viseLe = viseLe;
    }

    UUID getMatiereId() {
        return matiereId;
    }

    UUID getEngagementId() {
        return engagementId;
    }

    UUID getVisePar() {
        return visePar;
    }

    Instant getViseLe() {
        return viseLe;
    }
}
