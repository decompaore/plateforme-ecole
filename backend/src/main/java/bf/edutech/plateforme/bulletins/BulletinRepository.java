package bf.edutech.plateforme.bulletins;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BulletinRepository extends JpaRepository<Bulletin, UUID> {

    List<Bulletin> findByGenerationIdOrderByRangAsc(UUID generationId);

    /** Bulletins publiés de ces inscriptions, avec leur génération. */
    @Query("""
            select b, g from Bulletin b join GenerationBulletins g on g.id = b.generationId
            where b.inscriptionId in :inscriptions and g.statut = :statut
            order by g.publieLe desc""")
    List<Object[]> publiesDe(@Param("inscriptions") Collection<UUID> inscriptionIds,
            @Param("statut") StatutGeneration statut);
}
