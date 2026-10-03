package bf.edutech.plateforme.scolarite;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Filière, niveau ou classe visés par un frais (un seul des trois par ligne). */
@Entity
@Table(name = "frais_cible")
public class FraisCible extends EntiteCloisonnee {

    @Column(name = "frais_id", nullable = false, updatable = false)
    private UUID fraisId;

    @Column(name = "filiere_id", updatable = false)
    private UUID filiereId;

    @Column(name = "niveau", length = 30, updatable = false)
    private String niveau;

    @Column(name = "classe_id", updatable = false)
    private UUID classeId;

    protected FraisCible() {
    }

    FraisCible(UUID fraisId, UUID filiereId, String niveau, UUID classeId) {
        this.fraisId = fraisId;
        this.filiereId = filiereId;
        this.niveau = niveau;
        this.classeId = classeId;
    }

    UUID getFraisId() {
        return fraisId;
    }

    UUID getFiliereId() {
        return filiereId;
    }

    String getNiveau() {
        return niveau;
    }

    UUID getClasseId() {
        return classeId;
    }
}
