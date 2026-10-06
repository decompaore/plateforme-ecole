package bf.edutech.plateforme.absences;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Séance d'appel d'une classe ; l'identifiant est généré par l'appareil. */
@Entity
@Table(name = "appel")
public class Appel extends EntiteCloisonnee {

    @Column(name = "classe_id", nullable = false, updatable = false)
    private UUID classeId;

    @Column(name = "matiere_id")
    private UUID matiereId;

    @Column(name = "date_appel", nullable = false, updatable = false)
    private LocalDate dateAppel;

    @Column(name = "heure_debut", nullable = false, updatable = false)
    private LocalTime heureDebut;

    @Column(name = "heure_fin", nullable = false)
    private LocalTime heureFin;

    @Column(name = "fait_par", nullable = false, updatable = false)
    private UUID faitPar;

    @Column(name = "saisi_le", nullable = false, updatable = false)
    private Instant saisiLe;

    @Column(name = "recu_le", nullable = false, updatable = false)
    private Instant recuLe;

    @Column(name = "modifie_par")
    private UUID modifiePar;

    @Column(name = "modifie_le")
    private Instant modifieLe;

    protected Appel() {
    }

    Appel(UUID idClient, UUID classeId, UUID matiereId, LocalDate dateAppel, LocalTime heureDebut,
            LocalTime heureFin, UUID faitPar, Instant saisiLe, Instant recuLe) {
        imposerId(idClient);
        this.classeId = classeId;
        this.matiereId = matiereId;
        this.dateAppel = dateAppel;
        this.heureDebut = heureDebut;
        this.heureFin = heureFin;
        this.faitPar = faitPar;
        this.saisiLe = saisiLe;
        this.recuLe = recuLe;
    }

    void corriger(LocalTime heureFin, UUID matiereId) {
        this.heureFin = heureFin;
        this.matiereId = matiereId;
    }

    void marquerModifie(UUID par, Instant le) {
        this.modifiePar = par;
        this.modifieLe = le;
    }

    UUID getClasseId() {
        return classeId;
    }

    UUID getMatiereId() {
        return matiereId;
    }

    LocalDate getDateAppel() {
        return dateAppel;
    }

    LocalTime getHeureDebut() {
        return heureDebut;
    }

    LocalTime getHeureFin() {
        return heureFin;
    }

    UUID getFaitPar() {
        return faitPar;
    }

    Instant getSaisiLe() {
        return saisiLe;
    }

    Instant getRecuLe() {
        return recuLe;
    }

    Instant getModifieLe() {
        return modifieLe;
    }
}
