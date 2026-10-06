package bf.edutech.plateforme.emploidutemps;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PublicationRepository extends JpaRepository<PublicationEmploi, UUID> {

    Optional<PublicationEmploi> findByAnneeId(UUID anneeId);
}
