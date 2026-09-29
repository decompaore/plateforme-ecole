package bf.edutech.plateforme.bulletins;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface GenerationBulletinsRepository extends JpaRepository<GenerationBulletins, UUID> {

    Optional<GenerationBulletins> findByClasseIdAndPeriodeId(UUID classeId, UUID periodeId);
}
