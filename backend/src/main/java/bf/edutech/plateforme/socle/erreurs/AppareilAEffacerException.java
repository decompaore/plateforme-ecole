package bf.edutech.plateforme.socle.erreurs;

/**
 * La session de cet appareil a été fermée à distance avec effacement (téléphone perdu ou
 * volé) : réponse 401 avec le code {@code APPAREIL_A_EFFACER}, sur lequel l'application
 * efface toutes ses données locales.
 */
public class AppareilAEffacerException extends AuthentificationException {

    private static final long serialVersionUID = 1L;

    public AppareilAEffacerException() {
        super("Cet appareil a été déconnecté à distance : ses données ont été effacées");
    }
}
