package bf.edutech.plateforme.socle.tenant;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gestionnaire de transactions qui transmet l'établissement actif à PostgreSQL.
 * <p>
 * Au début de chaque transaction, exécute
 * {@code set_config('app.tenant_id', <tenant>, true)} : la valeur est locale à
 * la transaction et disparaît au COMMIT ou au ROLLBACK, ce qui évite toute
 * fuite d'une requête à l'autre via le pool de connexions. Les politiques de
 * Row-Level Security filtrent ensuite toutes les lignes.
 * <p>
 * Sans établissement actif, aucune valeur n'est positionnée : les tables
 * cloisonnées ne renvoient alors aucune ligne (comportement sûr par défaut).
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    private static final long serialVersionUID = 1L;

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        TenantContext.courant().ifPresent(tenantId -> {
            EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager
                    .getResource(obtainEntityManagerFactory());
            if (holder == null) {
                throw new IllegalStateException("Aucun EntityManager lié à la transaction");
            }
            holder.getEntityManager()
                    .createNativeQuery("select set_config('app.tenant_id', :tenant, true)")
                    .setParameter("tenant", tenantId.toString())
                    .getSingleResult();
        });
    }
}
