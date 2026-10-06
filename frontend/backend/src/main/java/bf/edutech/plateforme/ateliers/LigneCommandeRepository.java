package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LigneCommandeRepository extends JpaRepository<LigneCommande, UUID> {

    List<LigneCommande> findByCommandeId(UUID commandeId);

    List<LigneCommande> findByCommandeIdIn(Collection<UUID> commandeIds);
}
