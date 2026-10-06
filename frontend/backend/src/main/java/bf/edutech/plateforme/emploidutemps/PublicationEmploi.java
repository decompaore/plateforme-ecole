package bf.edutech.plateforme.emploidutemps;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Emploi du temps de l'année communiqué aux enseignants (dernière publication). */
@Entity
@Table(name = "publication_emploi")
public class PublicationEmploi extends EntiteCloisonnee {

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Column(name = "publie_le", nullable = false)
    private Instant publieLe;

    @Column(name = "publie_par")
    private UUID publiePar;

    protected PublicationEmploi() {
    }

    PublicationEmploi(UUID anneeId) {
        this.anneeId = anneeId;
    }

    void publier(UUID par, Instant maintenant) {
        this.publiePar = par;
        this.publieLe = maintenant;
    }

    Instant getPublieLe() {
        return publieLe;
    }
}
