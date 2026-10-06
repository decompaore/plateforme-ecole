package bf.edutech.plateforme.viescolaire;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ConvocationRepository extends JpaRepository<Convocation, UUID> {

    List<Convocation> findByInscriptionIdInOrderByRendezVousDesc(Collection<UUID> inscriptionIds);

    List<Convocation> findByRendezVousBetweenOrderByRendezVousAsc(LocalDateTime du, LocalDateTime au);

    List<Convocation> findByIncidentIdAndStatut(UUID incidentId, StatutConvocation statut);
}
