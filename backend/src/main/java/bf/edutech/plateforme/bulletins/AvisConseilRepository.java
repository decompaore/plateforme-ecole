package bf.edutech.plateforme.bulletins;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AvisConseilRepository extends JpaRepository<AvisConseil, UUID> {

    List<AvisConseil> findByPeriodeIdAndInscriptionIdIn(UUID periodeId, Collection<UUID> inscriptionIds);
}
