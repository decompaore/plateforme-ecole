package bf.edutech.plateforme.eleves;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InscriptionRepository extends JpaRepository<Inscription, UUID> {

    boolean existsByEleveIdAndAnneeId(UUID eleveId, UUID anneeId);

    Optional<Inscription> findByEleveIdAndAnneeId(UUID eleveId, UUID anneeId);

    List<Inscription> findByEleveIdOrderByInscritLeDesc(UUID eleveId);

    long countByClasseIdAndStatut(UUID classeId, StatutInscription statut);

    /** Inscriptions d'une classe (y compris les sorties) avec l'élève, triées par nom puis prénoms. */
    @Query("""
            select i, e from Inscription i join Eleve e on e.id = i.eleveId
            where i.classeId = :classe
            order by e.nom, e.prenoms""")
    List<Object[]> listerAvecEleves(@Param("classe") UUID classeId);
}
