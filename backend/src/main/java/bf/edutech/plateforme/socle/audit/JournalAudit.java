package bf.edutech.plateforme.socle.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteUuid;

/** Entrée du journal d'audit : qui a fait quoi, quand, sur quoi. Jamais modifiée. */
@Entity
@Table(name = "journal_audit")
public class JournalAudit extends EntiteUuid {

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "utilisateur_id", updatable = false)
    private UUID utilisateurId;

    @Column(name = "action", nullable = false, length = 60, updatable = false)
    private String action;

    @Column(name = "cible", length = 120, updatable = false)
    private String cible;

    @Column(name = "details", updatable = false)
    private String details;

    @Column(name = "adresse_ip", length = 45, updatable = false)
    private String adresseIp;

    @Column(name = "horodatage", nullable = false, updatable = false)
    private Instant horodatage;

    protected JournalAudit() {
    }

    JournalAudit(UUID tenantId, UUID utilisateurId, String action, String cible, String details,
            String adresseIp, Instant horodatage) {
        this.tenantId = tenantId;
        this.utilisateurId = utilisateurId;
        this.action = action;
        this.cible = cible;
        this.details = details;
        this.adresseIp = adresseIp;
        this.horodatage = horodatage;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUtilisateurId() {
        return utilisateurId;
    }

    public String getAction() {
        return action;
    }

    public String getCible() {
        return cible;
    }

    public String getDetails() {
        return details;
    }

    public String getAdresseIp() {
        return adresseIp;
    }

    public Instant getHorodatage() {
        return horodatage;
    }
}
