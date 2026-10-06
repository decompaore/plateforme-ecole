package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PanneRepository extends JpaRepository<Panne, UUID> {

    List<Panne> findByEquipementIdOrderBySignaleeLeDesc(UUID equipementId);

    List<Panne> findByEquipementIdInAndStatut(Collection<UUID> equipementIds, StatutPanne statut);

    Optional<Panne> findByEquipementIdAndStatut(UUID equipementId, StatutPanne statut);
}
