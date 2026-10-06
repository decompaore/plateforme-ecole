package bf.edutech.plateforme.emploidutemps;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CreneauRepository extends JpaRepository<Creneau, UUID> {

    List<Creneau> findByAnneeIdOrderByHeureDebutAsc(UUID anneeId);
}
