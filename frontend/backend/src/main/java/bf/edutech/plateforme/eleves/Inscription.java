package bf.edutech.plateforme.eleves;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Présence d'un élève dans une classe pour une année scolaire (une seule par année). */
@Entity
@Table(name = "inscription")
public class Inscription extends EntiteCloisonnee {

    @Column(name = "eleve_id", nullable = false, updatable = false)
    private UUID eleveId;

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Column(name = "classe_id", nullable = false)
    private UUID classeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 12)
    private StatutInscription statut = StatutInscription.ACTIVE;

    @Column(name = "redoublant", nullable = false)
    private boolean redoublant;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut_bourse", nullable = false, length = 14)
    private StatutBourse statutBourse;

    @Column(name = "inscrit_le", nullable = false, updatable = false)
    private LocalDate inscritLe;

    @Column(name = "date_sortie")
    private LocalDate dateSortie;

    @Column(name = "motif_sortie", length = 200)
    private String motifSortie;

    @Column(name = "inscription_precedente_id", updatable = false)
    private UUID inscriptionPrecedenteId;

    protected Inscription() {
    }

    Inscription(UUID eleveId, UUID anneeId, UUID classeId, boolean redoublant, StatutBourse statutBourse,
            LocalDate inscritLe, UUID inscriptionPrecedenteId) {
        this.eleveId = eleveId;
        this.anneeId = anneeId;
        this.classeId = classeId;
        this.redoublant = redoublant;
        this.statutBourse = statutBourse != null ? statutBourse : StatutBourse.NON_BOURSIER;
        this.inscritLe = inscritLe;
        this.inscriptionPrecedenteId = inscriptionPrecedenteId;
    }

    boolean estActive() {
        return statut == StatutInscription.ACTIVE;
    }

    void changerClasse(UUID classeId) {
        this.classeId = classeId;
    }

    void changerStatutBourse(StatutBourse statutBourse) {
        this.statutBourse = statutBourse;
    }

    void sortir(StatutInscription nouveauStatut, LocalDate date, String motif) {
        if (nouveauStatut == StatutInscription.ACTIVE) {
            throw new IllegalArgumentException("Le statut de sortie doit être TRANSFEREE ou ABANDON");
        }
        if (date.isBefore(inscritLe)) {
            throw new IllegalArgumentException("La date de sortie ne peut précéder la date d'inscription");
        }
        this.statut = nouveauStatut;
        this.dateSortie = date;
        this.motifSortie = motif;
    }

    UUID getEleveId() {
        return eleveId;
    }

    UUID getAnneeId() {
        return anneeId;
    }

    UUID getClasseId() {
        return classeId;
    }

    StatutInscription getStatut() {
        return statut;
    }

    boolean isRedoublant() {
        return redoublant;
    }

    StatutBourse getStatutBourse() {
        return statutBourse;
    }

    LocalDate getInscritLe() {
        return inscritLe;
    }

    LocalDate getDateSortie() {
        return dateSortie;
    }

    String getMotifSortie() {
        return motifSortie;
    }

    UUID getInscriptionPrecedenteId() {
        return inscriptionPrecedenteId;
    }
}
