package bf.edutech.plateforme.utilisateurs;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SessionAppareilRepository extends JpaRepository<SessionAppareil, UUID> {

    Optional<SessionAppareil> findByFamille(UUID famille);

    List<SessionAppareil> findByUtilisateurIdAndFermeeLeIsNullOrderByDernierUsageDesc(UUID utilisateurId);

    /** Appareils connectés dans un établissement, par compte (sessions ouvertes et utilisées depuis {@code depuis}). */
    @Query("""
            select s.utilisateurId, count(s) from SessionAppareil s
            where s.fermeeLe is null and s.tenantId = :tenant and s.dernierUsage > :depuis
            group by s.utilisateurId""")
    List<Object[]> compterParCompte(@Param("tenant") UUID tenantId, @Param("depuis") Instant depuis);
}
