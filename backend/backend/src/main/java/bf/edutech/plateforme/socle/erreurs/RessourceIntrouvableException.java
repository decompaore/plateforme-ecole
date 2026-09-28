package bf.edutech.plateforme.socle.erreurs;

/** Ressource absente (ou invisible pour l'établissement actif) : réponse 404. */
public class RessourceIntrouvableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RessourceIntrouvableException(String message) {
        super(message);
    }
}
