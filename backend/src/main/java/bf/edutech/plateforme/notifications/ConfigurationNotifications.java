package bf.edutech.plateforme.notifications;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active les tâches planifiées (worker d'envoi). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ConfigurationNotifications {
}
