package bf.edutech.plateforme.etablissement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Filtré par la Row-Level Security : aucune condition sur l'établissement n'est nécessaire. */
interface ClasseMatiereRepository extends JpaRepository<ClasseMatiere, UUID> {

    List<ClasseMatiere> findByClasseId(UUID classeId);

    Optional<ClasseMatiere> findByClasseIdAndMatiereId(UUID classeId, UUID matiereId);
}
