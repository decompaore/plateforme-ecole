package bf.edutech.plateforme.socle.config;

import java.time.Clock;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import bf.edutech.plateforme.socle.tenant.TenantAwareJpaTransactionManager;

/**
 * Configuration de la persistance.
 * <p>
 * Le bean s'appelle « transactionManager » : il remplace celui de Spring Boot
 * et est utilisé par toutes les méthodes {@code @Transactional}.
 */
@Configuration(proxyBeanMethods = false)
public class PersistanceConfig {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareJpaTransactionManager(emf);
    }

    /** Horloge injectable : permet de tester les expirations sans attendre. */
    @Bean
    Clock horloge() {
        return Clock.systemUTC();
    }
}
