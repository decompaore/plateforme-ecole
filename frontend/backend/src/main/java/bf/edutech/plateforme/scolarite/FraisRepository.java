package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FraisRepository extends JpaRepository<FraisScolarite, UUID> {

    List<FraisScolarite> findByAnneeIdOrderByLibelleAsc(UUID anneeId);

    List<FraisScolarite> findByAnneeIdIn(Collection<UUID> anneeIds);

    boolean existsByAnneeIdAndLibelleIgnoreCase(UUID anneeId, String libelle);
}
