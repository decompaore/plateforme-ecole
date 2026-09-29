package bf.edutech.plateforme.mobilemoney;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TransactionRepository extends JpaRepository<TransactionMobileMoney, UUID> {

    Optional<TransactionMobileMoney> findByReference(String reference);

    Optional<TransactionMobileMoney> findByCleIdempotence(String cle);

    List<TransactionMobileMoney> findByCreeLeBetweenOrderByCreeLeDesc(Instant du, Instant au);

    List<TransactionMobileMoney> findByStatutInOrderByCreeLeDesc(Collection<StatutTransaction> statuts);

    List<TransactionMobileMoney> findByInscriptionIdOrderByCreeLeDesc(UUID inscriptionId);

    List<TransactionMobileMoney> findByTermineLeBetween(Instant du, Instant au);
}
