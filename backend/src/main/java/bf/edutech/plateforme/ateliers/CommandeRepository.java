package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommandeRepository extends JpaRepository<Commande, UUID> {

    List<Commande> findByCampagneIdOrderByDateCommandeAscCreeLeAsc(UUID campagneId);

    List<Commande> findByCampagneIdIn(Collection<UUID> campagneIds);

    boolean existsByReference(String reference);
}
