package bf.edutech.plateforme.eleves;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface LienResponsableEleveRepository extends JpaRepository<LienResponsableEleve, UUID> {

    List<LienResponsableEleve> findByEleveId(UUID eleveId);

    List<LienResponsableEleve> findByResponsableId(UUID responsableId);

    Optional<LienResponsableEleve> findByEleveIdAndResponsableId(UUID eleveId, UUID responsableId);

    boolean existsByEleveIdAndResponsableId(UUID eleveId, UUID responsableId);
}
