package bf.edutech.plateforme.evaluations;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ResultatCompetenceRepository extends JpaRepository<ResultatCompetence, UUID> {

    List<ResultatCompetence> findByPeriodeIdAndCompetenceIdIn(UUID periodeId, Collection<UUID> competenceIds);
}
