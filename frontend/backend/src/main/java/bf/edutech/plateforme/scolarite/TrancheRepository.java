package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TrancheRepository extends JpaRepository<TrancheFrais, UUID> {

    List<TrancheFrais> findByFraisIdInOrderByNumeroAsc(Collection<UUID> fraisIds);

    void deleteByFraisId(UUID fraisId);
}
