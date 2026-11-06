package bf.edutech.plateforme.eleves;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.eleves.PreferencesNotifications.PreferencesVue;
import bf.edutech.plateforme.eleves.Vues.EnfantVue;

/** Espace parent : ce que voit le parent connecté. */
@RestController
@PreAuthorize("hasRole('PARENT')")
public class EspaceParentController {

    public record DemandePreferences(boolean notifierRetards, boolean discret) {
    }

    private final EspaceParentService service;
    private final PreferencesNotifications preferences;

    EspaceParentController(EspaceParentService service, PreferencesNotifications preferences) {
        this.service = service;
        this.preferences = preferences;
    }

    /** Préférences de notification du parent connecté (v0.50). */
    @GetMapping("/api/v1/espace-parent/preferences-notifications")
    public PreferencesVue mesPreferences() {
        return preferences.mesPreferences();
    }

    @PutMapping("/api/v1/espace-parent/preferences-notifications")
    public PreferencesVue modifierMesPreferences(@RequestBody DemandePreferences d) {
        return preferences.modifierMesPreferences(d.notifierRetards(), d.discret());
    }

    /** Enfants du parent connecté dans l'établissement choisi à la connexion. */
    @GetMapping("/api/v1/espace-parent/enfants")
    public List<EnfantVue> mesEnfants() {
        return service.mesEnfants();
    }
}
