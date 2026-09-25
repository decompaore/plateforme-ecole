package bf.edutech.plateforme.utilisateurs;

/**
 * Rôle d'un compte dans un établissement. Un même compte peut avoir des rôles
 * différents dans plusieurs établissements (ex. enseignant titulaire dans l'un,
 * vacataire dans un autre ; parent d'enfants scolarisés dans deux écoles).
 * <p>
 * Le super administrateur n'est pas un rôle d'établissement : c'est un
 * attribut du compte ({@link Utilisateur#isSuperAdmin()}).
 */
public enum Role {
    ADMIN_ECOLE,
    CENSEUR,
    SECRETARIAT,
    INTENDANT,
    SURVEILLANT,
    ENSEIGNANT,
    PARENT,
    ELEVE
}
