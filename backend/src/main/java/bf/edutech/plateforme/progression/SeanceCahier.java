package bf.edutech.plateforme.progression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Une séance du cahier de textes : ce qui a été fait à un cours, et le travail donné. */
@Entity
@Table(name = "seance_cahier")
public class SeanceCahier extends EntiteCloisonnee {

    @Column(name = "classe_id", nullable = false, updatable = false)
    private UUID classeId;

    @Column(name = "matiere_id", nullable = false, updatable = false)
    private UUID matiereId;

    @Column(name = "engagement_id", nullable = false)
    private UUID engagementId;

    @Column(name = "date_seance", nullable = false)
    private LocalDate date;

    @Column(name = "heure_debut", nullable = false)
    private LocalTime heureDebut;

    @Column(name = "heure_fin", nullable = false)
    private LocalTime heureFin;

    @Column(name = "sequence_ordre")
    private Short sequenceOrdre;

    @Column(name = "sequence_titre", length = 150)
    private String sequenceTitre;

    @Column(name = "contenu", nullable = false, length = 2000)
    private String contenu;

    @Column(name = "travail_a_faire", length = 1000)
    private String travailAFaire;

    @Column(name = "saisi_par", updatable = false)
    private UUID saisiPar;

    @Column(name = "saisi_le", nullable = false, updatable = false)
    private Instant saisiLe;

    @Column(name = "modifie_le", nullable = false)
    private Instant modifieLe;

    protected SeanceCahier() {
    }

    /** L'identifiant vient du téléphone : renvoyer la même séance ne la duplique pas. */
    SeanceCahier(UUID id, UUID classeId, UUID matiereId, UUID saisiPar, Instant maintenant) {
        imposerId(id);
        this.classeId = classeId;
        this.matiereId = matiereId;
        this.saisiPar = saisiPar;
        this.saisiLe = maintenant;
    }

    void remplir(UUID engagementId, LocalDate date, LocalTime heureDebut, LocalTime heureFin, Integer sequenceOrdre,
            String sequenceTitre, String contenu, String travailAFaire, Instant maintenant) {
        this.engagementId = engagementId;
        this.date = date;
        this.heureDebut = heureDebut;
        this.heureFin = heureFin;
        this.sequenceOrdre = sequenceOrdre == null ? null : sequenceOrdre.shortValue();
        this.sequenceTitre = sequenceOrdre == null ? null : sequenceTitre;
        this.contenu = contenu;
        this.travailAFaire = travailAFaire;
        this.modifieLe = maintenant;
    }

    /** Durée en heures, à une décimale (2 h, 1,5 h). */
    BigDecimal heures() {
        return BigDecimal.valueOf(Duration.between(heureDebut, heureFin).toMinutes())
                .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
    }

    public UUID getClasseId() {
        return classeId;
    }

    public UUID getMatiereId() {
        return matiereId;
    }

    public UUID getEngagementId() {
        return engagementId;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalTime getHeureDebut() {
        return heureDebut;
    }

    public LocalTime getHeureFin() {
        return heureFin;
    }

    public Integer getSequenceOrdre() {
        return sequenceOrdre == null ? null : sequenceOrdre.intValue();
    }

    public String getSequenceTitre() {
        return sequenceTitre;
    }

    public String getContenu() {
        return contenu;
    }

    public String getTravailAFaire() {
        return travailAFaire;
    }

    public Instant getSaisiLe() {
        return saisiLe;
    }

    public Instant getModifieLe() {
        return modifieLe;
    }
}
