package bf.edutech.plateforme.etablissement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Filtré par la Row-Level Security : aucune condition sur l'établissement n'est nécessaire. */
interface ClasseMatiereRepository extends JpaRepository<ClasseMatiere, UUID> {

    List<ClasseMatiere> findByClasseId(UUID classeId);

    Optional<ClasseMatiere> findByClasseIdAndMatiereId(UUID classeId, UUID matiereId);

    boolean existsByClasseIdAndEngagementId(UUID classeId, UUID engagementId);

    /** Matières assurées par un engagement dans les classes d'une année (avec la classe). */
    @Query("""
            select cm, c from ClasseMatiere cm join Classe c on c.id = cm.classeId
            where cm.engagementId = :engagement and c.anneeId = :annee
            order by c.code""")
    List<Object[]> affectationsDe(@Param("engagement") UUID engagementId, @Param("annee") UUID anneeId);

    /** Matières sans enseignant dans les classes d'une année (avec la classe). */
    @Query("""
            select cm, c from ClasseMatiere cm join Classe c on c.id = cm.classeId
            where cm.engagementId is null and c.anneeId = :annee
            order by c.code""")
    List<Object[]> sansEnseignant(@Param("annee") UUID anneeId);

    /** Affectations d'un engagement dans des années encore modifiables. */
    @Query("""
            select cm from ClasseMatiere cm join Classe c on c.id = cm.classeId
            join AnneeScolaire a on a.id = c.anneeId
            where cm.engagementId = :engagement and a.etat in :etats""")
    List<ClasseMatiere> affecteesDansLesAnnees(@Param("engagement") UUID engagementId,
            @Param("etats") Collection<EtatAnnee> etats);
}
