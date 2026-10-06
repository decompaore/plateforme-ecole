package bf.edutech.plateforme.socle.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Accès au journal d'audit ; la Row-Level Security limite la lecture à l'établissement actif. */
public interface JournalAuditRepository extends JpaRepository<JournalAudit, UUID> {

    Page<JournalAudit> findAllByOrderByHorodatageDesc(Pageable pagination);
}
