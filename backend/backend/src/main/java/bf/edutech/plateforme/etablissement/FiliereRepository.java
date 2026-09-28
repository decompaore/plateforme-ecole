package bf.edutech.plateforme.etablissement;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Filtré par la Row-Level Security : aucune condition sur l'établissement n'est nécessaire. */
interface FiliereRepository extends JpaRepository<Filiere, UUID> {

    boolean existsByCode(String code);

    List<Filiere> findAllByOrderByCodeAsc();
}
