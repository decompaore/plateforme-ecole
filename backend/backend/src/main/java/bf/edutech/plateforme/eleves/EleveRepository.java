package bf.edutech.plateforme.eleves;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Filtré par la Row-Level Security : aucune condition sur l'établissement n'est nécessaire. */
interface EleveRepository extends JpaRepository<Eleve, UUID> {

    Optional<Eleve> findByMatricule(String matricule);

    boolean existsByMatricule(String matricule);

    Optional<Eleve> findByIdentifiantNational(String identifiantNational);

    List<Eleve> findByIdIn(Collection<UUID> ids);

    /** Élève de même identité (détection des doublons lors de l'import). */
    Optional<Eleve> findFirstByNomIgnoreCaseAndPrenomsIgnoreCaseAndDateNaissance(String nom, String prenoms,
            LocalDate dateNaissance);

    /** Recherche sur le matricule, le nom ou les prénoms (motif en minuscules, avec %). */
    @Query("""
            select e from Eleve e
            where lower(e.matricule) like :motif or lower(e.nom) like :motif or lower(e.prenoms) like :motif
               or lower(concat(e.nom, ' ', e.prenoms)) like :motif""")
    Page<Eleve> rechercher(@Param("motif") String motif, Pageable pagination);
}
