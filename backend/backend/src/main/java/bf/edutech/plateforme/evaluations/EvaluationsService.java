package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.evaluations.ContexteClasse.Contexte;
import bf.edutech.plateforme.evaluations.Vues.EvaluationVue;
import bf.edutech.plateforme.evaluations.Vues.FeuilleNotesVue;
import bf.edutech.plateforme.evaluations.Vues.LigneNoteVue;
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Évaluations et notes. L'enseignant crée les évaluations et saisit les notes
 * des seules matières qui lui sont affectées ; le censeur et l'administrateur
 * peuvent intervenir dans toutes. Une période verrouillée ne se modifie plus.
 */
@Service
public class EvaluationsService {

    private static final BigDecimal BAREME_MAX = BigDecimal.valueOf(100);
    private static final BigDecimal POIDS_MAX = BigDecimal.valueOf(10);

    private final EvaluationRepository evaluations;
    private final NoteRepository notes;
    private final ContexteClasse contextes;
    private final AutorisationsEvaluations autorisations;
    private final InscriptionsService inscriptions;
    private final AuditService audit;
    private final Clock horloge;

    EvaluationsService(EvaluationRepository evaluations, NoteRepository notes, ContexteClasse contextes,
            AutorisationsEvaluations autorisations, InscriptionsService inscriptions, AuditService audit, Clock horloge) {
        this.evaluations = evaluations;
        this.notes = notes;
        this.contextes = contextes;
        this.autorisations = autorisations;
        this.inscriptions = inscriptions;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional
    public EvaluationVue creer(UUID classeId, UUID matiereId, UUID periodeId, String libelle, TypeEvaluation type,
            LocalDate date, BigDecimal bareme, BigDecimal poids) {
        return creer(classeId, matiereId, periodeId, libelle, type, date, bareme, poids, null);
    }

    /**
     * Création avec un identifiant choisi par l'appareil ({@code idClient}), pour
     * les évaluations créées hors connexion : un renvoi après une coupure réseau
     * retrouve l'évaluation déjà créée au lieu d'en créer une seconde. Le renvoi
     * est reconnu avant les contrôles de saisie, pour qu'une période verrouillée
     * entre-temps ne fasse pas passer une évaluation bien créée pour refusée.
     */
    @Transactional
    public EvaluationVue creer(UUID classeId, UUID matiereId, UUID periodeId, String libelle, TypeEvaluation type,
            LocalDate date, BigDecimal bareme, BigDecimal poids, UUID idClient) {
        UtilisateurConnecte.etablissementActif();
        if (idClient != null) {
            Optional<Evaluation> deja = evaluations.findById(idClient);
            if (deja.isPresent()) {
                Evaluation e = deja.get();
                if (!e.getClasseId().equals(classeId) || !e.getMatiereId().equals(matiereId)
                        || !e.getPeriodeId().equals(periodeId)) {
                    throw new RegleMetierException("IDENTIFIANT_DEJA_UTILISE",
                            "Cet identifiant d'évaluation est déjà utilisé pour une autre classe ou matière");
                }
                autorisations.verifierConsultation(classeId);
                Contexte lecture = contextes.pourLecture(classeId, periodeId);
                return vue(e, lecture, notes.findByEvaluationId(e.getId()).size());
            }
        }
        Contexte c = contextes.pourSaisie(classeId, periodeId);
        MatiereDeClasseVue matiere = matiereANotes(c, matiereId);
        autorisations.verifierSaisie(c.classe(), matiere);
        verifierDate(c, date);
        Evaluation evaluation = new Evaluation(classeId, matiereId, periodeId,
                UtilisateurConnecte.idSiConnecte().orElse(null));
        if (idClient != null) {
            evaluation.imposerIdentifiant(idClient);
        }
        evaluation.definir(libelle(libelle), type, date, bareme(bareme), poids(poids));
        evaluations.save(evaluation);
        audit.enregistrer("EVALUATION_CREEE", c.classe().code() + " / " + matiere.matiereCode(),
                Map.of("evaluation", evaluation.getId()));
        return vue(evaluation, c, 0);
    }

    @Transactional
    public EvaluationVue modifier(UUID evaluationId, String libelle, TypeEvaluation type, LocalDate date,
            BigDecimal bareme, BigDecimal poids) {
        Evaluation evaluation = charger(evaluationId);
        Contexte c = contextes.pourSaisie(evaluation.getClasseId(), evaluation.getPeriodeId());
        autorisations.verifierSaisie(c.classe(), c.matiere(evaluation.getMatiereId()));
        verifierDate(c, date);
        BigDecimal nouveauBareme = bareme(bareme);
        List<Note> existantes = notes.findByEvaluationId(evaluationId);
        existantes.stream().map(Note::getValeur).filter(v -> v != null && v.compareTo(nouveauBareme) > 0).findAny()
                .ifPresent(v -> {
                    throw new RegleMetierException("BAREME_INFERIEUR_AUX_NOTES",
                            "Des notes dépassent le nouveau barème (" + v.toPlainString() + ")");
                });
        evaluation.definir(libelle(libelle), type, date, nouveauBareme, poids(poids));
        audit.enregistrer("EVALUATION_MODIFIEE", evaluationId.toString(), null);
        return vue(evaluation, c, existantes.size());
    }

    @Transactional
    public void supprimer(UUID evaluationId) {
        Evaluation evaluation = charger(evaluationId);
        Contexte c = contextes.pourSaisie(evaluation.getClasseId(), evaluation.getPeriodeId());
        autorisations.verifierSaisie(c.classe(), c.matiere(evaluation.getMatiereId()));
        evaluations.delete(evaluation);
        audit.enregistrer("EVALUATION_SUPPRIMEE", c.classe().code() + " / " + evaluation.getLibelle(), null);
    }

    /** Évaluations d'une classe pour une période, avec le nombre de notes saisies. */
    @Transactional(readOnly = true)
    public List<EvaluationVue> lister(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        autorisations.verifierConsultation(classeId);
        Contexte c = contextes.pourLecture(classeId, periodeId);
        List<Evaluation> liste = evaluations.findByClasseIdAndPeriodeIdOrderByDateEvaluationAscLibelleAsc(classeId,
                periodeId);
        Map<UUID, Long> saisies = liste.isEmpty() ? Map.of()
                : notes.findByEvaluationIdIn(liste.stream().map(Evaluation::getId).toList()).stream()
                        .collect(Collectors.groupingBy(Note::getEvaluationId, Collectors.counting()));
        int effectif = inscriptions.listerParClasse(classeId, false).size();
        return liste.stream()
                .map(e -> vue(e, c, saisies.getOrDefault(e.getId(), 0L).intValue(), effectif))
                .toList();
    }

    /** Feuille de notes : tous les élèves actifs de la classe, avec leur note s'il y en a une. */
    @Transactional(readOnly = true)
    public FeuilleNotesVue feuille(UUID evaluationId) {
        Evaluation evaluation = charger(evaluationId);
        autorisations.verifierConsultation(evaluation.getClasseId());
        Contexte c = contextes.pourLecture(evaluation.getClasseId(), evaluation.getPeriodeId());
        return feuille(evaluation, c, List.of());
    }

    /**
     * Saisie (ou correction) des notes, élève par élève : idempotent, un renvoi
     * depuis un appareil hors connexion donne le même résultat. Les élèves qui
     * ne sont pas (ou plus) dans la classe sont ignorés et signalés.
     */
    @Transactional
    public FeuilleNotesVue saisir(UUID evaluationId, List<SaisieNote> saisies) {
        Evaluation evaluation = charger(evaluationId);
        Contexte c = contextes.pourSaisie(evaluation.getClasseId(), evaluation.getPeriodeId());
        autorisations.verifierSaisie(c.classe(), c.matiere(evaluation.getMatiereId()));
        Map<UUID, InscriptionVue> eleves = eleves(evaluation.getClasseId());
        Map<UUID, Note> existantes = notes.findByEvaluationId(evaluationId).stream()
                .collect(Collectors.toMap(Note::getInscriptionId, Function.identity()));
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        Instant maintenant = horloge.instant();
        List<UUID> ignorees = new ArrayList<>();
        Set<UUID> vus = new HashSet<>();
        int modifiees = 0;
        for (SaisieNote s : saisies) {
            if (s == null || s.inscriptionId() == null) {
                throw new IllegalArgumentException("Chaque note doit indiquer l'inscription de l'élève");
            }
            if (!vus.add(s.inscriptionId())) {
                throw new IllegalArgumentException("Un élève figure deux fois dans la saisie");
            }
            if (!eleves.containsKey(s.inscriptionId())) {
                ignorees.add(s.inscriptionId());
                continue;
            }
            Note existante = existantes.get(s.inscriptionId());
            if (!s.absent() && s.valeur() == null) { // ni note ni absence : effacement
                if (existante != null) {
                    notes.delete(existante);
                    existantes.remove(s.inscriptionId());
                    modifiees++;
                }
                continue;
            }
            BigDecimal valeur = s.absent() ? null : valeurValide(s.valeur(), evaluation.getBareme());
            if (existante != null && existante.identique(valeur, s.absent())) {
                continue;
            }
            Note note = existante != null ? existante : new Note(evaluationId, s.inscriptionId());
            note.definir(valeur, s.absent(), moi, maintenant);
            if (existante == null) {
                notes.save(note);
                existantes.put(s.inscriptionId(), note);
            }
            modifiees++;
        }
        if (modifiees > 0) {
            audit.enregistrer("NOTES_SAISIES", c.classe().code() + " / " + evaluation.getLibelle(),
                    Map.of("evaluation", evaluationId, "modifiees", modifiees));
        }
        return feuille(evaluation, c, ignorees);
    }

    // ------------------------------------------------------------------

    private FeuilleNotesVue feuille(Evaluation evaluation, Contexte c, List<UUID> ignorees) {
        Map<UUID, Note> parEleve = notes.findByEvaluationId(evaluation.getId()).stream()
                .collect(Collectors.toMap(Note::getInscriptionId, Function.identity()));
        List<InscriptionVue> eleves = inscriptions.listerParClasse(evaluation.getClasseId(), false);
        List<LigneNoteVue> lignes = eleves.stream()
                .map(i -> {
                    Note n = parEleve.get(i.id());
                    return new LigneNoteVue(i.id(), i.matricule(), i.nom(), i.prenoms(), n != null ? n.getValeur() : null,
                            n != null && n.isAbsent());
                })
                .toList();
        int saisies = (int) eleves.stream().filter(i -> parEleve.containsKey(i.id())).count();
        return new FeuilleNotesVue(vue(evaluation, c, saisies, eleves.size()), lignes, ignorees);
    }

    private Map<UUID, InscriptionVue> eleves(UUID classeId) {
        return inscriptions.listerParClasse(classeId, false).stream()
                .collect(Collectors.toMap(InscriptionVue::id, Function.identity()));
    }

    private static MatiereDeClasseVue matiereANotes(Contexte c, UUID matiereId) {
        MatiereDeClasseVue matiere = c.matiere(matiereId);
        if (Contexte.estModule(matiere)) {
            throw new RegleMetierException("MODULE_EN_COMPETENCES", matiere.matiereLibelle()
                    + " est un module de compétences : évaluez les compétences de son référentiel");
        }
        return matiere;
    }

    private static void verifierDate(Contexte c, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("La date de l'évaluation est obligatoire");
        }
        if (date.isBefore(c.periode().debut()) || date.isAfter(c.periode().fin())) {
            throw new RegleMetierException("DATE_HORS_PERIODE", "La date doit être comprise dans la période « "
                    + c.periode().libelle() + " » (" + c.periode().debut() + " au " + c.periode().fin() + ")");
        }
    }

