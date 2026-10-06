package bf.edutech.plateforme.progression;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FicheProgressionRepository extends JpaRepository<FicheProgression, UUID> {

    Optional<FicheProgression> findByClasseIdAndMatiereId(UUID classeId, UUID matiereId);

    List<FicheProgression> findByClasseIdIn(Collection<UUID> classeIds);
}
