package bf.edutech.plateforme.etablissement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.etablissement.Vues.AffectationVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.pedagogie.Decoupage;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;

/**
 * Classes d'une année et matières enseignées dans chaque classe.
 * <p>
 * Les règles dépendent du profil pédagogique de la filière : groupe de matières
 * obligatoire en enseignement technique (notes par groupes), durée du module
 * obligatoire en formation professionnelle (découpage en modules).
 */
@Service
public class ClassesService {

    private final ClasseRepository classes;
    private final ClasseMatiereRepository matieresDeClasse;
    private final FiliereRepository filieres;
    private final MatiereRepository matieres;
    private final AccesAnnees annees;
    private final ProfilsService profils;
    private final AuditService audit;
    private final OccupationClasses occupation;

    ClassesService(ClasseRepository classes, ClasseMatiereRepository matieresDeClasse, FiliereRepository filieres,
            MatiereRepository matieres, AccesAnnees annees, ProfilsService profils, AuditService audit,
            OccupationClasses occupation) {
        this.classes = classes;
        this.matieresDeClasse = matieresDeClasse;
        this.filieres = filieres;
        this.matieres = matieres;
        this.annees = annees;
        this.profils = profils;
        this.audit = audit;
        this.occupation = occupation;
    }

    @Transactional(readOnly = true)
    public List<ClasseVue> lister(UUID anneeId) {
        annees.charger(anneeId);
        Map<UUID, Filiere> parId = filieres.findAll().stream()
                .collect(Collectors.toMap(Filiere::getId, Function.identity()));
        return classes.findByAnneeIdOrderByCodeAsc(anneeId).stream().map(c -> vue(c, parId.get(c.getFiliereId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClasseVue trouver(UUID classeId) {
        Classe classe = charger(classeId);
        return vue(classe, chargerFiliere(classe.getFiliereId()));
    }

    @Transactional
    public ClasseVue creer(UUID anneeId, UUID filiereId, String codeSaisi, String niveau, Short effectifMax) {
        AnneeScolaire annee = annees.chargerModifiable(anneeId);
        Filiere filiere = chargerFiliere(filiereId);
        String code = codeSaisi.trim();
        if (classes.existsByAnneeIdAndCode(anneeId, code)) {
            throw new RegleMetierException("CODE_EXISTANT", "La classe " + code + " existe déjà pour cette année");
        }
        Classe classe = classes.save(new Classe(anneeId, filiereId, code, niveau.trim(), effectifMax));
        audit.enregistrer("CLASSE_CREEE", annee.getLibelle() + " / " + code, null);
        return vue(classe, filiere);
    }

    /** La filière (donc le profil) d'une classe ne change que pendant la préparation de l'année. */
    @Transactional
    public ClasseVue modifier(UUID classeId, UUID filiereId, String codeSaisi, String niveau, Short effectifMax) {
        Classe classe = charger(classeId);
        AnneeScolaire annee = annees.chargerModifiable(classe.getAnneeId());
        if (!classe.getFiliereId().equals(filiereId) && annee.getEtat() != EtatAnnee.PREPARATION) {
            throw new RegleMetierException("ANNEE_EN_COURS",
                    "La filière d'une classe ne peut changer que pendant la préparation de l'année");
        }
        String code = codeSaisi.trim();
        if (!code.equals(classe.getCode()) && classes.existsByAnneeIdAndCode(classe.getAnneeId(), code)) {
            throw new RegleMetierException("CODE_EXISTANT", "La classe " + code + " existe déjà pour cette année");
        }
        Filiere filiere = chargerFiliere(filiereId);
        classe.modifier(filiereId, code, niveau.trim(), effectifMax);
        return vue(classe, filiere);
    }

    /** Suppression possible uniquement pendant la préparation de l'année. */
    @Transactional
    public void supprimer(UUID classeId) {
        Classe classe = charger(classeId);
        AnneeScolaire annee = annees.chargerModifiable(classe.getAnneeId());
        if (annee.getEtat() != EtatAnnee.PREPARATION) {
            throw new RegleMetierException("ANNEE_EN_COURS",
                    "Une classe ne peut être supprimée que pendant la préparation de l'année");
        }
        if (occupation.aDesInscrits(classeId)) {
            throw new RegleMetierException("CLASSE_NON_VIDE",
                    "Des élèves sont inscrits dans la classe " + classe.getCode() + " : changez-les de classe d'abord");
        }
        classes.delete(classe);
        audit.enregistrer("CLASSE_SUPPRIMEE", annee.getLibelle() + " / " + classe.getCode(), null);
    }

    @Transactional(readOnly = true)
    public List<MatiereDeClasseVue> matieres(UUID classeId) {
        charger(classeId);
        Map<UUID, Matiere> parId = matieres.findAll().stream()
                .collect(Collectors.toMap(Matiere::getId, Function.identity()));
        return matieresDeClasse.findByClasseId(classeId).stream()
                .map(cm -> vue(cm, parId.get(cm.getMatiereId())))
                .sorted((a, b) -> a.matiereLibelle().compareToIgnoreCase(b.matiereLibelle()))
                .toList();
    }

    /** Ajoute la matière à la classe, ou met à jour son coefficient, son groupe et ses volumes. */
    @Transactional
    public MatiereDeClasseVue definirMatiere(UUID classeId, UUID matiereId, BigDecimal coefficient, String groupe,
            BigDecimal volumeHebdo, BigDecimal volumeTotal) {
        Classe classe = charger(classeId);
        annees.chargerModifiable(classe.getAnneeId());
        Matiere matiere = matieres.findById(matiereId)
                .orElseThrow(() -> new RessourceIntrouvableException("Matière introuvable"));
        if (!matiere.isActif()) {
            throw new RegleMetierException("MATIERE_INACTIVE", "La matière " + matiere.getCode() + " est désactivée");
        }
        ProfilVue profil = profils.trouver(chargerFiliere(classe.getFiliereId()).getProfilId());
        String groupeNettoye = groupe == null || groupe.isBlank() ? null : groupe.trim();
        if (profil.exigeGroupesDeMatieres() && groupeNettoye == null) {
            throw new RegleMetierException("GROUPE_OBLIGATOIRE", "Le profil « " + profil.libelle()
                    + " » calcule des moyennes par groupe : indiquez le groupe de la matière");
        }
        if (profil.decoupage() == Decoupage.MODULE && volumeTotal == null) {
            throw new RegleMetierException("VOLUME_TOTAL_OBLIGATOIRE", "Le profil « " + profil.libelle()
                    + " » est organisé en modules : indiquez la durée totale en heures");
        }
        ClasseMatiere cm = matieresDeClasse.findByClasseIdAndMatiereId(classeId, matiereId)
                .orElseGet(() -> new ClasseMatiere(classeId, matiereId));
        cm.definir(coefficient, groupeNettoye, volumeHebdo, volumeTotal);
        return vue(matieresDeClasse.save(cm), matiere);
    }

    @Transactional
    public void retirerMatiere(UUID classeId, UUID matiereId) {
        Classe classe = charger(classeId);
        annees.chargerModifiable(classe.getAnneeId());
        ClasseMatiere cm = matieresDeClasse.findByClasseIdAndMatiereId(classeId, matiereId)
                .orElseThrow(() -> new RessourceIntrouvableException("Cette matière n'est pas enseignée dans la classe"));
        matieresDeClasse.delete(cm);
    }

    // ------------------------------------------------------------------
    // Enseignants affectés (le module Enseignants vérifie l'engagement ;
    // la base garantit qu'il appartient à cet établissement)
    // ------------------------------------------------------------------

    /** Affecte l'enseignant (engagement) à la matière de la classe ; null retire l'affectation. */
    @Transactional
    public MatiereDeClasseVue affecterEnseignant(UUID classeId, UUID matiereId, UUID engagementId) {
        Classe classe = charger(classeId);
        AnneeScolaire annee = annees.chargerModifiable(classe.getAnneeId());
        ClasseMatiere cm = matieresDeClasse.findByClasseIdAndMatiereId(classeId, matiereId)
                .orElseThrow(() -> new RessourceIntrouvableException("Cette matière n'est pas enseignée dans la classe"));
        Matiere matiere = matieres.findById(matiereId)
                .orElseThrow(() -> new RessourceIntrouvableException("Matière introuvable"));
        cm.affecter(engagementId);
        audit.enregistrer(engagementId != null ? "ENSEIGNANT_AFFECTE" : "AFFECTATION_RETIREE",
                annee.getLibelle() + " / " + classe.getCode() + " / " + matiere.getCode(),
                engagementId != null ? Map.of("engagement", engagementId) : null);
        return vue(cm, matiere);
    }

    /** Matières assurées par un engagement pendant une année. */
    @Transactional(readOnly = true)
    public List<AffectationVue> affectations(UUID engagementId, UUID anneeId) {
        annees.charger(anneeId);
        return affectationsVues(matieresDeClasse.affectationsDe(engagementId, anneeId));
    }

    /** Matières d'une année qui n'ont pas encore d'enseignant. */
    @Transactional(readOnly = true)
    public List<AffectationVue> matieresSansEnseignant(UUID anneeId) {
        annees.charger(anneeId);
        return affectationsVues(matieresDeClasse.sansEnseignant(anneeId));
    }

    /** L'engagement assure-t-il au moins une matière dans cette classe ? */
    @Transactional(readOnly = true)
    public boolean estAffecte(UUID engagementId, UUID classeId) {
        return matieresDeClasse.existsByClasseIdAndEngagementId(classeId, engagementId);
    }

    /**
     * Fin d'un engagement : retire l'enseignant des classes des années en
     * préparation ou en cours (les années clôturées gardent l'historique).
     */
    @Transactional
    public int libererEnseignant(UUID engagementId) {
        List<ClasseMatiere> affectees = matieresDeClasse.affecteesDansLesAnnees(engagementId,
                List.of(EtatAnnee.PREPARATION, EtatAnnee.ACTIVE));
        affectees.forEach(cm -> cm.affecter(null));
        return affectees.size();
    }

    private List<AffectationVue> affectationsVues(List<Object[]> lignes) {
        Map<UUID, Matiere> parId = matieres.findAll().stream()
                .collect(Collectors.toMap(Matiere::getId, Function.identity()));
        return lignes.stream()
                .map(ligne -> {
                    ClasseMatiere cm = (ClasseMatiere) ligne[0];
                    Classe c = (Classe) ligne[1];
                    Matiere m = parId.get(cm.getMatiereId());
                    return new AffectationVue(c.getId(), c.getCode(), m.getId(), m.getCode(), m.getLibelle(),
                            cm.getVolumeHebdo(), cm.getVolumeTotal(), cm.getEngagementId());
                })
                .toList();
    }

    private Classe charger(UUID classeId) {
        return classes.findById(classeId).orElseThrow(() -> new RessourceIntrouvableException("Classe introuvable"));
    }

    private Filiere chargerFiliere(UUID filiereId) {
        return filieres.findById(filiereId).orElseThrow(() -> new RessourceIntrouvableException("Filière introuvable"));
    }

    private static ClasseVue vue(Classe c, Filiere f) {
        return new ClasseVue(c.getId(), c.getAnneeId(), c.getFiliereId(), f != null ? f.getCode() : null,
                f != null ? f.getProfilId() : null, c.getCode(), c.getNiveau(), c.getEffectifMax());
    }

    private static MatiereDeClasseVue vue(ClasseMatiere cm, Matiere m) {
        return new MatiereDeClasseVue(cm.getId(), cm.getMatiereId(), m.getCode(), m.getLibelle(), m.getType(),
                cm.getCoefficient(), cm.getGroupe(), cm.getVolumeHebdo(), cm.getVolumeTotal(), cm.getEngagementId());
    }
}
