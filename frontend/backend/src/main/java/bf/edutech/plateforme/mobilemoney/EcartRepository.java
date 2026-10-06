package bf.edutech.plateforme.mobilemoney;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EcartRepository extends JpaRepository<EcartRapprochement, UUID> {

    List<EcartRapprochement> findByRapprochementIdOrderByTypeAscReferenceAsc(UUID rapprochementId);
}
