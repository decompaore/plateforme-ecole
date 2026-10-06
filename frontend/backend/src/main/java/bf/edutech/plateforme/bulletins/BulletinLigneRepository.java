package bf.edutech.plateforme.bulletins;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface BulletinLigneRepository extends JpaRepository<BulletinLigne, UUID> {

    List<BulletinLigne> findByBulletinIdOrderByOrdreAsc(UUID bulletinId);
}
