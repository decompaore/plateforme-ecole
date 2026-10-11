package bf.edutech.plateforme.utilisateurs;

/**
 * Espace d'administration d'un compte (v0.41) : super administration de la plateforme, administration
 * d'un pays ou compte de direction. Une même personne peut aussi être parent d'élève : à la connexion,
 * elle choisit entre cet espace et ses établissements (où elle n'a jamais que le rôle PARENT).
 *
 * @param type    PLATEFORME, PAYS ou DIRECTION
 * @param libelle « Administration de la plateforme », « Administration du pays : Burkina Faso »,
 *                « DR du Centre »…
 */
public record EspaceAdministration(String type, String libelle) {
}
