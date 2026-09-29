package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Note d'un élève à une évaluation ; {@code absent} : l'élève n'a pas composé (non compté). */
@Entity
@Table(name = "note")
public class Note extends EntiteCloisonnee {

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "valeur", precision = 5, scale = 2)
    private BigDecimal valeur;

    @Column(name = "absent", nullable = false)
    private boolean absent;

    @Column(name = "saisi_par")
    private UUID saisiPar;

    @Column(name = "saisi_le", nullable = false)
    private Instant saisiLe;

    protected Note() {
    }

    Note(UUID evaluationId, UUID inscriptionId) {
        this.evaluationId = evaluationId;
        this.inscriptionId = inscriptionId;
    }

    void definir(BigDecimal valeur, boolean absent, UUID saisiPar, Instant saisiLe) {
        this.valeur = absent ? null : valeur;
        this.absent = absent;
        this.saisiPar = saisiPar;
        this.saisiLe = saisiLe;
    }

    boolean identique(BigDecimal autreValeur, boolean autreAbsent) {
        if (absent || autreAbsent) {
            return absent == autreAbsent;
        }
        return valeur != null && autreValeur != null && valeur.compareTo(autreValeur) == 0;
    }

    UUID getEvaluationId() {
        return evaluationId;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    BigDecimal getValeur() {
        return valeur;
    }

    boolean isAbsent() {
        return absent;
    }
}
