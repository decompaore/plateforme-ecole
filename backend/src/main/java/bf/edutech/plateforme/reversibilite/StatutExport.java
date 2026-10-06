package bf.edutech.plateforme.reversibilite;

/** État d'un export ; EXPIRE et INTERROMPU sont déduits (jamais enregistrés). */
public enum StatutExport {
    EN_COURS,
    PRET,
    ECHEC,
    /** Archive effacée du serveur (durée de conservation dépassée ou export plus récent). */
    EXPIRE,
    /** Préparation arrêtée (redémarrage du serveur pendant l'export). */
    INTERROMPU
}
