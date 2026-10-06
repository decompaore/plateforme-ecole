package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RelanceRepository extends JpaRepository<Relance, UUID> {

    List<Relance> findByInscriptionIdIn(Collection<UUID> inscriptionIds);
}
