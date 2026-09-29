package bf.edutech.plateforme.enseignants;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Filtré par la Row-Level Security : engagements de l'établissement actif. */
interface EngagementRepository extends JpaRepository<Engagement, UUID> {

    List<Engagement> findByStatutIn(Collection<StatutEngagement> statuts);

    boolean existsByEnseignantIdAndStatutIn(UUID enseignantId, Collection<StatutEngagement> statuts);

    Optional<Engagement> findFirstByEnseignantIdAndStatut(UUID enseignantId, StatutEngagement statut);
}
