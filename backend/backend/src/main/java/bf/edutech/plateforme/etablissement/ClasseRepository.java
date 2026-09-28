package bf.edutech.plateforme.etablissement;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Filtré par la Row-Level Security : aucune condition sur l'établissement n'est nécessaire. */
interface ClasseRepository extends JpaRepository<Classe, UUID> {

    List<Classe> findByAnneeIdOrderByCodeAsc(UUID anneeId);

    boolean existsByAnneeIdAndCode(UUID anneeId, String code);

    boolean existsByFiliereId(UUID filiereId);

    long countByAnneeId(UUID anneeId);

    /** Profils pédagogiques utilisés par les classes d'une année. */
    @Query("select distinct f.profilId from Classe c join Filiere f on f.id = c.filiereId where c.anneeId = :annee")
    List<UUID> profilsUtilises(@Param("annee") UUID anneeId);
}
