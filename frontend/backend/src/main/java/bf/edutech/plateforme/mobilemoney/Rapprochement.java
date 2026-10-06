package bf.edutech.plateforme.mobilemoney;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Comparaison des paiements d'une journée avec le relevé de l'agrégateur. */
@Entity
@Table(name = "rapprochement")
public class Rapprochement extends EntiteCloisonnee {

    @Column(name = "date_releve", nullable = false, updatable = false)
    private LocalDate dateReleve;

    @Column(name = "lignes", nullable = false)
    private int lignes;

    @Column(name = "ecarts", nullable = false)
    private int ecarts;

    @Column(name = "statut", nullable = false, length = 8)
    private String statut;

    @Column(name = "message", length = 300)
    private String message;

    @Column(name = "execute_le", nullable = false)
    private Instant executeLe;

    protected Rapprochement() {
    }

    Rapprochement(LocalDate dateReleve, int lignes, int ecarts, String statut, String message, Instant executeLe) {
        this.dateReleve = dateReleve;
        this.lignes = lignes;
        this.ecarts = ecarts;
        this.statut = statut;
        this.message = message == null ? null : message.length() > 300 ? message.substring(0, 300) : message;
        this.executeLe = executeLe;
    }

    LocalDate getDateReleve() {
        return dateReleve;
    }

    int getLignes() {
        return lignes;
    }

    int getEcarts() {
        return ecarts;
    }

    String getStatut() {
        return statut;
    }

    String getMessage() {
        return message;
    }

    Instant getExecuteLe() {
        return executeLe;
    }
}
