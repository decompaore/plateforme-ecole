package bf.edutech.plateforme.evaluations;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Filtré par la Row-Level Security. */
interface EvaluationRepository extends JpaRepository<Evaluation, UUID> {

    List<Evaluation> findByClasseIdAndPeriodeIdOrderByDateEvaluationAscLibelleAsc(UUID classeId, UUID periodeId);

    List<Evaluation> findByClasseIdIn(Collection<UUID> classeIds);
}