    private static String libelle(String saisie) {
        if (saisie == null || saisie.isBlank()) {
            throw new IllegalArgumentException("Le libellé de l'évaluation est obligatoire");
        }
        String libelle = saisie.trim();
        if (libelle.length() > 80) {
            throw new IllegalArgumentException("Le libellé dépasse 80 caractères");
        }
        return libelle;
    }

    private static BigDecimal bareme(BigDecimal saisie) {
        BigDecimal bareme = saisie != null ? saisie : BigDecimal.valueOf(20);
        if (bareme.signum() <= 0 || bareme.compareTo(BAREME_MAX) > 0 || bareme.scale() > 2) {
            throw new IllegalArgumentException("Barème invalide (entre 0 et 100, deux décimales au plus)");
        }
        return bareme;
    }

    private static BigDecimal poids(BigDecimal saisie) {
        BigDecimal poids = saisie != null ? saisie : BigDecimal.ONE;
        if (poids.signum() <= 0 || poids.compareTo(POIDS_MAX) > 0 || poids.scale() > 2) {
            throw new IllegalArgumentException("Poids invalide (entre 0 et 10, deux décimales au plus)");
        }
        return poids;
    }

    private static BigDecimal valeurValide(BigDecimal valeur, BigDecimal bareme) {
        if (valeur.signum() < 0 || valeur.compareTo(bareme) > 0) {
            throw new IllegalArgumentException("Note " + valeur.toPlainString() + " hors barème (0 à "
                    + bareme.stripTrailingZeros().toPlainString() + ")");
        }
        if (valeur.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Une note a deux décimales au plus");
        }
        return valeur;
    }

    private Evaluation charger(UUID evaluationId) {
        UtilisateurConnecte.etablissementActif();
        return evaluations.findById(evaluationId)
                .orElseThrow(() -> new RessourceIntrouvableException("Évaluation introuvable"));
    }

    private EvaluationVue vue(Evaluation e, Contexte c, int notesSaisies) {
        return vue(e, c, notesSaisies, inscriptions.listerParClasse(e.getClasseId(), false).size());
    }

    private static EvaluationVue vue(Evaluation e, Contexte c, int notesSaisies, int effectif) {
        MatiereDeClasseVue m = c.matiere(e.getMatiereId());
        return new EvaluationVue(e.getId(), e.getClasseId(), e.getMatiereId(), m.matiereCode(), e.getPeriodeId(),
                e.getLibelle(), e.getType(), e.getDateEvaluation(), e.getBareme(), e.getPoids(), notesSaisies,
                effectif);
    }
}
