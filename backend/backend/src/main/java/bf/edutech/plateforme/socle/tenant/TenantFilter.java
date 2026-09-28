package bf.edutech.plateforme.socle.tenant;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixe l'établissement actif à partir du jeton d'accès validé.
 * <p>
 * Tout en-tête du type X-Tenant-Id envoyé par le client est ignoré. Si la
 * vérification du sous-domaine est activée, l'hôte appelé doit commencer par
 * le code de l'établissement du jeton (ex. lycee-a.plateforme.bf).
 * <p>
 * Ce filtre est déclaré dans la chaîne de sécurité (pas comme composant Spring)
 * pour ne pas être enregistré deux fois.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_TENANT_CODE = "tenant_code";

    private final boolean verifierSousDomaine;

    public TenantFilter(boolean verifierSousDomaine) {
        this.verifierSousDomaine = verifierSousDomaine;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain chaine)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                String tenant = jwt.getClaimAsString(CLAIM_TENANT_ID);
                if (tenant != null) {
                    if (verifierSousDomaine
                            && !sousDomaineCorrespond(requete.getServerName(), jwt.getClaimAsString(CLAIM_TENANT_CODE))) {
                        reponse.sendError(HttpStatus.FORBIDDEN.value(), "Établissement non autorisé sur ce domaine");
                        return;
                    }
                    TenantContext.definir(UUID.fromString(tenant));
                }
            }
            chaine.doFilter(requete, reponse);
        } finally {
            TenantContext.effacer();
        }
    }

    static boolean sousDomaineCorrespond(String hote, String codeTenant) {
        if (hote == null || codeTenant == null) {
            return false;
        }
        int point = hote.indexOf('.');
        String sousDomaine = point > 0 ? hote.substring(0, point) : hote;
        return sousDomaine.equalsIgnoreCase(codeTenant);
    }
}
