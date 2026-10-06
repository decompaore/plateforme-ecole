package bf.edutech.plateforme.emploidutemps;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SeanceEmploiRepository extends JpaRepository<SeanceEmploi, UUID> {

    List<SeanceEmploi> findByAnneeId(UUID anneeId);

    boolean existsByCreneauId(UUID creneauId);

    boolean existsByCreneauIdAndJour(UUID creneauId, short jour);
}
