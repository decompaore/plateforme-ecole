package bf.edutech.plateforme.eleves;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ResponsableRepository extends JpaRepository<Responsable, UUID> {

    Optional<Responsable> findByTelephone(String telephone);

    Optional<Responsable> findByUtilisateurId(UUID utilisateurId);

    List<Responsable> findByIdIn(Collection<UUID> ids);
}
