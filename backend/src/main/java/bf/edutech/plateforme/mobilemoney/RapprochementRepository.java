package bf.edutech.plateforme.mobilemoney;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RapprochementRepository extends JpaRepository<Rapprochement, UUID> {

    Optional<Rapprochement> findByDateReleve(LocalDate dateReleve);

    List<Rapprochement> findByDateReleveBetweenOrderByDateReleveDesc(LocalDate du, LocalDate au);
}
