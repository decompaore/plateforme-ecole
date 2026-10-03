package bf.edutech.plateforme.progression;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SeanceCahierRepository extends JpaRepository<SeanceCahier, UUID> {

    List<SeanceCahier> findByClasseIdAndMatiereIdOrderByDateDescHeureDebutDesc(UUID classeId, UUID matiereId);

    List<SeanceCahier> findByClasseIdIn(Collection<UUID> classeIds);

    Optional<SeanceCahier> findByClasseIdAndMatiereIdAndDateAndHeureDebut(UUID classeId, UUID matiereId,
            LocalDate date, LocalTime heureDebut);
}
