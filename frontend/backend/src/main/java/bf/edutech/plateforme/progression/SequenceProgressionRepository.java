package bf.edutech.plateforme.progression;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SequenceProgressionRepository extends JpaRepository<SequenceProgression, UUID> {

    List<SequenceProgression> findByFicheIdOrderByOrdre(UUID ficheId);

    List<SequenceProgression> findByFicheIdIn(Collection<UUID> ficheIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SequenceProgression s where s.ficheId = :ficheId")
    void supprimerDeLaFiche(UUID ficheId);
}
