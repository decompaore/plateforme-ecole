package bf.edutech.plateforme.passage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DecisionRepository extends JpaRepository<DecisionFinAnnee, UUID> {

    List<DecisionFinAnnee> findByClasseId(UUID classeId);

    List<DecisionFinAnnee> findByInscriptionIdIn(Collection<UUID> inscriptionIds);

    List<DecisionFinAnnee> findByClasseIdIn(Collection<UUID> classeIds);
}
