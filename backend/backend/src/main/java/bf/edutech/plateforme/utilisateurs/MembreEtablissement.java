package bf.edutech.plateforme.utilisateurs;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteUuid;

/**
 * Appartenance d'un compte à un établissement, avec un rôle.
 * Table cloisonnée : la Row-Level Security ne laisse voir que les
 * appartenances de l'établissement actif.
 */
@Entity
@Table(name = "membre_etablissement")
public class MembreEtablissement extends EntiteUuid {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "utilisateur_id", nullable = false, updatable = false)
    private UUID utilisateurId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, updatable = false)
    private Role role;

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe = Instant.now();

    protected MembreEtablissement() {
    }

    public MembreEtablissement(UUID tenantId, UUID utilisateurId, Role role) {
        this.tenantId = tenantId;
        this.utilisateurId = utilisateurId;
        this.role = role;
    }

    public void desactiver() {
        this.actif = false;
    }

    public void reactiver() {
        this.actif = true;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUtilisateurId() {
        return utilisateurId;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActif() {
        return actif;
    }
}
