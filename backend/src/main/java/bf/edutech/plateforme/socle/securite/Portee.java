package bf.edutech.plateforme.socle.securite;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;

/**
 * Portée de l'administration de la plateforme : le super administrateur voit tout ; un
 * administrateur pays (v0.36) ne voit que son pays, désigné dans son jeton d'accès ; un compte de
 * direction (v0.37) ne voit que les nombres de sa direction.
 */
public final class Portee {

    /** Revendication du jeton : pays administré. */
    public static final String CLAIM_PAYS = "pays_id";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN_PAYS = "ADMIN_PAYS";
    /** Revendication du jeton : direction (régionale, provinciale…) d'un compte de direction (v0.37). */
    public static final String CLAIM_DIRECTION = "direction_id";
    public static final String DIRECTION = "DIRECTION";

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

    /**
     * Direction d'un compte de direction (v0.37) : il ne voit que les nombres de cette direction
     * et de ce qui en dépend. Vide pour tout autre compte.
     */
    public static Optional<UUID> direction() {
        if (!UtilisateurConnecte.aUnRole(DIRECTION)) {
            return Optional.empty();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String direction = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString(CLAIM_DIRECTION) : null;
        if (direction == null) {
            throw new AccesRefuseException("Aucune direction associée à ce compte");
        }
        return Optional.of(UUID.fromString(direction));
    }

    public static void exigerSuperAdmin() {
        if (!superAdmin()) {
            throw new AccesRefuseException("Réservé au super administrateur de la plateforme");
        }
    }
}
