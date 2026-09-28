package bf.edutech.plateforme.enseignants;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.enseignants.Vues.InvitationVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Invitations reçues par l'enseignant connecté, quel que soit l'établissement
 * de sa session (ou sans établissement s'il n'en a encore aucun).
 * <p>
 * La réponse s'exécute dans l'établissement qui a invité : la transaction est
 * ouverte APRÈS avoir fixé cet établissement, pour que la Row-Level Security
 * autorise la mise à jour de l'engagement.
 */
@Service
public class EspaceEnseignantService {

    /** Réponse à une invitation ; l'enseignant peut ensuite choisir cet établissement. */
    public record ReponseInvitation(UUID etablissementId, String etablissementNom, StatutEngagement statut) {
    }

    private final IdentitesPlateforme plateforme;
    private final EngagementRepository engagements;
    private final EnseignantRepository enseignants;
    private final MembresService membres;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    EspaceEnseignantService(IdentitesPlateforme plateforme, EngagementRepository engagements,
            EnseignantRepository enseignants, MembresService membres, AuditService audit,
            PlatformTransactionManager gestionnaireTransactions) {
        this.plateforme = plateforme;
        this.engagements = engagements;
        this.enseignants = enseignants;
        this.membres = membres;
        this.audit = audit;
        this.transaction = new TransactionTemplate(gestionnaireTransactions);
    }

    public List<InvitationVue> mesInvitations() {
        return plateforme.invitations(UtilisateurConnecte.id());
    }

    /** Accepte ou refuse une invitation adressée au compte connecté. */
    public ReponseInvitation repondre(UUID engagementId, boolean accepter) {
        UUID moi = UtilisateurConnecte.id();
        InvitationVue invitation = plateforme.invitations(moi).stream()
                .filter(i -> i.engagementId().equals(engagementId))
                .findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Invitation introuvable ou déjà traitée"));
        return TenantContext.executerPour(invitation.etablissementId(), () -> transaction.execute(etat -> {
            Engagement engagement = engagements.findById(engagementId)
                    .orElseThrow(() -> new RessourceIntrouvableException("Invitation introuvable"));
            Enseignant enseignant = enseignants.findById(engagement.getEnseignantId()).orElseThrow();
            if (!enseignant.getUtilisateurId().equals(moi)) {
                throw new AccesRefuseException("Cette invitation ne vous est pas adressée");
            }
            if (accepter) {
                engagement.accepter();
                membres.garantirRole(enseignant.getTelephone(), enseignant.getNom(), enseignant.getPrenoms(),
                        Role.ENSEIGNANT);
            } else {
                engagement.refuser();
            }
            audit.enregistrer(accepter ? "INVITATION_ACCEPTEE" : "INVITATION_REFUSEE", engagementId.toString(),
                    Map.of("type", engagement.getType().name()));
            return new ReponseInvitation(invitation.etablissementId(), invitation.etablissementNom(),
                    engagement.getStatut());
        }));
    }
}
