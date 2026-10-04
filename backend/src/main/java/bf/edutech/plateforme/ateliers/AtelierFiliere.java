package bf.edutech.plateforme.ateliers;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Filière servie par un atelier (un atelier peut servir plusieurs filières). */
@Entity
@Table(name = "atelier_filiere")
public class AtelierFiliere extends EntiteCloisonnee {

    @Column(name = "atelier_id", nullable = false, updatable = false)
    private UUID atelierId;

    @Column(name = "filiere_id", nullable = false, updatable = false)
    private UUID filiereId;

    protected AtelierFiliere() {
    }

    AtelierFiliere(UUID atelierId, UUID filiereId) {
        this.atelierId = atelierId;
        this.filiereId = filiereId;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public UUID getFiliereId() {
        return filiereId;
    }
}
