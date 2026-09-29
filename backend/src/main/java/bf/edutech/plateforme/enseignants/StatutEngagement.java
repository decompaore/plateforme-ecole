package bf.edutech.plateforme.enseignants;

/**
 * Cycle de vie d'un engagement : INVITE (en attente de la réponse de l'enseignant)
 * → ACTIF → TERMINE ; ou INVITE → REFUSE.
 */
public enum StatutEngagement {
    INVITE,
    ACTIF,
    TERMINE,
    REFUSE
}
