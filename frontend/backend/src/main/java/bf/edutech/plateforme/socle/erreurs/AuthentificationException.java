package bf.edutech.plateforme.socle.erreurs;

/** Échec d'authentification : réponse 401 avec un message volontairement neutre. */
public class AuthentificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthentificationException(String message) {
        super(message);
    }
}
