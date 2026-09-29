package bf.edutech.plateforme.bulletins;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AppreciationRepository extends JpaRepository<Appreciation, UUID> {

    List<Appreciation> findByPeriodeIdAndInscriptionIdIn(UUID periodeId, Collection<UUID> inscriptionIds);

    List<Appreciation> findByPeriodeIdAndMatiereIdAndInscriptionIdIn(UUID periodeId, UUID matiereId,
            Collection<UUID> inscriptionIds);
}
