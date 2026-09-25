package bf.edutech.plateforme.socle.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Établissement actif pour le traitement en cours.
 * <p>
 * Renseigné par {@link TenantFilter} à partir du jeton d'accès (jamais à partir
 * d'un en-tête ou du corps de la requête), puis transmis à PostgreSQL par
 * {@link TenantAwareJpaTransactionManager} au début de chaque transaction.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> COURANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Établissement actif, s'il y en a un. */
    public static Optional<UUID> courant() {
        return Optional.ofNullable(COURANT.get());
    }

    /**
     * Exécute une action pour un établissement donné (par exemple lors de la
     * création d'un établissement par le super administrateur). La transaction
     * doit être démarrée À L'INTÉRIEUR de l'action pour que la base reçoive
     * le bon établissement.
     */
    public static <T> T executerPour(UUID tenantId, Supplier<T> action) {
        UUID precedent = COURANT.get();
        COURANT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (precedent == null) {
                COURANT.remove();
            } else {
                COURANT.set(precedent);
            }
        }
    }

    static void definir(UUID tenantId) {
        COURANT.set(tenantId);
    }

    static void effacer() {
        COURANT.remove();
    }
}
