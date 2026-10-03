package bf.edutech.plateforme.absences;

/** Réponse du serveur pour chaque appel d'un lot synchronisé. */
public enum StatutAccuse {
    /** Nouvel appel enregistré. */
    ENREGISTRE,
    /** Appel déjà reçu à l'identique (renvoi après une coupure réseau) : rien n'a changé. */
    DEJA_RECU,
    /** Appel déjà reçu, modifié par ce nouvel envoi. */
    MODIFIE,
    /** Refusé (voir le code et le message) : l'appareil peut retirer l'appel de sa file et alerter. */
    REFUSE
}
