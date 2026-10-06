package bf.edutech.plateforme.notifications;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.notifications.NotificationsService.NotificationVue;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;

/** Suivi des SMS envoyés par l'établissement. */
@RestController
@PreAuthorize("hasAnyRole('ADMIN_ECOLE','SURVEILLANT')")
public class NotificationsController {

    private final NotificationsService service;

    NotificationsController(NotificationsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/notifications")
    public List<NotificationVue> dernieres(@RequestParam(required = false) StatutNotification statut,
            @RequestParam(defaultValue = "100") int limite) {
        return service.dernieres(statut, limite);
    }
}
