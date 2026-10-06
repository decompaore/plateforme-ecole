package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BesoinRepository extends JpaRepository<BesoinAtelier, UUID> {

    List<BesoinAtelier> findByCampagneId(UUID campagneId);

    List<BesoinAtelier> findByCampagneIdIn(Collection<UUID> campagneIds);

    List<BesoinAtelier> findByAtelierId(UUID atelierId);

    Optional<BesoinAtelier> findByCampagneIdAndAtelierId(UUID campagneId, UUID atelierId);
}
