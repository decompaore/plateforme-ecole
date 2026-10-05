/**
 * Emplois du temps : grille horaire de l'année, séances de chaque classe, contrôle des
 * conflits (classe, enseignant, y compris dans ses autres établissements, atelier), couverture
 * des volumes horaires, génération automatique et publication aux enseignants. Les matières
 * générales sont placées par le censeur, les matières techniques et pratiques par le chef des
 * travaux (le censeur le remplace s'il n'y en a pas).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Emplois du temps")
package bf.edutech.plateforme.emploidutemps;
