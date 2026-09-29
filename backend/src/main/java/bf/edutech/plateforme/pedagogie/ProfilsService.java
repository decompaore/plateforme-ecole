package bf.edutech.plateforme.pedagogie;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Gestion des profils pédagogiques de l'établissement actif.
 * <p>
 * Trois profils types (général, technique, professionnel) peuvent être créés
 * en une opération ; l'établissement les ajuste ensuite à ses pratiques.
 */
@Service
public class ProfilsService {

    /** Données modifiables d'un profil. */
    public record DonneesProfil(String libelle, OrdreEnseignement ordre, CodeModele modele, Decoupage decoupage,
            String gabaritDocument, BigDecimal seuilAdmission, Short decimalesMoyenne, BigDecimal noteEliminatoire,
            BigDecimal seuilMaitrise, ProfilVue.Vocabulaire vocabulaire) {
    }

    private final ProfilPedagogiqueRepository depot;
    private final AuditService audit;
    private final UtilisationProfils utilisation;

    ProfilsService(ProfilPedagogiqueRepository depot, AuditService audit, UtilisationProfils utilisation) {
        this.depot = depot;
        this.audit = audit;
        this.utilisation = utilisation;
    }

    @Transactional(readOnly = true)
    public List<ProfilVue> lister() {
        UtilisateurConnecte.etablissementActif();
        return depot.findAllByOrderByOrdreAscLibelleAsc().stream().map(ProfilPedagogique::versVue).toList();
    }

    /** Profil de l'établissement actif ; erreur 404 s'il n'existe pas (ou appartient à une autre école). */
    @Transactional(readOnly = true)
    public ProfilVue trouver(UUID id) {
        return charger(id).versVue();
    }

    /** Crée les profils types manquants : GENERAL, TECHNIQUE, PROFESSIONNEL. Sans effet s'ils existent. */
    @Transactional
    public List<ProfilVue> initialiserProfilsTypes() {
        UtilisateurConnecte.etablissementActif();
        int crees = 0;
        if (!depot.existsByCode("GENERAL")) {
            depot.save(new ProfilPedagogique("GENERAL", "Enseignement général", OrdreEnseignement.GENERAL,
                    CodeModele.NOTES_COEFFICIENTS, Decoupage.TRIMESTRE, "bulletin-general"));
            crees++;
        }
        if (!depot.existsByCode("TECHNIQUE")) {
            depot.save(new ProfilPedagogique("TECHNIQUE", "Enseignement technique", OrdreEnseignement.TECHNIQUE,
                    CodeModele.NOTES_PAR_GROUPES, Decoupage.TRIMESTRE, "bulletin-technique"));
            crees++;
        }
        if (!depot.existsByCode("PROFESSIONNEL")) {
            ProfilPedagogique pro = new ProfilPedagogique("PROFESSIONNEL", "Formation professionnelle (APC)",
                    OrdreEnseignement.PROFESSIONNEL, CodeModele.COMPETENCES, Decoupage.MODULE, "releve-competences");
            pro.modifierRegles("Formation professionnelle (APC)", "releve-competences", BigDecimal.TEN, (short) 2,
                    null, new BigDecimal("70"));
            pro.modifierVocabulaire("Apprenant", "Groupe", "Module", "Formateur");
            depot.save(pro);
            crees++;
        }
        if (crees > 0) {
            audit.enregistrer("PROFILS_INITIALISES", null, Map.of("crees", crees));
        }
        return lister();
    }

    @Transactional
    public ProfilVue creer(String codeSaisi, DonneesProfil d) {
        UtilisateurConnecte.etablissementActif();
        String code = codeSaisi.trim().toUpperCase(Locale.ROOT);
        if (depot.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Un profil porte déjà ce code");
        }
        ProfilPedagogique profil = new ProfilPedagogique(code, d.libelle().trim(), d.ordre(), d.modele(),
                d.decoupage(), d.gabaritDocument());
        appliquer(profil, d);
        depot.save(profil);
        audit.enregistrer("PROFIL_CREE", code, null);
        return profil.versVue();
    }

    /**
     * Modifie un profil. L'ordre d'enseignement, le modèle d'évaluation et le
     * découpage ne peuvent plus changer dès qu'une filière utilise le profil.
     */
    @Transactional
    public ProfilVue modifier(UUID id, DonneesProfil d) {
        ProfilPedagogique profil = charger(id);
        boolean structureChange = profil.getOrdre() != d.ordre() || profil.getModele() != d.modele()
                || profil.getDecoupage() != d.decoupage();
        if (structureChange && utilisation.estUtilise(id)) {
            throw new RegleMetierException("PROFIL_UTILISE",
                    "Ce profil est utilisé par des filières : seuls ses libellés et ses règles peuvent changer");
        }
        profil.modifierStructure(d.ordre(), d.modele(), d.decoupage());
        appliquer(profil, d);
        audit.enregistrer("PROFIL_MODIFIE", profil.getCode(), null);
        return profil.versVue();
    }

    @Transactional
    public ProfilVue changerActivation(UUID id, boolean actif) {
        ProfilPedagogique profil = charger(id);
        profil.changerActivation(actif);
        return profil.versVue();
    }

    private ProfilPedagogique charger(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return depot.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Profil pédagogique introuvable"));
    }

    private static void appliquer(ProfilPedagogique profil, DonneesProfil d) {
        profil.modifierRegles(d.libelle().trim(), d.gabaritDocument(),
                d.seuilAdmission() != null ? d.seuilAdmission() : BigDecimal.TEN,
                d.decimalesMoyenne() != null ? d.decimalesMoyenne().shortValue() : (short) 2,
                d.noteEliminatoire(), d.seuilMaitrise());
        if (d.vocabulaire() != null) {
            ProfilVue.Vocabulaire v = d.vocabulaire();
            profil.modifierVocabulaire(v.apprenant(), v.groupe(), v.matiere(), v.enseignant());
        }
    }
}
