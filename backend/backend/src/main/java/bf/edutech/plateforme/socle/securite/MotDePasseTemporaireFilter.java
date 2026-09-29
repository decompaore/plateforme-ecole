package bf.edutech.plateforme.socle.securite;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tant que le mot de passe temporaire n'a pas été changé, seules la
 * consultation du profil, le changement de mot de passe et les opérations
 * d'authentification sont autorisées.
 */
public class MotDePasseTemporaireFilter extends OncePerRequestFilter {

    private static final Set<String> CHEMINS_AUTORISES = Set.of(
            "/api/v1/moi", "/api/v1/moi/mot-de-passe", "/api/v1/moi/etablissements");

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain chaine)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt
                && Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean(SecuriteConfig.CLAIM_MOT_DE_PASSE_A_CHANGER))
                && !cheminAutorise(requete.getRequestURI())) {
            reponse.setStatus(HttpStatus.FORBIDDEN.value());
            reponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            reponse.setCharacterEncoding("UTF-8");
            reponse.getWriter().write("""
                    {"title":"Mot de passe à changer","status":403,\
                    "detail":"Vous devez changer votre mot de passe temporaire avant de continuer.",\
                    "code":"MOT_DE_PASSE_A_CHANGER"}""");
            return;
        }
        chaine.doFilter(requete, reponse);
    }

    private static boolean cheminAutorise(String uri) {
        return uri.startsWith("/api/v1/auth/") || CHEMINS_AUTORISES.contains(uri);
    }
}
