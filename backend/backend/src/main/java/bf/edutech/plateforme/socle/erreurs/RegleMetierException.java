package bf.edutech.plateforme.socle.erreurs;

/** Règle de gestion non respectée : réponse 409 avec un code stable. */
public class RegleMetierException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public RegleMetierException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
