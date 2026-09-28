package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Appartenances de l'établissement actif (filtrées par la Row-Level Security :
 * aucune condition sur tenant_id n'est nécessaire dans les requêtes).
 */
public interface MembreEtablissementRepository extends JpaRepository<MembreEtablissement, UUID> {

    Optional<MembreEtablissement> findByUtilisateurIdAndRole(UUID utilisateurId, Role role);

    long countByRoleAndActifTrue(Role role);

    @Query("""
            select new bf.edutech.plateforme.utilisateurs.MembreVue(
                m.id, u.id, u.nom, u.prenoms, u.telephone, m.role, m.actif)
            from MembreEtablissement m
            join Utilisateur u on u.id = m.utilisateurId
            order by u.nom, u.prenoms, m.role
            """)
    List<MembreVue> listerAvecIdentite();
}
