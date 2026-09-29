package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SouscriptionRepository extends JpaRepository<SouscriptionFrais, UUID> {

    List<SouscriptionFrais> findByInscriptionIdIn(Collection<UUID> inscriptionIds);

    Optional<SouscriptionFrais> findByInscriptionIdAndFraisId(UUID inscriptionId, UUID fraisId);
}
