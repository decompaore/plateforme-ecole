package bf.edutech.plateforme.plateforme;

/** Statut commercial d'un établissement sur la plateforme. */
public enum StatutTenant {
    /** Accès normal. */
    ACTIF,
    /** Accès bloqué (impayé, demande de l'école) ; données conservées. */
    SUSPENDU,
    /** Contrat terminé ; données conservées le temps de la réversibilité. */
    RESILIE
}
