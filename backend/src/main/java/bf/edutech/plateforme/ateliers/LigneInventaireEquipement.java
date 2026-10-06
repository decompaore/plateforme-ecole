package bf.edutech.plateforme.ateliers;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Ligne d'inventaire d'un équipement : état enregistré à l'ouverture et état constaté. */
@Entity
@Table(name = "ligne_inventaire_equipement")
public class LigneInventaireEquipement extends EntiteCloisonnee {

    @Column(name = "inventaire_id", nullable = false, updatable = false)
    private UUID inventaireId;

    @Column(name = "equipement_id", nullable = false, updatable = false)
    private UUID equipementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_theorique", nullable = false, length = 10)
    private EtatEquipement etatTheorique;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_constate", length = 10)
    private EtatEquipement etatConstate;

    @Column(name = "observation", length = 300)
    private String observation;

    protected LigneInventaireEquipement() {
    }

    LigneInventaireEquipement(UUID inventaireId, UUID equipementId, EtatEquipement etatTheorique) {
        this.inventaireId = inventaireId;
        this.equipementId = equipementId;
        this.etatTheorique = etatTheorique;
    }

    void constater(EtatEquipement etat, String observation) {
        this.etatConstate = etat;
        this.observation = observation;
    }

    public UUID getInventaireId() {
        return inventaireId;
    }

    public UUID getEquipementId() {
        return equipementId;
    }

    public EtatEquipement getEtatTheorique() {
        return etatTheorique;
    }

    public EtatEquipement getEtatConstate() {
        return etatConstate;
    }

    public String getObservation() {
        return observation;
    }
}
