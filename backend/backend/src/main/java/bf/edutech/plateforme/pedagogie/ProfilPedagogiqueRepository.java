package bf.edutech.plateforme.pedagogie;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Profils de l'établissement actif (filtrés par la Row-Level Security). */
interface ProfilPedagogiqueRepository extends JpaRepository<ProfilPedagogique, UUID> {

    boolean existsByCode(String code);

    List<ProfilPedagogique> findAllByOrderByOrdreAscLibelleAsc();
}
