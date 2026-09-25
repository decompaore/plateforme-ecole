package bf.edutech.plateforme.socle.audit;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Enregistre les actions sensibles dans le journal d'audit.
 * <p>
 * L'établissement et l'utilisateur sont pris dans le contexte de la requête ;
 * aucune donnée personnelle sensible (mot de passe, numéro complet) ne doit
 * figurer dans les détails.
 */
@Service
public class AuditService {

    private final JournalAuditRepository depot;
    private final Clock horloge;

    public AuditService(JournalAuditRepository depot, Clock horloge) {
        this.depot = depot;
        this.horloge = horloge;
    }

    @Transactional
    public void enregistrer(String action, String cible, Map<String, ?> details) {
        enregistrerPour(UtilisateurConnecte.idSiConnecte().orElse(null), action, cible, details);
    }

    /** Variante utilisée lorsque l'utilisateur n'est pas encore authentifié (connexion). */
    @Transactional
    public void enregistrerPour(UUID utilisateurId, String action, String cible, Map<String, ?> details) {
        depot.save(new JournalAudit(
                TenantContext.courant().orElse(null),
                utilisateurId,
                action,
                cible,
                formater(details),
                adresseIp(),
                horloge.instant()));
    }

    private static String formater(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        return details.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String adresseIp() {
        RequestAttributes attributs = RequestContextHolder.getRequestAttributes();
        if (attributs instanceof ServletRequestAttributes servlet) {
            HttpServletRequest requete = servlet.getRequest();
            return requete.getRemoteAddr();
        }
        return null;
    }
}
