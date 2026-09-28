package bf.edutech.plateforme.plateforme;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteUuid;

/** Établissement (tenant) : table de niveau plateforme, non cloisonnée. */
@Entity
@Table(name = "tenant")
public class Tenant extends EntiteUuid {

    @Column(name = "code", nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @Column(name = "nom", nullable = false, length = 200)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutTenant statut = StatutTenant.ACTIF;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe = Instant.now();

    protected Tenant() {
    }

    Tenant(UUID id, String code, String nom) {
        imposerId(id);
        this.code = code;
        this.nom = nom;
    }

    void changerStatut(StatutTenant nouveau) {
        this.statut = nouveau;
    }

    public String getCode() {
        return code;
    }

    public String getNom() {
        return nom;
    }

    public StatutTenant getStatut() {
        return statut;
    }

    public Instant getCreeLe() {
        return creeLe;
    }
}
