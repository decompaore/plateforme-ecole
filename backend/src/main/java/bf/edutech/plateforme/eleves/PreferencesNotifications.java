package bf.edutech.plateforme.eleves;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Comment un responsable est prévenu des absences et des retards (v0.50) :
 * <ul>
 * <li>l'établissement choisit le canal : {@code AUTO} (l'application, si le parent l'a activée sur
 * son téléphone, sinon SMS) ou {@code SMS} (toujours par SMS : parent sans smartphone) ;</li>
 * <li>le parent choisit s'il veut être prévenu des retards, et un message discret sur l'écran
 * verrouillé de son téléphone.</li>
 * </ul>
 */
@Service
public class PreferencesNotifications {

    public record PreferencesVue(String canal, boolean notifierRetards, boolean discret) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    PreferencesNotifications(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public void definirCanal(UUID responsableId, String canal) {
        UtilisateurConnecte.etablissementActif();
        if (!"AUTO".equals(canal) && !"SMS".equals(canal)) {
            throw new IllegalArgumentException("Canal attendu : AUTO ou SMS");
        }
        int n = jdbc.update("update responsable set canal_notification = ? where id = ?", canal, responsableId);
        if (n == 0) {
            throw new RessourceIntrouvableException("Responsable introuvable");
        }
        audit.enregistrer("CANAL_NOTIFICATION", responsableId + " : " + canal, null);
    }

    @Transactional(readOnly = true)
    public PreferencesVue mesPreferences() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select bool_or(canal_notification = 'SMS'), bool_and(notifier_retards), bool_or(notifications_discretes)
                from responsable where utilisateur_id = ?""",
                rs -> {
                    if (!rs.next() || rs.getObject(2) == null) {
                        throw new RegleMetierException("PARENT_INCONNU", "Aucun enfant rattaché à ce compte");
                    }
                    return new PreferencesVue(rs.getBoolean(1) ? "SMS" : "AUTO", rs.getBoolean(2), rs.getBoolean(3));
                }, UtilisateurConnecte.id());
    }

    @Transactional
    public PreferencesVue modifierMesPreferences(boolean notifierRetards, boolean discret) {
        UtilisateurConnecte.etablissementActif();
        jdbc.update("update responsable set notifier_retards = ?, notifications_discretes = ? where utilisateur_id = ?",
                notifierRetards, discret, UtilisateurConnecte.id());
        return mesPreferences();
    }
}
