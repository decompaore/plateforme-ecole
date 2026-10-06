package bf.edutech.plateforme.utilisateurs;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface JetonRafraichissementRepository extends JpaRepository<JetonRafraichissement, UUID> {

    Optional<JetonRafraichissement> findByHache(String hache);

    @Transactional
    @Modifying
    @Query("update JetonRafraichissement j set j.revoqueLe = :maintenant where j.famille = :famille and j.revoqueLe is null")
    int revoquerFamille(@Param("famille") UUID famille, @Param("maintenant") Instant maintenant);

    /** Ferme toutes les sessions d'un compte (réinitialisation du mot de passe). */
    @Transactional
    @Modifying
    @Query("update JetonRafraichissement j set j.revoqueLe = :maintenant where j.utilisateurId = :utilisateur and j.revoqueLe is null")
    int revoquerTout(@Param("utilisateur") UUID utilisateur, @Param("maintenant") Instant maintenant);
}
