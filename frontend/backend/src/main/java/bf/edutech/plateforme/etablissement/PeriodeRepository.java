package bf.edutech.plateforme.etablissement;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Filtré par la Row-Level Security : aucune condition sur l'établissement n'est nécessaire. */
interface PeriodeRepository extends JpaRepository<Periode, UUID> {

    List<Periode> findByAnneeIdOrderByProfilIdAscOrdreAsc(UUID anneeId);

    List<Periode> findByAnneeIdAndProfilIdOrderByOrdreAsc(UUID anneeId, UUID profilId);

    boolean existsByAnneeIdAndProfilId(UUID anneeId, UUID profilId);

    long countByAnneeIdAndVerrouilleeFalse(UUID anneeId);
}
