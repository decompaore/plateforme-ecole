package bf.edutech.plateforme.socle.audit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.socle.persistance.PageResultat;

/** Consultation du journal d'audit de l'établissement actif (administrateur d'école). */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('ADMIN_ECOLE')")
public class AuditController {

    private final JournalAuditRepository depot;

    public AuditController(JournalAuditRepository depot) {
        this.depot = depot;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResultat<EntreeAudit> lister(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int taille) {
        int tailleBornee = Math.min(Math.max(taille, 1), 200);
        return PageResultat.depuis(
                depot.findAllByOrderByHorodatageDesc(PageRequest.of(Math.max(page, 0), tailleBornee)),
                EntreeAudit::depuis);
    }

    /** Vue exposée : pas d'adresse IP côté établissement. */
    public record EntreeAudit(UUID id, Instant horodatage, UUID utilisateurId, String action, String cible,
            String details) {

        static EntreeAudit depuis(JournalAudit j) {
            return new EntreeAudit(j.getId(), j.getHorodatage(), j.getUtilisateurId(), j.getAction(), j.getCible(),
                    j.getDetails());
        }
    }
}
