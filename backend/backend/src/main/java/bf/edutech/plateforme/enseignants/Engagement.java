package bf.edutech.plateforme.enseignants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Engagement d'un enseignant dans l'établissement : titulaire ou vacataire. */
@Entity
@Table(name = "engagement_enseignant")
public class Engagement extends EntiteCloisonnee {

    @Column(name = "enseignant_id", nullable = false, updatable = false)
    private UUID enseignantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private TypeEngagement type;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutEngagement statut;

    @Column(name = "debut", nullable = false, updatable = false)
    private LocalDate debut;

    @Column(name = "fin")
    private LocalDate fin;

    @Column(name = "taux_horaire", precision = 8, scale = 0)
    private BigDecimal tauxHoraire;

    @Column(name = "motif_fin", length = 200)
    private String motifFin;

    protected Engagement() {
    }

    Engagement(UUID enseignantId, TypeEngagement type, StatutEngagement statut, LocalDate debut, LocalDate fin,
            BigDecimal tauxHoraire) {
        this.enseignantId = enseignantId;
        this.type = type;
        this.statut = statut;
        this.debut = debut;
        this.fin = fin;
        this.tauxHoraire = tauxHoraire;
    }

    void accepter() {
        exigerStatut(StatutEngagement.INVITE);
        this.statut = StatutEngagement.ACTIF;
    }

    void refuser() {
        exigerStatut(StatutEngagement.INVITE);
        this.statut = StatutEngagement.REFUSE;
    }

    void terminer(LocalDate date, String motif) {
        exigerStatut(StatutEngagement.ACTIF);
        if (date.isBefore(debut)) {
            throw new IllegalArgumentException("La date de fin ne peut précéder le début de l'engagement");
        }
        this.statut = StatutEngagement.TERMINE;
        this.fin = date;
        this.motifFin = motif;
    }

    private void exigerStatut(StatutEngagement attendu) {
        if (statut != attendu) {
            throw new RegleMetierException("STATUT_ENGAGEMENT", "Opération impossible : l'engagement est "
                    + statut.name().toLowerCase() + " (attendu : " + attendu.name().toLowerCase() + ")");
        }
    }

    UUID getEnseignantId() {
        return enseignantId;
    }

    TypeEngagement getType() {
        return type;
    }

    StatutEngagement getStatut() {
        return statut;
    }

    LocalDate getDebut() {
        return debut;
    }

    LocalDate getFin() {
        return fin;
    }

    BigDecimal getTauxHoraire() {
        return tauxHoraire;
    }

    String getMotifFin() {
        return motifFin;
    }
}
