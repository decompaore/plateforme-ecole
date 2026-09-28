package bf.edutech.plateforme.utilisateurs;

import java.time.Duration;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.socle.securite.SecuriteProperties;
import bf.edutech.plateforme.utilisateurs.AuthDtos.DemandeChoixEtablissement;
import bf.edutech.plateforme.utilisateurs.AuthDtos.DemandeConnexion;
import bf.edutech.plateforme.utilisateurs.AuthDtos.ReponseConnexion;

/**
 * Points d'accès d'authentification.
 * <p>
 * Le jeton de rafraîchissement circule uniquement dans un cookie HttpOnly,
 * SameSite=Strict, limité au chemin /api/v1/auth : il n'est jamais lisible
 * par le code JavaScript de l'application.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String COOKIE_RAFRAICHISSEMENT = "plateforme_rt";

    private final AuthService service;
    private final SecuriteProperties securite;

    AuthController(AuthService service, SecuriteProperties securite) {
        this.service = service;
        this.securite = securite;
    }

    @PostMapping("/connexion")
    public ResponseEntity<ReponseConnexion> connexion(@Valid @RequestBody DemandeConnexion demande) {
        return reponse(service.connexion(demande.telephone(), demande.motDePasse()));
    }

    @PostMapping("/etablissement")
    public ResponseEntity<ReponseConnexion> choisirEtablissement(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DemandeChoixEtablissement demande,
            @CookieValue(name = COOKIE_RAFRAICHISSEMENT, required = false) String ancien) {
        return reponse(service.choisirEtablissement(java.util.UUID.fromString(jwt.getSubject()),
                demande.etablissementId(), ancien));
    }

    @PostMapping("/rafraichir")
    public ResponseEntity<ReponseConnexion> rafraichir(
            @CookieValue(name = COOKIE_RAFRAICHISSEMENT, required = false) String valeur) {
        return reponse(service.rafraichir(valeur));
    }

    @PostMapping("/deconnexion")
    public ResponseEntity<Void> deconnexion(
            @CookieValue(name = COOKIE_RAFRAICHISSEMENT, required = false) String valeur) {
        service.deconnexion(valeur);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<ReponseConnexion> reponse(AuthService.ResultatConnexion resultat) {
        ResponseEntity.BodyBuilder constructeur = ResponseEntity.ok();
        if (resultat.jetonRafraichissement() != null) {
            constructeur.header(HttpHeaders.SET_COOKIE,
                    cookie(resultat.jetonRafraichissement(), securite.dureeJetonRafraichissement()).toString());
        }
        return constructeur.body(ReponseConnexion.depuis(resultat));
    }

    private ResponseCookie cookie(String valeur, Duration duree) {
        return ResponseCookie.from(COOKIE_RAFRAICHISSEMENT, valeur)
                .httpOnly(true)
                .secure(securite.cookieSecurise())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(duree)
                .build();
    }
}
