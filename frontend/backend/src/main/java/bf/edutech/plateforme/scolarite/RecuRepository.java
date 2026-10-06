package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RecuRepository extends JpaRepository<Recu, UUID> {

    Optional<Recu> findByPaiementId(UUID paiementId);

    List<Recu> findByPaiementIdIn(Collection<UUID> paiementIds);
}
