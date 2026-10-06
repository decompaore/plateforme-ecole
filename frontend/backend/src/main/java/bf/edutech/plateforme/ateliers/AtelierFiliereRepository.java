package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AtelierFiliereRepository extends JpaRepository<AtelierFiliere, UUID> {

    List<AtelierFiliere> findByAtelierId(UUID atelierId);

    List<AtelierFiliere> findByAtelierIdIn(Collection<UUID> atelierIds);

    @Modifying @Query("delete from AtelierFiliere f where f.atelierId = :atelierId") void supprimerDe(@Param("atelierId") UUID atelierId);
}
