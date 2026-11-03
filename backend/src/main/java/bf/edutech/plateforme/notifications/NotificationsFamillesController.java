package bf.edutech.plateforme.notifications;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.notifications.AbonnementsPush.AppareilVue;
import bf.edutech.plateforme.notifications.AbonnementsPush.ConfigurationPush;
import bf.edutech.plateforme.notifications.AbonnementsPush.DonneesAbonnement;
import bf.edutech.plateforme.notifications.NotificationsFamilles.CentreNotifications;
import bf.edutech.plateforme.notifications.NotificationsFamilles.ReglageNotifications;

/** Notifications aux parents dans l'application (v0.50) : appareils, centre de notifications, réglages. */
@RestController
public class NotificationsFamillesController {

    public record DemandeReglage(int delaiCorrectionMinutes, boolean smsRetards) {
    }

    public record DemandeRetrait(String endpoint) {
    }

    public record DemandeLecture(List<UUID> ids) {
    }

    private final NotificationsFamilles familles;
    private final AbonnementsPush abonnements;

    NotificationsFamillesController(NotificationsFamilles familles, AbonnementsPush abonnements) {
        this.familles = familles;
        this.abonnements = abonnements;
    }

    // ------------------------------------------------------------------ appareils (tout utilisateur connecté)

    @GetMapping("/api/v1/push/configuration")
    public ConfigurationPush configuration() {
        return abonnements.configuration();
    }

    @GetMapping("/api/v1/push/abonnements")
    @PreAuthorize("hasRole('PARENT')")
    public List<AppareilVue> mesAppareils() {
        return abonnements.mesAppareils();
    }

    @PostMapping("/api/v1/push/abonnements")
    @PreAuthorize("hasRole('PARENT')")
    public List<AppareilVue> abonner(@RequestBody DonneesAbonnement d) {
        return abonnements.abonner(d);
    }

    @PostMapping("/api/v1/push/abonnements/retrait")
    @PreAuthorize("hasRole('PARENT')")
    public List<AppareilVue> desabonner(@RequestBody DemandeRetrait d) {
        return abonnements.desabonner(d.endpoint());
    }

    @PostMapping("/api/v1/push/essai")
    @PreAuthorize("hasRole('PARENT')")
    public Map<String, Integer> essai() {
        return Map.of("appareils", abonnements.essai());
    }

    // ------------------------------------------------------------------ centre de notifications du parent

    @GetMapping("/api/v1/espace-parent/notifications")
    @PreAuthorize("hasRole('PARENT')")
    public CentreNotifications centre(@RequestParam(defaultValue = "50") int limite) {
        return familles.centre(limite);
    }

    @PostMapping("/api/v1/espace-parent/notifications/lues")
    @PreAuthorize("hasRole('PARENT')")
    public CentreNotifications marquerLues(@RequestBody(required = false) DemandeLecture d) {
        return familles.marquerLues(d == null ? List.of() : d.ids());
    }

    // ------------------------------------------------------------------ réglages de l'établissement

    @GetMapping("/api/v1/notifications/reglages")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CENSEUR','SURVEILLANT','SECRETARIAT')")
    public ReglageNotifications reglages() {
        return familles.reglages();
    }

    @PutMapping("/api/v1/notifications/reglages")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','SURVEILLANT')")
    public ReglageNotifications modifierReglages(@RequestBody DemandeReglage d) {
        return familles.modifierReglages(d.delaiCorrectionMinutes(), d.smsRetards());
    }
}
