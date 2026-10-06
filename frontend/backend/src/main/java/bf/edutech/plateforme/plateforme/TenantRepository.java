package bf.edutech.plateforme.plateforme;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsByCode(String code);

    List<Tenant> findAllByOrderByNomAsc();
}
