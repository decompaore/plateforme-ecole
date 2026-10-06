package bf.edutech.plateforme.viescolaire;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface IncidentRepository extends JpaRepository<Incident, UUID> {

    List<Incident> findByInscriptionIdInOrderByDateFaitsDescSaisiLeDesc(Collection<UUID> inscriptionIds);
}
