package bf.edutech.plateforme.eleves;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Parents à joindre pour un message de l'établissement (v0.46) : responsables légaux et contacts prioritaires
 * des élèves inscrits (actifs) dans une année, éventuellement de certaines classes. Un parent de plusieurs
 * enfants n'apparaît qu'une fois.
 */
@Service
public class FamillesDestinataires {

    /**
     * Un parent : son compte « espace parent » ({@code utilisateurId}, null s'il n'en a pas), son téléphone
     * et ses enfants (« Awa OUEDRAOGO (6e A) »).
     */
    public record Famille(UUID responsableId, UUID utilisateurId, String nom, String prenoms, String telephone,
            String langueSms, List<String> enfants) {
    }

    private final JdbcTemplate jdbc;

    FamillesDestinataires(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code classeIds} vide : toutes les classes de l'année. */
    @Transactional(readOnly = true)
    public List<Famille> parents(UUID anneeId, Collection<UUID> classeIds) {
        UtilisateurConnecte.etablissementActif();
        Set<UUID> classes = classeIds == null ? Set.of() : new HashSet<>(classeIds);
        Map<UUID, Famille> parResponsable = new LinkedHashMap<>();
        jdbc.query("""
                select r.id, r.utilisateur_id, r.nom, r.prenoms, r.telephone, r.langue_sms,
                       e.prenoms || ' ' || e.nom || ' (' || c.code || ')', c.id
                  from responsable r
                  join lien_responsable_eleve l on l.tenant_id = r.tenant_id and l.responsable_id = r.id
                  join inscription i on i.tenant_id = l.tenant_id and i.eleve_id = l.eleve_id and i.statut = 'ACTIVE'
                  join classe c on c.tenant_id = i.tenant_id and c.id = i.classe_id
                  join eleve e on e.tenant_id = i.tenant_id and e.id = i.eleve_id
                 where c.annee_id = ? and (l.responsable_legal or l.contact_prioritaire)
                 order by r.nom, r.prenoms, e.nom, e.prenoms""", r -> {
            UUID classe = r.getObject(8, UUID.class);
            if (!classes.isEmpty() && !classes.contains(classe)) {
                return;
            }
            UUID id = r.getObject(1, UUID.class);
            Famille f = parResponsable.computeIfAbsent(id, k -> {
                try {
                    return new Famille(k, r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getString(5),
                            r.getString(6), new ArrayList<>());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            String enfant = r.getString(7);
            if (!f.enfants().contains(enfant)) {
                f.enfants().add(enfant);
            }
        }, anneeId);
        return new ArrayList<>(parResponsable.values());
    }
}
