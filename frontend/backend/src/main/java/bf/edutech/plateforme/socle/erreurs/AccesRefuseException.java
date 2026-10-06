package bf.edutech.plateforme.socle.erreurs;

/** Action interdite pour l'utilisateur connecté : réponse 403. */
public class AccesRefuseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccesRefuseException(String message) {
        super(message);
    }
}
