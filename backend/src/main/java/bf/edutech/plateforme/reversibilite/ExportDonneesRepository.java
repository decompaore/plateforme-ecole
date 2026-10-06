package bf.edutech.plateforme.reversibilite;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExportDonneesRepository extends JpaRepository<ExportDonnees, UUID> {

    /** Exports de l'établissement actif (Row-Level Security), le plus récent d'abord. */
    List<ExportDonnees> findAllByOrderByDemandeLeDesc();

    long countByDemandeLeAfter(Instant depuis);

    List<ExportDonnees> findAllByStatut(StatutExport statut);
}
