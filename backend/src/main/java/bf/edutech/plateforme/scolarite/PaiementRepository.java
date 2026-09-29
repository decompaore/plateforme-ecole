package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaiementRepository extends JpaRepository<Paiement, UUID> {

    List<Paiement> findByInscriptionIdIn(Collection<UUID> inscriptionIds);

    List<Paiement> findByInscriptionIdOrderByEnregistreLeAsc(UUID inscriptionId);

    Optional<Paiement> findByCleIdempotence(String cleIdempotence);

    List<Paiement> findByDatePaiementBetweenOrderByEnregistreLeAsc(java.time.LocalDate du, java.time.LocalDate au);
}
