/**
 * Notifications : file d'envoi (outbox) des SMS, alimentée dans la même
 * transaction que l'événement métier, et worker d'envoi avec reprises
 * automatiques et coupe-circuit. Ne dépend d'aucun module métier.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notifications")
package bf.edutech.plateforme.notifications;
