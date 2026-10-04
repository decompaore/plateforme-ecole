package bf.edutech.plateforme.ateliers;

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

/** Réception d'une commande par le chef des travaux, puis répartition entre les ateliers. */
@Entity
@Table(name = "livraison")
public class Livraison extends EntiteCloisonnee {

    @Column(name = "commande_id", nullable = false, updatable = false)
    private UUID commandeId;

    @Column(name = "date_reception", nullable = false)
    private LocalDate dateReception;

    @Column(name = "bon_livraison", length = 60)
    private String bonLivraison;

    @Column(name = "observations", length = 500)
    private String observations;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutLivraison statut = StatutLivraison.A_REPARTIR;

    @Column(name = "recue_par", updatable = false)
    private UUID recuePar;

    @Column(name = "recue_le", nullable = false, updatable = false)
    private Instant recueLe;

    @Column(name = "repartie_par")
    private UUID repartiePar;

    @Column(name = "repartie_le")
    private Instant repartieLe;

    protected Livraison() {
    }

    Livraison(UUID commandeId, LocalDate dateReception, String bonLivraison, String observations, UUID par,
            Instant maintenant) {
        this.commandeId = commandeId;
        this.dateReception = dateReception;
        this.bonLivraison = bonLivraison;
        this.observations = observations;
        this.recuePar = par;
        this.recueLe = maintenant;
    }

    void exigerARepartir() {
        if (statut != StatutLivraison.A_REPARTIR) {
            throw new RegleMetierException("LIVRAISON_REPARTIE", "Cette livraison est déjà répartie entre les ateliers");
        }
    }

    void repartir(UUID par, Instant maintenant) {
        exigerARepartir();
        statut = StatutLivraison.REPARTIE;
        repartiePar = par;
        repartieLe = maintenant;
    }

    public UUID getCommandeId() {
        return commandeId;
    }

    public LocalDate getDateReception() {
        return dateReception;
    }

    public String getBonLivraison() {
        return bonLivraison;
    }

    public String getObservations() {
        return observations;
    }

    public StatutLivraison getStatut() {
        return statut;
    }

    public UUID getRecuePar() {
        return recuePar;
    }

    public Instant getRecueLe() {
        return recueLe;
    }

    public UUID getRepartiePar() {
        return repartiePar;
    }

    public Instant getRepartieLe() {
        return repartieLe;
    }
}
