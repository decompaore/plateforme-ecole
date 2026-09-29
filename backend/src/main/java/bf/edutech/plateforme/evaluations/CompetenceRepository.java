package bf.edutech.plateforme.evaluations;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CompetenceRepository extends JpaRepository<Competence, UUID> {

    List<Competence> findByMatiereIdOrderByOrdreAscCodeAsc(UUID matiereId);

    List<Competence> findByMatiereIdInAndActifTrue(Collection<UUID> matiereIds);

    boolean existsByMatiereIdAndCode(UUID matiereId, String code);
}
