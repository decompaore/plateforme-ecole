package bf.edutech.plateforme.viescolaire;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Convocation d'un parent ; close une fois honorée, non honorée ou annulée. */
@Entity
@Table(name = "convocation")
public class Convocation extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "incident_id", updatable = false)
    private UUID incidentId;

    @Column(name = "rendez_vous", nullable = false, updatable = false)
    private LocalDateTime rendezVous;

    @Column(name = "motif", nullable = false, length = 300, updatable = false)
    private String motif;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 12)
    private StatutConvocation statut = StatutConvocation.PREVUE;

    @Column(name = "compte_rendu", length = 500)
    private String compteRendu;

    @Column(name = "saisi_par", updatable = false)
    private UUID saisiPar;

    @Column(name = "saisi_le", nullable = false, updatable = false)
    private Instant saisiLe;

    @Column(name = "cloture_par")
    private UUID cloturePar;

    @Column(name = "cloture_le")
    private Instant clotureLe;

    protected Convocation() {
    }

    Convocation(UUID inscriptionId, UUID incidentId, LocalDateTime rendezVous, String motif, UUID saisiPar,
            Instant saisiLe) {
        this.inscriptionId = inscriptionId;
        this.incidentId = incidentId;
        this.rendezVous = rendezVous;
        this.motif = motif;
        this.saisiPar = saisiPar;
        this.saisiLe = saisiLe;
    }

    void cloturer(StatutConvocation nouveau, String compteRendu, UUID par, Instant le) {
        if (statut != StatutConvocation.PREVUE) {
            throw new RegleMetierException("CONVOCATION_CLOSE", "Cette convocation est déjà close");
        }
        if (nouveau == StatutConvocation.PREVUE) {
            throw new IllegalArgumentException("Indiquez HONOREE, NON_HONOREE ou ANNULEE");
        }
        this.statut = nouveau;
        this.compteRendu = compteRendu;
        this.cloturePar = par;
        this.clotureLe = le;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    UUID getIncidentId() {
        return incidentId;
    }

    LocalDateTime getRendezVous() {
        return rendezVous;
    }

    String getMotif() {
        return motif;
    }

    StatutConvocation getStatut() {
        return statut;
    }

    String getCompteRendu() {
        return compteRendu;
    }
}
