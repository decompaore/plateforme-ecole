package bf.edutech.plateforme.absences;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Filtré par la Row-Level Security. */
interface AppelRepository extends JpaRepository<Appel, UUID> {

    Optional<Appel> findByClasseIdAndDateAppelAndHeureDebut(UUID classeId, LocalDate date, LocalTime heureDebut);

    List<Appel> findByClasseIdAndDateAppelOrderByHeureDebutAsc(UUID classeId, LocalDate date);

    List<Appel> findByClasseIdAndDateAppelBetween(UUID classeId, LocalDate du, LocalDate au);
}
