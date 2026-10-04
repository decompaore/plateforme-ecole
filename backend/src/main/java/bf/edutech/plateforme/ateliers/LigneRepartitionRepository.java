package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LigneRepartitionRepository extends JpaRepository<LigneRepartition, UUID> {

    List<LigneRepartition> findByLivraisonId(UUID livraisonId);

    @Modifying @Query("delete from LigneRepartition r where r.livraisonId = :livraisonId") void supprimerDe(@Param("livraisonId") UUID livraisonId);
}
