package bf.edutech.plateforme.socle.erreurs;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Transforme les exceptions en réponses « Problem Details » (RFC 9457).
 * Aucun détail technique (pile d'appels, requête SQL, identifiants d'autres
 * établissements) n'est renvoyé au client.
 */
@RestControllerAdvice
public class GestionnaireErreurs extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GestionnaireErreurs.class);

    @ExceptionHandler(RessourceIntrouvableException.class)
    ProblemDetail introuvable(RessourceIntrouvableException ex) {
        return probleme(HttpStatus.NOT_FOUND, "Ressource introuvable", ex.getMessage());
    }

    @ExceptionHandler(RegleMetierException.class)
    ProblemDetail regleMetier(RegleMetierException ex) {
        ProblemDetail pd = probleme(HttpStatus.CONFLICT, "Règle de gestion non respectée", ex.getMessage());
        pd.setProperty("code", ex.getCode());
        return pd;
    }

    @ExceptionHandler(ServiceIndisponibleException.class)
    ProblemDetail serviceIndisponible(ServiceIndisponibleException ex) {
        ProblemDetail pd = probleme(HttpStatus.SERVICE_UNAVAILABLE, "Service momentanément indisponible",
                ex.getMessage());
        pd.setProperty("code", ex.getCode());
        return pd;
    }

    @ExceptionHandler(AppareilAEffacerException.class)
    ProblemDetail appareilAEffacer(AppareilAEffacerException ex) {
        ProblemDetail pd = probleme(HttpStatus.UNAUTHORIZED, "Appareil déconnecté à distance", ex.getMessage());
        pd.setProperty("code", "APPAREIL_A_EFFACER");
        return pd;
    }

    @ExceptionHandler(AuthentificationException.class)
    ProblemDetail authentification(AuthentificationException ex) {
        return probleme(HttpStatus.UNAUTHORIZED, "Authentification refusée", ex.getMessage());
    }

    @ExceptionHandler({ AccesRefuseException.class, AccessDeniedException.class })
    ProblemDetail accesRefuse(RuntimeException ex) {
        return probleme(HttpStatus.FORBIDDEN, "Accès refusé", "Vous n'avez pas les droits nécessaires pour cette action");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail argumentInvalide(IllegalArgumentException ex) {
        return probleme(HttpStatus.BAD_REQUEST, "Requête invalide", ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflitDonnees(DataIntegrityViolationException ex) {
        LOG.warn("Violation de contrainte : {}", ex.getMostSpecificCause().getMessage());
        return probleme(HttpStatus.CONFLICT, "Conflit", "L'opération entre en conflit avec des données existantes");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail erreurInattendue(Exception ex) {
        LOG.error("Erreur inattendue", ex);
        return probleme(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne",
                "Une erreur inattendue s'est produite. Elle a été enregistrée.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> erreurs = new LinkedHashMap<>();
        for (FieldError erreur : ex.getBindingResult().getFieldErrors()) {
            erreurs.putIfAbsent(erreur.getField(), erreur.getDefaultMessage());
        }
        ProblemDetail pd = probleme(HttpStatus.BAD_REQUEST, "Données invalides", "Certains champs sont invalides");
        pd.setProperty("erreurs", erreurs);
        return ResponseEntity.badRequest().body(pd);
    }

    private static ProblemDetail probleme(HttpStatus statut, String titre, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(statut, detail);
        pd.setTitle(titre);
        return pd;
    }
}
