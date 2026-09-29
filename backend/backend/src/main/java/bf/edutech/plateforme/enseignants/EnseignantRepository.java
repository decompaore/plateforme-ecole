package bf.edutech.plateforme.enseignants;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Filtré par la Row-Level Security : seuls les enseignants engagés ou invités dans l'établissement. */
interface EnseignantRepository extends JpaRepository<Enseignant, UUID> {

    Optional<Enseignant> findByUtilisateurId(UUID utilisateurId);

    List<Enseignant> findByIdIn(Collection<UUID> ids);
}
