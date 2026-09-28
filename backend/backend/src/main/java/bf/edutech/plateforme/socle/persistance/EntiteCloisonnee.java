package bf.edutech.plateforme.socle.persistance;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Entité appartenant à un établissement.
 * <p>
 * L'établissement est renseigné automatiquement à la création, à partir de
 * l'établissement actif ; il ne peut jamais être modifié. La Row-Level
 * Security de PostgreSQL refuse de toute façon une valeur différente.
 */
@MappedSuperclass
public abstract class EntiteCloisonnee extends EntiteUuid {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @PrePersist
    void affecterEtablissement() {
        if (tenantId == null) {
            tenantId = TenantContext.courant()
                    .orElseThrow(() -> new IllegalStateException("Aucun établissement actif pour cette création"));
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
