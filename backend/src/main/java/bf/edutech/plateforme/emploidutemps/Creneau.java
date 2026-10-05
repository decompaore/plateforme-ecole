package bf.edutech.plateforme.emploidutemps;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Heure de cours de la grille de l'année, et jours de la semaine où elle existe. */
@Entity
@Table(name = "creneau_horaire")
public class Creneau extends EntiteCloisonnee {

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Column(name = "heure_debut", nullable = false)
    private LocalTime heureDebut;

    @Column(name = "heure_fin", nullable = false)
    private LocalTime heureFin;

    /** Chiffres ISO des jours (1 = lundi … 7 = dimanche), ex. « 12345 ». */
    @Column(name = "jours", nullable = false, length = 7)
    private String jours;

    protected Creneau() {
    }

    Creneau(UUID anneeId) {
        this.anneeId = anneeId;
    }

    void definir(LocalTime debut, LocalTime fin, Set<Integer> jours) {
        this.heureDebut = debut;
        this.heureFin = fin;
        StringBuilder s = new StringBuilder();
        new TreeSet<>(jours).forEach(s::append);
        this.jours = s.toString();
    }

    Set<Integer> jours() {
        Set<Integer> j = new TreeSet<>();
        jours.chars().forEach(c -> j.add(c - '0'));
        return j;
    }

    boolean existeLe(int jour) {
        return jours.indexOf((char) ('0' + jour)) >= 0;
    }

    int minutes() {
        return (int) Duration.between(heureDebut, heureFin).toMinutes();
    }

    UUID getAnneeId() {
        return anneeId;
    }

    LocalTime getHeureDebut() {
        return heureDebut;
    }

    LocalTime getHeureFin() {
        return heureFin;
    }
}
