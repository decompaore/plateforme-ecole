package bf.edutech.plateforme.evaluations;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.MatieresService;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereVue;
import bf.edutech.plateforme.evaluations.ContexteClasse.Contexte;
import bf.edutech.plateforme.evaluations.Vues.CompetenceVue;
import bf.edutech.plateforme.evaluations.Vues.GrilleCompetencesVue;
import bf.edutech.plateforme.evaluations.Vues.LigneCompetencesVue;
import bf.edutech.plateforme.evaluations.Vues.NiveauVue;
import bf.edutech.plateforme.evaluations.Vues.SaisieCompetence;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Référentiels de compétences des modules et évaluation des apprenants. */
@Service
public class CompetencesService {

    private final CompetenceRepository competences;
    private final ResultatCompetenceRepository resultats;
    private final MatieresService matieres;
    private final ContexteClasse contextes;
    private final AutorisationsEvaluations autorisations;
    private final InscriptionsService inscriptions;
    private final AuditService audit;
    private final Clock horloge;

    CompetencesService(CompetenceRepository competences, ResultatCompetenceRepository resultats,
            MatieresService matieres, ContexteClasse contextes, AutorisationsEvaluations autorisations,
            InscriptionsService inscriptions, AuditService audit, Clock horloge) {
        this.competences = competences;
        this.resultats = resultats;
        this.matieres = matieres;
        this.contextes = contextes;
        this.autorisations = autorisations;
        this.inscriptions = inscriptions;
        this.audit = audit;
        this.horloge = horloge;
    }

    /** Ajoute une compétence au référentiel d'un module. */
    @Transactional
    public CompetenceVue creer(UUID matiereId, String codeSaisi, String libelleSaisi, Integer ordre) {
        UtilisateurConnecte.etablissementActif();
        MatiereVue module = matieres.lister().stream().filter(m -> m.id().equals(matiereId)).findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Matière introuvable"));
        if (module.type() != TypeMatiere.MODULE_COMPETENCES) {
            throw new RegleMetierException("PAS_UN_MODULE", module.libelle()
                    + " n'est pas un module de compétences (type MODULE_COMPETENCES)");
        }
        if (codeSaisi == null || codeSaisi.isBlank() || libelleSaisi == null || libelleSaisi.isBlank()) {
            throw new IllegalArgumentException("Le code et le libellé de la compétence sont obligatoires");
        }
        String code = codeSaisi.trim().toUpperCase(Locale.ROOT);
        if (competences.existsByMatiereIdAndCode(matiereId, code)) {
            throw new RegleMetierException("CODE_EXISTANT", "La compétence " + code + " existe déjà dans ce module");
        }
        Competence c = competences.save(new Competence(matiereId, code, libelleSaisi.trim(),
                (short) (ordre != null ? Math.clamp(ordre, 1, 999) : 1)));
        audit.enregistrer("COMPETENCE_CREEE", module.code() + " / " + code, null);
        return vue(c);
    }

    @Transactional(readOnly = true)
    public List<CompetenceVue> lister(UUID matiereId) {
        UtilisateurConnecte.etablissementActif();
        return competences.findByMatiereIdOrderByOrdreAscCodeAsc(matiereId).stream().map(CompetencesService::vue)
                .toList();
    }

    /** Grille d'un module pour une classe et une période. */
    @Transactional(readOnly = true)
    public GrilleCompetencesVue grille(UUID classeId, UUID periodeId, UUID matiereId) {
        UtilisateurConnecte.etablissementActif();
        autorisations.verifierConsultation(classeId);
        Contexte c = contextes.pourLecture(classeId, periodeId);
        return grille(c, module(c, matiereId));
    }

