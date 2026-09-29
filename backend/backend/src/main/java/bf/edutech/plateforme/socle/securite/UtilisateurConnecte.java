package bf.edutech.plateforme.socle.securite;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/** Accès pratique à l'utilisateur et à l'établissement de la requête en cours. */
public final class UtilisateurConnecte {

    private UtilisateurConnecte() {
    }

    /** Identifiant du compte connecté, s'il y en a un. */
    public static Optional<UUID> idSiConnecte() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.of(UUID.fromString(jwt.getToken().getSubject()));
        }
        return Optional.empty();
    }

    /** Identifiant du compte connecté ; erreur 403 s'il n'y en a pas. */
    public static UUID id() {
        return idSiConnecte().orElseThrow(() -> new AccesRefuseException("Authentification requise"));
    }

    /** Vrai si l'utilisateur de la requête a au moins l'un de ces rôles (sans le préfixe ROLE_). */
    public static boolean aUnRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority autorite : auth.getAuthorities()) {
            for (String role : roles) {
                if (("ROLE_" + role).equals(autorite.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Établissement actif ; erreur 403 s'il n'y en a pas. */
    public static UUID etablissementActif() {
        return TenantContext.courant()
                .orElseThrow(() -> new AccesRefuseException("Aucun établissement actif pour cette requête"));
    }
}
