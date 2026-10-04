package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LigneBesoinRepository extends JpaRepository<LigneBesoin, UUID> {

    List<LigneBesoin> findByBesoinId(UUID besoinId);

    List<LigneBesoin> findByBesoinIdIn(Collection<UUID> besoinIds);

    Optional<LigneBesoin> findByBesoinIdAndArticleId(UUID besoinId, UUID articleId);
}
