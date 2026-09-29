package bf.edutech.plateforme.socle.erreurs;

/** Un service externe (agrégateur Mobile Money…) ne répond pas : réponse 503, l'utilisateur peut réessayer. */
public class ServiceIndisponibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ServiceIndisponibleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
