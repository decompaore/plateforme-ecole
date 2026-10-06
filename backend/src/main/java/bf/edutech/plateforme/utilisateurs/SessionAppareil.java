package bf.edutech.plateforme.utilisateurs;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteUuid;

/** Un appareil connecté : une famille de jetons de rafraîchissement. */
@Entity
@Table(name = "session_appareil")
public class SessionAppareil extends EntiteUuid {

    @Column(name = "utilisateur_id", nullable = false, updatable = false)
    private UUID utilisateurId;

    @Column(name = "famille", nullable = false, unique = true)
    private UUID famille;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "appareil", nullable = false, length = 120)
    private String appareil;

    @Column(name = "ouverte_le", nullable = false, updatable = false)
    private Instant ouverteLe;

    @Column(name = "dernier_usage", nullable = false)
    private Instant dernierUsage;

    @Column(name = "fermee_le")
    private Instant fermeeLe;

    @Column(name = "motif_fermeture", length = 20)
    private String motifFermeture;

    @Column(name = "effacement_demande", nullable = false)
    private boolean effacementDemande;

    @Column(name = "fermee_par")
    private UUID fermeePar;

    protected SessionAppareil() {
    }

    SessionAppareil(UUID utilisateurId, UUID famille, UUID tenantId, String appareil, Instant maintenant) {
        this.utilisateurId = utilisateurId;
        this.famille = famille;
        this.tenantId = tenantId;
        this.appareil = appareil;
        this.ouverteLe = maintenant;
        this.dernierUsage = maintenant;
    }

    void utiliser(Instant maintenant) {
        this.dernierUsage = maintenant;
    }

    /** Changement d'établissement : nouvelle famille de jetons, même appareil. */
    void changerFamille(UUID famille, UUID tenantId, Instant maintenant) {
        this.famille = famille;
        this.tenantId = tenantId;
        this.dernierUsage = maintenant;
    }

    void fermer(String motif, boolean effacer, UUID par, Instant maintenant) {
        if (fermeeLe == null) {
            fermeeLe = maintenant;
            motifFermeture = motif;
            fermeePar = par;
        }
        if (effacer) {
            effacementDemande = true;
        }
    }

    boolean estFermee() {
        return fermeeLe != null;
    }

    UUID getUtilisateurId() {
        return utilisateurId;
    }

    UUID getFamille() {
        return famille;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getAppareil() {
        return appareil;
    }

    Instant getOuverteLe() {
        return ouverteLe;
    }

    Instant getDernierUsage() {
        return dernierUsage;
    }

    boolean isEffacementDemande() {
        return effacementDemande;
    }
}
