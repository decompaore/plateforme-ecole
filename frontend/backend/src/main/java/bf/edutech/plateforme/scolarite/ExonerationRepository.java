package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExonerationRepository extends JpaRepository<Exoneration, UUID> {

    List<Exoneration> findByInscriptionIdIn(Collection<UUID> inscriptionIds);

    Optional<Exoneration> findByInscriptionIdAndFraisId(UUID inscriptionId, UUID fraisId);
}
