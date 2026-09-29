package bf.edutech.plateforme.absences;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AbsenceRepository extends JpaRepository<Absence, UUID> {

    List<Absence> findByAppelId(UUID appelId);

    List<Absence> findByAppelIdIn(Collection<UUID> appelIds);

    /** Absences et retards d'inscriptions sur une période, avec l'appel (date, horaires). */
    @Query("""
            select a, p from Absence a join Appel p on p.id = a.appelId
            where a.inscriptionId in :inscriptions and p.dateAppel between :du and :au
            order by p.dateAppel desc, p.heureDebut desc""")
    List<Object[]> avecAppels(@Param("inscriptions") Collection<UUID> inscriptionIds, @Param("du") LocalDate du,
            @Param("au") LocalDate au);
}
