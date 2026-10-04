package bf.edutech.plateforme.ateliers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Commande passée auprès d'un fournisseur par la direction régionale ou par l'établissement. */
@Entity
@Table(name = "commande")
public class Commande extends EntiteCloisonnee {

    @Column(name = "campagne_id", nullable = false, updatable = false)
    private UUID campagneId;

    @Column(name = "reference", nullable = false, length = 60)
    private String reference;

    @Column(name = "fournisseur", nullable = false, length = 150)
    private String fournisseur;

    @Enumerated(EnumType.STRING)
    @Column(name = "passee_par", nullable = false, length = 20)
    private PasseePar passeePar;

    @Column(name = "date_commande", nullable = false)
    private LocalDate dateCommande;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 22)
    private StatutCommande statut = StatutCommande.EN_COURS;

    @Column(name = "observations", length = 500)
    private String observations;

    @Column(name = "motif_annulation", length = 200)
    private String motifAnnulation;

    @Column(name = "cree_par", updatable = false)
    private UUID creePar;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe;

    protected Commande() {
    }

    Commande(UUID campagneId, UUID par, Instant maintenant) {
        this.campagneId = campagneId;
        this.creePar = par;
        this.creeLe = maintenant;
    }

    void definir(String reference, String fournisseur, PasseePar passeePar, LocalDate dateCommande, String observations) {
        this.reference = reference;
        this.fournisseur = fournisseur;
        this.passeePar = passeePar;
        this.dateCommande = dateCommande;
        this.observations = observations;
    }

    void changerStatut(StatutCommande statut) {
        this.statut = statut;
    }

    void annuler(String motif) {
        this.statut = StatutCommande.ANNULEE;
        this.motifAnnulation = motif;
    }

    public UUID getCampagneId() {
        return campagneId;
    }

    public String getReference() {
        return reference;
    }

    public String getFournisseur() {
        return fournisseur;
    }

    public PasseePar getPasseePar() {
        return passeePar;
    }

    public LocalDate getDateCommande() {
        return dateCommande;
    }

    public StatutCommande getStatut() {
        return statut;
    }

    public String getObservations() {
        return observations;
    }

    public String getMotifAnnulation() {
        return motifAnnulation;
    }

    public Instant getCreeLe() {
        return creeLe;
    }
}
