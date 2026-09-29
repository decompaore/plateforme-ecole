package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PriseEnChargeRepository extends JpaRepository<PriseEnCharge, UUID> {

    List<PriseEnCharge> findByInscriptionIdIn(Collection<UUID> inscriptionIds);

    Optional<PriseEnCharge> findByInscriptionId(UUID inscriptionId);
}
