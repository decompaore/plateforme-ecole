package bf.edutech.plateforme.evaluations;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface NoteRepository extends JpaRepository<Note, UUID> {

    List<Note> findByEvaluationId(UUID evaluationId);

    List<Note> findByEvaluationIdIn(Collection<UUID> evaluationIds);
}
