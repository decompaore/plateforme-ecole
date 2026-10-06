package bf.edutech.plateforme.enseignants;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.enseignants.EspaceEnseignantService.ReponseInvitation;
import bf.edutech.plateforme.enseignants.Vues.FicheEnseignantVue;
import bf.edutech.plateforme.enseignants.Vues.InvitationVue;

/** Ce que voit l'enseignant connecté : ses invitations, ses classes et sa charge horaire. */
@RestController
public class EspaceEnseignantController {

    private final EspaceEnseignantService espace;
    private final EnseignantsService enseignants;

    EspaceEnseignantController(EspaceEnseignantService espace, EnseignantsService enseignants) {
        this.espace = espace;
        this.enseignants = enseignants;
    }

    /** Invitations en attente, tous établissements confondus (accessible depuis n'importe quelle session). */
    @GetMapping("/api/v1/moi/invitations")
    public List<InvitationVue> mesInvitations() {
        return espace.mesInvitations();
    }

    /** Après acceptation : choisir l'établissement avec POST /api/v1/auth/etablissement. */
    @PostMapping("/api/v1/moi/invitations/{engagementId}/acceptation")
    public ReponseInvitation accepter(@PathVariable UUID engagementId) {
        return espace.repondre(engagementId, true);
    }

    @PostMapping("/api/v1/moi/invitations/{engagementId}/refus")
    public ReponseInvitation refuser(@PathVariable UUID engagementId) {
        return espace.repondre(engagementId, false);
    }

    /** Mes matières et classes dans l'établissement de la session, et ma charge hebdomadaire. */
    @GetMapping("/api/v1/espace-enseignant/affectations")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public FicheEnseignantVue mesAffectations(@RequestParam(required = false) UUID anneeId) {
        return enseignants.maFiche(anneeId);
    }
}
