package bf.edutech.plateforme.scolarite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrganismeRepository extends JpaRepository<OrganismeFinanceur, UUID> {

    List<OrganismeFinanceur> findAllByOrderByNomAsc();

    boolean existsByNomIgnoreCase(String nom);
}
