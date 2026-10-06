package bf.edutech.plateforme.socle.modules;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Ferme les chemins de l'API des modules désactivés pour l'établissement de la requête
 * (403, code {@code MODULE_DESACTIVE}). Les appels sans établissement (connexion, super
 * administrateur, notifications des opérateurs) ne sont pas concernés.
 */
public class ModulesFilter extends OncePerRequestFilter {

    private final ModulesEtablissement modules;

    public ModulesFilter(ModulesEtablissement modules) {
        this.modules = modules;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain chaine)
            throws ServletException, IOException {
        Optional<UUID> tenant = TenantContext.courant();
        if (tenant.isPresent()) {
            Module ferme = modules.moduleFerme(tenant.get(), requete.getRequestURI());
            if (ferme != null) {
                reponse.setStatus(HttpStatus.FORBIDDEN.value());
                reponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                reponse.setCharacterEncoding("UTF-8");
                reponse.getWriter().write("""
                        {"title":"Module non activé","status":403,\
                        "detail":"Le module « %s » n'est pas activé pour cet établissement.",\
                        "code":"MODULE_DESACTIVE","module":"%s"}""".formatted(ferme.libelle(), ferme.name()));
                return;
            }
        }
        chaine.doFilter(requete, reponse);
    }
}
