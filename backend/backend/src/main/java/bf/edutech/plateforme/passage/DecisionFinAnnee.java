package bf.edutech.plateforme.passage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Décision de fin d'année d'un élève : proposition du moteur, décision du conseil, validation. */
@Entity
@Table(name = "decision_fin_annee")
public class DecisionFinAnnee extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "classe_id", nullable = false)
    private UUID classeId;

    @Column(name = "moyenne_annuelle", precision = 6, scale = 3)
    private BigDecimal moyenneAnnuelle;

    @Column(name = "rang")
    private Integer rang;

    @Column(name = "taux_maitrise", precision = 5, scale = 2)
    private BigDecimal tauxMaitrise;

    @Column(name = "redoublements", nullable = false)
    private short redoublements;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultat_examen", length = 8)
    private ResultatExamen resultatExamen;

    @Enumerated(EnumType.STRING)
    @Column(name = "proposition", nullable = false, length = 18)
    private Decision proposition;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 18)
    private Decision decision;

    @Column(name = "motif", length = 300)
    private String motif;

    @Column(name = "calcule_le", nullable = false)
    private Instant calculeLe;

    @Column(name = "modifie_par")
    private UUID modifiePar;

    @Column(name = "valide_par")
    private UUID validePar;

    @Column(name = "valide_le")
    private Instant valideLe;

    protected DecisionFinAnnee() {
    }

    DecisionFinAnnee(UUID inscriptionId) {
        this.inscriptionId = inscriptionId;
    }

    /** Nouveaux résultats calculés ; une décision modifiée par le conseil (avec motif) est conservée. */
    void calculer(UUID classeId, BigDecimal moyenneAnnuelle, Integer rang, BigDecimal tauxMaitrise,
            int redoublements, Decision proposition, Instant le) {
        this.classeId = classeId;
        this.moyenneAnnuelle = moyenneAnnuelle;
        this.rang = rang;
        this.tauxMaitrise = tauxMaitrise;
        this.redoublements = (short) redoublements;
        proposer(proposition);
        this.calculeLe = le;
    }

    void proposer(Decision nouvelle) {
        this.proposition = nouvelle;
        if (this.decision == null || this.motif == null) {
            this.decision = nouvelle;
        } else if (this.decision == nouvelle) {
            this.motif = null;                               // le conseil rejoint la proposition
        }
    }

    void resultatExamen(ResultatExamen resultat) {
        this.resultatExamen = resultat;
    }

    /** Décision du conseil : un motif est obligatoire si elle s'écarte de la proposition. */
    void decider(Decision nouvelle, String motif, UUID par) {
        this.decision = nouvelle;
        this.motif = nouvelle == proposition ? null : motif;
        this.modifiePar = par;
    }

    void valider(UUID par, Instant le) {
        this.validePar = par;
        this.valideLe = le;
    }

    boolean estValidee() {
        return valideLe != null;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    UUID getClasseId() {
        return classeId;
    }

    BigDecimal getMoyenneAnnuelle() {
        return moyenneAnnuelle;
    }

    Integer getRang() {
        return rang;
    }

    BigDecimal getTauxMaitrise() {
        return tauxMaitrise;
    }

    int getRedoublements() {
        return redoublements;
    }

    ResultatExamen getResultatExamen() {
        return resultatExamen;
    }

    Decision getProposition() {
        return proposition;
    }

    Decision getDecision() {
        return decision;
    }

    String getMotif() {
        return motif;
    }

    Instant getValideLe() {
        return valideLe;
    }
}
