package bf.edutech.plateforme.absences;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface JustificatifRepository extends JpaRepository<Justificatif, UUID> {

    List<Justificatif> findByInscriptionIdOrderByDuDesc(UUID inscriptionId);

    List<Justificatif> findByInscriptionIdIn(Collection<UUID> inscriptionIds);
}
