/**
 * Socle technique partagé : cloisonnement par établissement (tenant),
 * sécurité, audit, gestion des erreurs, persistance.
 * <p>
 * Module « ouvert » : les autres modules peuvent utiliser ses sous-paquetages.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Socle",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package bf.edutech.plateforme.socle;
