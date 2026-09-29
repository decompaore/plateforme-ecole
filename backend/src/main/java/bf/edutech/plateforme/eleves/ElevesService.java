package bf.edutech.plateforme.eleves;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.Eleve.IdentiteEleve;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.eleves.Vues.EleveVue;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.eleves.Vues.ResponsableDeEleveVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.persistance.PageResultat;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Dossiers des élèves et de leurs responsables. */
@Service
public class ElevesService {

    private static final int TAILLE_MAX = 100;

    private final EleveRepository eleves;
    private final ResponsableRepository responsables;
    private final LienResponsableEleveRepository liens;
    private final InscriptionRepository inscriptions;
    private final RegistreEleves registre;
    private final RegistreInscriptions registreInscriptions;
    private final AuditService audit;

    ElevesService(EleveRepository eleves, ResponsableRepository responsables, LienResponsableEleveRepository liens,
            InscriptionRepository inscriptions, RegistreEleves registre, RegistreInscriptions registreInscriptions,
            AuditService audit) {
        this.eleves = eleves;
        this.responsables = responsables;
        this.liens = liens;
        this.inscriptions = inscriptions;
        this.registre = registre;
        this.registreInscriptions = registreInscriptions;
        this.audit = audit;
    }

    /** Recherche par matricule, nom ou prénoms (tous les élèves si le texte est vide). */
    @Transactional(readOnly = true)
    public PageResultat<EleveVue> rechercher(String texte, int page, int taille) {
        UtilisateurConnecte.etablissementActif();
        PageRequest pagination = PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX),
                Sort.by("nom", "prenoms"));
        Page<Eleve> resultat = RegistreEleves.vide(texte) ? eleves.findAll(pagination)
                : eleves.rechercher("%" + texte.trim().toLowerCase(Locale.ROOT).replace("%", "") + "%", pagination);
        return PageResultat.depuis(resultat, EleveVue::depuis);
    }

    @Transactional(readOnly = true)
    public DossierEleveVue trouver(UUID id) {
        return dossier(charger(id));
    }

    /** Crée le dossier de l'élève et rattache ses responsables (facultatif). */
    @Transactional
    public DossierEleveVue creer(DonneesEleve donnees, List<DonneesResponsable> responsablesSaisis) {
        UtilisateurConnecte.etablissementActif();
        Eleve eleve = registre.creer(donnees);
        if (responsablesSaisis != null) {
            responsablesSaisis.forEach(r -> registre.rattacher(eleve, r));
        }
        audit.enregistrer("ELEVE_CREE", eleve.getMatricule(), null);
        return dossier(eleve);
    }

    /** Modifie l'identité ; le matricule n'est changé que s'il est fourni et différent. */
    @Transactional
    public DossierEleveVue modifier(UUID id, DonneesEleve donnees) {
        Eleve eleve = charger(id);
        IdentiteEleve identite = registre.normaliser(donnees);
        registre.verifierIdentifiantNational(identite.identifiantNational(), eleve.getId());
        if (!RegistreEleves.vide(donnees.matricule())) {
            String matricule = registre.matriculeValide(donnees.matricule());
            if (!matricule.equals(eleve.getMatricule())) {
                if (eleves.existsByMatricule(matricule)) {
                    throw new RegleMetierException("MATRICULE_EXISTANT",
                            "Le matricule " + matricule + " est déjà attribué");
                }
                audit.enregistrer("MATRICULE_MODIFIE", eleve.getMatricule(), Map.of("nouveau", matricule));
                eleve.changerMatricule(matricule);
            }
        }
        eleve.definir(identite);
        audit.enregistrer("ELEVE_MODIFIE", eleve.getMatricule(), null);
        return dossier(eleve);
    }

    @Transactional
    public DossierEleveVue ajouterResponsable(UUID eleveId, DonneesResponsable donnees) {
        Eleve eleve = charger(eleveId);
        registre.rattacher(eleve, donnees);
        audit.enregistrer("RESPONSABLE_RATTACHE", eleve.getMatricule(), null);
        return dossier(eleve);
    }

    @Transactional
    public DossierEleveVue retirerResponsable(UUID eleveId, UUID responsableId) {
        Eleve eleve = charger(eleveId);
        registre.detacher(eleveId, responsableId);
        audit.enregistrer("RESPONSABLE_DETACHE", eleve.getMatricule(), Map.of("responsable", responsableId));
        return dossier(eleve);
    }

    /**
     * Modifie les coordonnées d'un responsable (pour tous ses enfants). Le
     * téléphone ne change plus une fois l'espace parent ouvert : il sert d'identifiant de connexion.
     */
    @Transactional
    public void modifierResponsable(UUID responsableId, String nom, String prenoms, String telephoneSaisi,
            String profession, LangueSms langueSms) {
        UtilisateurConnecte.etablissementActif();
        Responsable responsable = responsables.findById(responsableId)
                .orElseThrow(() -> new RessourceIntrouvableException("Responsable introuvable"));
        String telephone = registre.telephone(telephoneSaisi);
        if (!telephone.equals(responsable.getTelephone())) {
            if (responsable.getUtilisateurId() != null) {
                throw new RegleMetierException("ESPACE_PARENT_OUVERT", "Le téléphone sert à la connexion à l'espace "
                        + "parent : le responsable le modifie lui-même depuis son compte");
            }
            if (responsables.findByTelephone(telephone).isPresent()) {
                throw new RegleMetierException("TELEPHONE_EXISTANT", "Un autre responsable a déjà ce numéro");
            }
            responsable.changerTelephone(telephone);
        }
        responsable.modifier(RegistreEleves.obligatoire(nom, "Le nom", 80).toUpperCase(Locale.ROOT),
                RegistreEleves.obligatoire(prenoms, "Les prénoms", 120),
                RegistreEleves.facultatif(profession, "La profession", 80), langueSms);
        audit.enregistrer("RESPONSABLE_MODIFIE", responsableId.toString(), null);
    }

    private Eleve charger(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return eleves.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Élève introuvable"));
    }

    private DossierEleveVue dossier(Eleve eleve) {
        List<LienResponsableEleve> liensEleve = liens.findByEleveId(eleve.getId());
        Map<UUID, Responsable> parId = liensEleve.isEmpty() ? Map.of() : responsables
                .findByIdIn(liensEleve.stream().map(LienResponsableEleve::getResponsableId).toList()).stream()
                .collect(Collectors.toMap(Responsable::getId, Function.identity()));
        List<ResponsableDeEleveVue> vuesResponsables = liensEleve.stream()
                .sorted(Comparator.comparing(LienResponsableEleve::isContactPrioritaire).reversed())
                .map(l -> ResponsableDeEleveVue.depuis(l, parId.get(l.getResponsableId())))
                .toList();
        Libelles libelles = registreInscriptions.libelles();
        List<InscriptionVue> parcours = libelles.parAnneeDecroissante(
                inscriptions.findByEleveIdOrderByInscritLeDesc(eleve.getId())).stream()
                .map(i -> InscriptionVue.depuis(i, eleve, libelles.annee(i.getAnneeId()).libelle(),
                        libelles.classe(i.getClasseId()).code()))
                .toList();
        return new DossierEleveVue(EleveVue.depuis(eleve), vuesResponsables, parcours);
    }
}
