package bf.edutech.plateforme.socle.securite;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;

/**
 * Portée de l'administration de la plateforme : le super administrateur voit tout ; un
 * administrateur pays (v0.36) ne voit que son pays, désigné dans son jeton d'accès.
 */
public final class Portee {

    /** Revendication du jeton : pays administré. */
    public static final String CLAIM_PAYS = "pays_id";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN_PAYS = "ADMIN_PAYS";

    private Portee() {
    }

    public static boolean superAdmin() {
        return UtilisateurConnecte.aUnRole(SUPER_ADMIN);
    }

    /**
     * Pays auquel la requête est limitée : celui de l'administrateur pays ; vide pour le super
     * administrateur (tous les pays) et pour un traitement interne sans utilisateur (amorçage,
     * tâches, tests).
     */
    public static Optional<UUID> pays() {
        Authentication courante = SecurityContextHolder.getContext().getAuthentication();
        if (!(courante instanceof JwtAuthenticationToken) || superAdmin()) {
            return Optional.empty();
        }
        if (!UtilisateurConnecte.aUnRole(ADMIN_PAYS)) {
            throw new AccesRefuseException("Réservé à l'administration de la plateforme");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String pays = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString(CLAIM_PAYS) : null;
        if (pays == null) {
            throw new AccesRefuseException("Aucun pays administré");
        }
        return Optional.of(UUID.fromString(pays));
    }

    public static void exigerSuperAdmin() {
        if (!superAdmin()) {
            throw new AccesRefuseException("Réservé au super administrateur de la plateforme");
        }
    }
}