    /** Saisie (idempotente) des niveaux de maîtrise ; un niveau null efface l'évaluation. */
    @Transactional
    public GrilleCompetencesVue evaluer(UUID classeId, UUID periodeId, UUID matiereId,
            List<SaisieCompetence> saisies) {
        UtilisateurConnecte.etablissementActif();
        Contexte c = contextes.pourSaisie(classeId, periodeId);
        MatiereDeClasseVue module = module(c, matiereId);
        autorisations.verifierSaisie(c.classe(), module);
        Set<UUID> referentiel = competences.findByMatiereIdOrderByOrdreAscCodeAsc(matiereId).stream()
                .filter(Competence::isActif).map(Competence::getId).collect(Collectors.toSet());
        Set<UUID> eleves = inscriptions.listerParClasse(classeId, false).stream().map(InscriptionVue::id)
                .collect(Collectors.toSet());
        Map<String, ResultatCompetence> existants = new HashMap<>();
        if (!referentiel.isEmpty()) {
            resultats.findByPeriodeIdAndCompetenceIdIn(periodeId, referentiel)
                    .forEach(r -> existants.put(cle(r.getInscriptionId(), r.getCompetenceId()), r));
        }
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        Instant maintenant = horloge.instant();
        int modifies = 0;
        Set<String> vus = new java.util.HashSet<>();
        for (SaisieCompetence s : saisies) {
            if (s == null || s.inscriptionId() == null || s.competenceId() == null) {
                throw new IllegalArgumentException("Chaque saisie indique l'apprenant et la compétence");
            }
            if (!vus.add(cle(s.inscriptionId(), s.competenceId()))) {
                throw new IllegalArgumentException("Une même compétence figure deux fois pour un apprenant");
            }
            if (!referentiel.contains(s.competenceId())) {
                throw new RegleMetierException("COMPETENCE_HORS_MODULE",
                        "Une compétence ne fait pas partie du référentiel de ce module");
            }
            if (!eleves.contains(s.inscriptionId())) {
                continue; // apprenant parti : ignoré
            }
            String cle = cle(s.inscriptionId(), s.competenceId());
            ResultatCompetence existant = existants.get(cle);
            if (s.niveau() == null) {
                if (existant != null) {
                    resultats.delete(existant);
                    existants.remove(cle);
                    modifies++;
                }
                continue;
            }
            if (existant != null && existant.getNiveau() == s.niveau()) {
                continue;
            }
            ResultatCompetence r = existant != null ? existant
                    : new ResultatCompetence(s.competenceId(), s.inscriptionId(), periodeId);
            r.definir(s.niveau(), moi, maintenant);
            if (existant == null) {
                resultats.save(r);
                existants.put(cle, r);
            }
            modifies++;
        }
        if (modifies > 0) {
            audit.enregistrer("COMPETENCES_EVALUEES", c.classe().code() + " / " + module.matiereCode(),
                    Map.of("modifications", modifies));
        }
        return grille(c, module);
    }

    // ------------------------------------------------------------------

    private GrilleCompetencesVue grille(Contexte c, MatiereDeClasseVue module) {
        List<Competence> referentiel = competences.findByMatiereIdOrderByOrdreAscCodeAsc(module.matiereId()).stream()
                .filter(Competence::isActif).toList();
        Map<String, NiveauMaitrise> niveaux = new HashMap<>();
        if (!referentiel.isEmpty()) {
            resultats.findByPeriodeIdAndCompetenceIdIn(c.periode().id(),
                    referentiel.stream().map(Competence::getId).toList())
                    .forEach(r -> niveaux.put(cle(r.getInscriptionId(), r.getCompetenceId()), r.getNiveau()));
        }
        List<LigneCompetencesVue> lignes = new ArrayList<>();
        for (InscriptionVue i : inscriptions.listerParClasse(c.classe().id(), false)) {
            List<NiveauVue> siens = referentiel.stream()
                    .map(comp -> new NiveauVue(comp.getId(), niveaux.get(cle(i.id(), comp.getId()))))
                    .toList();
            lignes.add(new LigneCompetencesVue(i.id(), i.nom(), i.prenoms(), siens));
        }
        return new GrilleCompetencesVue(module.matiereId(), c.periode().id(),
                referentiel.stream().map(CompetencesService::vue).toList(), lignes);
    }

    private static MatiereDeClasseVue module(Contexte c, UUID matiereId) {
        MatiereDeClasseVue m = c.matiere(matiereId);
        if (!Contexte.estModule(m)) {
            throw new RegleMetierException("PAS_UN_MODULE", m.matiereLibelle() + " se note, sans compétences");
        }
        return m;
    }

    private static String cle(UUID inscriptionId, UUID competenceId) {
        return inscriptionId + ":" + competenceId;
    }

    private static CompetenceVue vue(Competence c) {
        return new CompetenceVue(c.getId(), c.getMatiereId(), c.getCode(), c.getLibelle(), c.getOrdre(), c.isActif());
    }
}
