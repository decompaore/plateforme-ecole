package bf.edutech.plateforme.scolarite;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.scolarite.Vues.OrganismeVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Organismes financeurs des bourses (État, collectivités, ONG, entreprises). */
@Service
public class OrganismesService {

    private final OrganismeRepository organismes;
    private final AuditService audit;

    OrganismesService(OrganismeRepository organismes, AuditService audit) {
        this.organismes = organismes;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<OrganismeVue> lister() {
        UtilisateurConnecte.etablissementActif();
        return organismes.findAllByOrderByNomAsc().stream().map(OrganismesService::vue).toList();
    }

    @Transactional
    public OrganismeVue creer(String nom, TypeOrganisme type, String telephone) {
        UtilisateurConnecte.etablissementActif();
        String propre = obligatoire(nom);
        if (organismes.existsByNomIgnoreCase(propre)) {
            throw new RegleMetierException("ORGANISME_EXISTANT", "L'organisme « " + propre + " » existe déjà");
        }
        OrganismeFinanceur o = organismes.save(new OrganismeFinanceur(propre, type, vide(telephone)));
        audit.enregistrer("ORGANISME_CREE", propre, Map.of("type", type.name()));
        return vue(o);
    }

    @Transactional
    public OrganismeVue modifier(UUID id, String nom, TypeOrganisme type, String telephone, boolean actif) {
        OrganismeFinanceur o = trouver(id);
        String propre = obligatoire(nom);
        if (!propre.equalsIgnoreCase(o.getNom()) && organismes.existsByNomIgnoreCase(propre)) {
            throw new RegleMetierException("ORGANISME_EXISTANT", "L'organisme « " + propre + " » existe déjà");
        }
        o.modifier(propre, type, vide(telephone), actif);
        audit.enregistrer("ORGANISME_MODIFIE", propre, null);
        return vue(o);
    }

    OrganismeFinanceur trouver(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return organismes.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Organisme introuvable"));
    }

    static OrganismeVue vue(OrganismeFinanceur o) {
        return new OrganismeVue(o.getId(), o.getNom(), o.getType(), o.getTelephone(), o.isActif());
    }

    private static String obligatoire(String nom) {
        if (nom == null || nom.isBlank()) {
            throw new IllegalArgumentException("Le nom de l'organisme est obligatoire");
        }
        return nom.trim().replaceAll("\\s+", " ");
    }

    private static String vide(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
