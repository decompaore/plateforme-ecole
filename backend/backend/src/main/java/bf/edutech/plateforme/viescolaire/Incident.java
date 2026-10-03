package bf.edutech.plateforme.viescolaire;

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

/** Incident de vie scolaire ; jamais supprimé, seulement annulé avec un motif. */
@Entity
@Table(name = "incident")
public class Incident extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 22, updatable = false)
    private TypeIncident type;

    @Column(name = "date_faits", nullable = false, updatable = false)
    private LocalDate dateFaits;

    @Column(name = "motif", nullable = false, length = 300, updatable = false)
    private String motif;

    @Column(name = "minutes_retard", updatable = false)
    private Short minutesRetard;

    @Column(name = "debut_exclusion", updatable = false)
    private LocalDate debutExclusion;

    @Column(name = "jours_exclusion", updatable = false)
    private Short joursExclusion;

    @Column(name = "famille_prevenue", nullable = false)
    private boolean famillePrevenue;

    @Column(name = "saisi_par", updatable = false)
    private UUID saisiPar;

    @Column(name = "saisi_le", nullable = false, updatable = false)
    private Instant saisiLe;

    @Column(name = "annule_le")
    private Instant annuleLe;

    @Column(name = "annule_par")
    private UUID annulePar;

    @Column(name = "motif_annulation", length = 300)
    private String motifAnnulation;

    protected Incident() {
    }

    Incident(UUID inscriptionId, TypeIncident type, LocalDate dateFaits, String motif, Integer minutesRetard,
            LocalDate debutExclusion, Integer joursExclusion, UUID saisiPar, Instant saisiLe) {
        this.inscriptionId = inscriptionId;
        this.type = type;
        this.dateFaits = dateFaits;
        this.motif = motif;
        this.minutesRetard = minutesRetard == null ? null : minutesRetard.shortValue();
        this.debutExclusion = debutExclusion;
        this.joursExclusion = joursExclusion == null ? null : joursExclusion.shortValue();
        this.saisiPar = saisiPar;
        this.saisiLe = saisiLe;
    }

    void famillePrevenue() {
        this.famillePrevenue = true;
    }

    /** Le SMS a été retiré avant son envoi. */
    void famillePasPrevenue() {
        this.famillePrevenue = false;
    }

    void annuler(String motif, UUID par, Instant le) {
        if (annuleLe != null) {
            throw new RegleMetierException("INCIDENT_DEJA_ANNULE", "Cet incident est déjà annulé");
        }
        this.motifAnnulation = motif;
        this.annulePar = par;
        this.annuleLe = le;
    }

    boolean estAnnule() {
        return annuleLe != null;
    }

    LocalDate finExclusion() {
        return debutExclusion == null ? null : debutExclusion.plusDays(joursExclusion - 1L);
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    TypeIncident getType() {
        return type;
    }

    LocalDate getDateFaits() {
        return dateFaits;
    }

    String getMotif() {
        return motif;
    }

    Integer getMinutesRetard() {
        return minutesRetard == null ? null : minutesRetard.intValue();
    }

    LocalDate getDebutExclusion() {
        return debutExclusion;
    }

    Integer getJoursExclusion() {
        return joursExclusion == null ? null : joursExclusion.intValue();
    }

    boolean isFamillePrevenue() {
        return famillePrevenue;
    }

    UUID getSaisiPar() {
        return saisiPar;
    }

    Instant getSaisiLe() {
        return saisiLe;
    }

    String getMotifAnnulation() {
        return motifAnnulation;
    }
}
