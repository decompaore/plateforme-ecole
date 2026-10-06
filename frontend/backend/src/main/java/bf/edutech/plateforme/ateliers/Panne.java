package bf.edutech.plateforme.ateliers;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Panne d'un équipement : signalée par un enseignant de l'atelier, suivie jusqu'à la réparation. */
@Entity
@Table(name = "panne")
public class Panne extends EntiteCloisonnee {

    @Column(name = "equipement_id", nullable = false, updatable = false)
    private UUID equipementId;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "signalee_par", updatable = false)
    private UUID signaleePar;

    @Column(name = "signalee_le", nullable = false, updatable = false)
    private Instant signaleeLe;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 12)
    private StatutPanne statut = StatutPanne.OUVERTE;

    @Column(name = "intervention", length = 500)
    private String intervention;

    @Column(name = "cout")
    private Long cout;

    @Column(name = "cloturee_par")
    private UUID clotureePar;

    @Column(name = "cloturee_le")
    private Instant clotureeLe;

    protected Panne() {
    }

    Panne(UUID equipementId, String description, UUID signaleePar, Instant maintenant) {
        this.equipementId = equipementId;
        this.description = description;
        this.signaleePar = signaleePar;
        this.signaleeLe = maintenant;
    }

    void cloturer(StatutPanne statut, String intervention, Long cout, UUID par, Instant maintenant) {
        this.statut = statut;
        this.intervention = intervention;
        this.cout = cout;
        this.clotureePar = par;
        this.clotureeLe = maintenant;
    }

    public UUID getEquipementId() {
        return equipementId;
    }

    public String getDescription() {
        return description;
    }

    public UUID getSignaleePar() {
        return signaleePar;
    }

    public Instant getSignaleeLe() {
        return signaleeLe;
    }

    public StatutPanne getStatut() {
        return statut;
    }

    public String getIntervention() {
        return intervention;
    }

    public Long getCout() {
        return cout;
    }

    public UUID getClotureePar() {
        return clotureePar;
    }

    public Instant getClotureeLe() {
        return clotureeLe;
    }
}
