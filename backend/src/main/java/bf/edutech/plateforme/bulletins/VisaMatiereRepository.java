package bf.edutech.plateforme.bulletins;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface VisaMatiereRepository extends JpaRepository<VisaMatiere, UUID> {

    List<VisaMatiere> findByClasseIdAndPeriodeId(UUID classeId, UUID periodeId);

    Optional<VisaMatiere> findByClasseIdAndMatiereIdAndPeriodeId(UUID classeId, UUID matiereId, UUID periodeId);
}
