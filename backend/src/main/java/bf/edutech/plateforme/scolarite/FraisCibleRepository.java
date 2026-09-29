package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FraisCibleRepository extends JpaRepository<FraisCible, UUID> {

    List<FraisCible> findByFraisIdIn(Collection<UUID> fraisIds);

    void deleteByFraisId(UUID fraisId);
}
