package bf.edutech.plateforme.passage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import bf.edutech.plateforme.eleves.Vues.NonReinscrit;
import bf.edutech.plateforme.eleves.Vues.ResultatReinscription;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Plan de passage (v0.45) : à partir des décisions validées des conseils de classe, la plateforme propose
 * la classe de l'année suivante de chaque élève — les admis au niveau supérieur de leur filière, les
 * redoublants au même niveau — en équilibrant les classes parallèles (effectif, filles et garçons,
 * redoublants). Exclus, orientés, certifiés et admis en fin de cycle sortent de la liste. La direction
 * ajuste le plan puis le confirme : les réinscriptions se font en une fois.
 */
@Service
public class PlanPassageService {

    /** Pourquoi un élève n'a pas de classe proposée. */
    public enum Sortie {
        EXCLU, ORIENTE, CERTIFIE, FIN_DE_CYCLE, SANS_CLASSE_CIBLE
    }

    public record ClasseCibleVue(UUID id, String code, String niveau, String filiereCode, Short effectifMax,
            int dejaInscrits) {
    }

    /**
     * Un élève du plan. {@code classeCibleId} : classe proposée (ou null) ; {@code redoublant} : il redouble
     * l'an prochain ; {@code dejaInscrit} : déjà inscrit pour l'année suivante (classe {@code classeActuelle}).
     */
    public record LignePlan(UUID inscriptionId, UUID eleveId, String matricule, String nom, String prenoms,
            String sexe, UUID classeSourceId, String classeSourceCode, Decision decision, BigDecimal moyenneAnnuelle,
            boolean redoublant, UUID classeCibleId, Sortie sortie, String dejaInscrit) {
    }

    public record PlanVue(AnneeVue annee, AnneeVue anneeSuivante, List<String> classesNonValidees,
            List<ClasseCibleVue> classesCibles, List<LignePlan> eleves) {
    }

    public record Affectation(UUID inscriptionId, UUID classeCibleId) {
    }

    public record ResultatPlan(int reinscrits, int redoublants, List<NonReinscrit> nonReinscrits) {
    }

    private final DecisionRepository decisions;
    private final AnneesService annees;
    private final ClassesService classes;
    private final InscriptionsService inscriptions;
    private final AuditService audit;

    PlanPassageService(DecisionRepository decisions, AnneesService annees, ClassesService classes,
            InscriptionsService inscriptions, AuditService audit) {
        this.decisions = decisions;
        this.annees = annees;
        this.classes = classes;
        this.inscriptions = inscriptions;
        this.audit = audit;
    }

    /** L'année qui suit {@code annee} (en préparation ou active), si elle a été créée. */
    @Transactional(readOnly = true)
    public Optional<AnneeVue> anneeSuivante(AnneeVue annee) {
        return annees.lister().stream()
                .filter(a -> a.debut().isAfter(annee.fin()))
                .filter(a -> a.etat() == EtatAnnee.PREPARATION || a.etat() == EtatAnnee.ACTIVE)
                .min(Comparator.comparing(AnneeVue::debut));
    }

    /** Propose la classe de l'année suivante de chaque élève dont la décision est validée. */
    @Transactional(readOnly = true)
    public PlanVue proposer(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        AnneeVue annee = annees.trouver(anneeId);
        AnneeVue suivante = anneeSuivante(annee).orElseThrow(() -> new RegleMetierException("ANNEE_SUIVANTE_ABSENTE",
                "Créez d'abord l'année qui suit " + annee.libelle() + " (étape « Année suivante »)"));

        List<ClasseVue> sources = classes.lister(anneeId);
        Map<UUID, List<DecisionFinAnnee>> parClasse = sources.isEmpty() ? Map.of()
                : decisions.findByClasseIdIn(sources.stream().map(ClasseVue::id).toList()).stream()
                        .collect(Collectors.groupingBy(DecisionFinAnnee::getClasseId));
        List<String> nonValidees = new ArrayList<>();

        // Classes de l'année suivante et élèves qui y sont déjà inscrits
        List<ClasseVue> cibles = classes.lister(suivante.id());
        Map<UUID, Integer> effectifs = new HashMap<>();
        Map<UUID, String> dejaInscrits = new HashMap<>();          // élève -> classe de l'année suivante
        for (ClasseVue c : cibles) {
            List<InscriptionVue> l = inscriptions.listerParClasse(c.id(), false);
            effectifs.put(c.id(), l.size());
            l.forEach(i -> dejaInscrits.put(i.eleveId(), c.code()));
        }

        List<LignePlan> lignes = new ArrayList<>();
        // Élèves à placer, par groupe de classes cibles (filière + niveau)
        Map<String, List<Candidat>> groupes = new LinkedHashMap<>();
        Map<String, List<ClasseVue>> classesDuGroupe = new HashMap<>();
        for (ClasseVue source : sources.stream().sorted(Comparator.comparing(ClasseVue::code)).toList()) {
            List<DecisionFinAnnee> ds = parClasse.getOrDefault(source.id(), List.of());
            if (ds.isEmpty() || !ds.stream().allMatch(DecisionFinAnnee::estValidee)) {
                nonValidees.add(source.code());
                continue;
            }
            Map<UUID, InscriptionVue> eleves = inscriptions.listerParClasse(source.id(), false).stream()
                    .collect(Collectors.toMap(InscriptionVue::id, Function.identity()));
            List<ClasseVue> filiere = cibles.stream().filter(c -> c.filiereId().equals(source.filiereId())).toList();
            Optional<String> niveauSuperieur = Niveaux.suivant(source.niveau(),
                    filiere.stream().map(ClasseVue::niveau).distinct().toList());
            for (DecisionFinAnnee d : ds) {
                InscriptionVue i = eleves.get(d.getInscriptionId());
                if (i == null) {
                    continue;                                   // sorti de l'établissement depuis le conseil
                }
                Decision decision = d.getDecision();
                boolean redouble = decision == Decision.REDOUBLE || decision == Decision.NON_CERTIFIE;
                Sortie sortie = switch (decision) {
                    case EXCLU -> Sortie.EXCLU;
                    case ORIENTE -> Sortie.ORIENTE;
                    case CERTIFIE -> Sortie.CERTIFIE;
                    default -> null;
                };
                String niveauCible = null;
                if (sortie == null && redouble) {
                    niveauCible = filiere.stream().map(ClasseVue::niveau).filter(n -> Niveaux.meme(n, source.niveau()))
                            .findFirst().orElse(null);
                    if (niveauCible == null) {
                        sortie = Sortie.SANS_CLASSE_CIBLE;
                    }
                } else if (sortie == null) {
                    niveauCible = niveauSuperieur.orElse(null);
                    if (niveauCible == null) {
                        sortie = Niveaux.position(source.niveau()).isPresent() ? Sortie.FIN_DE_CYCLE
                                : Sortie.SANS_CLASSE_CIBLE;
                    }
                }
                String deja = dejaInscrits.get(i.eleveId());
                Candidat cand = new Candidat(i, source, d, redouble, sortie, deja);
                if (sortie == null && deja == null) {
                    String cle = source.filiereId() + "|" + niveauCible;
                    final String n = niveauCible;
                    classesDuGroupe.computeIfAbsent(cle, k -> filiere.stream()
                            .filter(c -> Niveaux.meme(c.niveau(), n)).toList());
                    groupes.computeIfAbsent(cle, k -> new ArrayList<>()).add(cand);
                } else {
                    lignes.add(cand.ligne(null));
                }
            }
        }

        // Répartition équilibrée dans les classes parallèles de chaque groupe
        for (Map.Entry<String, List<Candidat>> g : groupes.entrySet()) {
            Map<UUID, UUID> affectation = repartir(g.getValue(), classesDuGroupe.get(g.getKey()), effectifs);
            for (Candidat c : g.getValue()) {
                lignes.add(c.ligne(affectation.get(c.inscription.id())));
            }
        }
        lignes.sort(Comparator.comparing(LignePlan::classeSourceCode).thenComparing(LignePlan::nom)
                .thenComparing(LignePlan::prenoms));
        List<ClasseCibleVue> vuesCibles = cibles.stream()
                .sorted(Comparator.comparing(ClasseVue::code))
                .map(c -> new ClasseCibleVue(c.id(), c.code(), c.niveau(), c.filiereCode(), c.effectifMax(),
                        effectifs.getOrDefault(c.id(), 0)))
                .toList();
        return new PlanVue(annee, suivante, nonValidees, vuesCibles, lignes);
    }

    /**
     * Réinscrit les élèves dans les classes choisies (le plan, ajusté par la direction). Les admis entrent
     * sans mention, les redoublants et non certifiés avec la mention redoublant. Un exclu n'est jamais
     * réinscrit ; un orienté ou un certifié peut l'être si la direction le place explicitement.
     */
    @Transactional
    public ResultatPlan appliquer(UUID anneeId, List<Affectation> affectations, boolean conserverStatutBourse) {
        UtilisateurConnecte.etablissementActif();
        AnneeVue annee = annees.trouver(anneeId);
        AnneeVue suivante = anneeSuivante(annee).orElseThrow(() -> new RegleMetierException("ANNEE_SUIVANTE_ABSENTE",
                "Créez d'abord l'année qui suit " + annee.libelle()));
        if (affectations == null || affectations.isEmpty()) {
            throw new IllegalArgumentException("Aucun élève à réinscrire");
        }
        Set<UUID> classesSuivantes = classes.lister(suivante.id()).stream().map(ClasseVue::id)
                .collect(Collectors.toSet());
        Set<UUID> classesSources = classes.lister(anneeId).stream().map(ClasseVue::id).collect(Collectors.toSet());
        Map<UUID, DecisionFinAnnee> parInscription = decisions.findByInscriptionIdIn(
                affectations.stream().map(Affectation::inscriptionId).toList()).stream()
                .filter(d -> classesSources.contains(d.getClasseId()))
                .collect(Collectors.toMap(DecisionFinAnnee::getInscriptionId, Function.identity(), (a, b) -> a));

        // Regroupement par classe cible et par mention redoublant
        Map<UUID, List<UUID>> admis = new LinkedHashMap<>();
        Map<UUID, List<UUID>> redoublants = new LinkedHashMap<>();
        List<NonReinscrit> refus = new ArrayList<>();
        Set<UUID> vus = new HashSet<>();
        for (Affectation a : affectations) {
            if (a == null || a.inscriptionId() == null || a.classeCibleId() == null || !vus.add(a.inscriptionId())) {
                throw new IllegalArgumentException("Chaque élève figure une seule fois, avec sa classe cible");
            }
            if (!classesSuivantes.contains(a.classeCibleId())) {
                throw new IllegalArgumentException("La classe cible doit appartenir à l'année " + suivante.libelle());
            }
            DecisionFinAnnee d = parInscription.get(a.inscriptionId());
            if (d == null || !d.estValidee()) {
                refus.add(new NonReinscrit(a.inscriptionId(), null, "Décision de fin d'année non validée"));
                continue;
            }
            if (d.getDecision() == Decision.EXCLU) {
                refus.add(new NonReinscrit(a.inscriptionId(), null, "Élève exclu par le conseil de classe"));
                continue;
            }
            boolean redouble = d.getDecision() == Decision.REDOUBLE || d.getDecision() == Decision.NON_CERTIFIE;
            (redouble ? redoublants : admis).computeIfAbsent(a.classeCibleId(), k -> new ArrayList<>())
                    .add(a.inscriptionId());
        }
        int reinscrits = 0;
        int nbRedoublants = 0;
        for (Map.Entry<UUID, List<UUID>> e : admis.entrySet()) {
            ResultatReinscription r = inscriptions.reinscrire(e.getKey(), e.getValue(), false, conserverStatutBourse);
            reinscrits += r.reinscrits();
            refus.addAll(r.nonReinscrits());
        }
        for (Map.Entry<UUID, List<UUID>> e : redoublants.entrySet()) {
            ResultatReinscription r = inscriptions.reinscrire(e.getKey(), e.getValue(), true, conserverStatutBourse);
            reinscrits += r.reinscrits();
            nbRedoublants += r.reinscrits();
            refus.addAll(r.nonReinscrits());
        }
        audit.enregistrer("PLAN_PASSAGE_APPLIQUE", annee.libelle() + " → " + suivante.libelle(),
                Map.of("reinscrits", reinscrits, "redoublants", nbRedoublants, "nonReinscrits", refus.size()));
        return new ResultatPlan(reinscrits, nbRedoublants, refus);
    }

    /**
     * Listes des classes de l'année suivante : élèves déjà inscrits et élèves proposés par le plan (pas
     * encore réinscrits), une feuille par classe ; puis les élèves qui sortent.
     */
    @Transactional(readOnly = true)
    public Tableau[] listes(UUID anneeId) {
        PlanVue plan = proposer(anneeId);
        String libelle = plan.anneeSuivante().libelle();
        Map<UUID, List<LignePlan>> proposes = plan.eleves().stream().filter(l -> l.classeCibleId() != null)
                .collect(Collectors.groupingBy(LignePlan::classeCibleId));
        List<Tableau> r = new ArrayList<>();
        for (ClasseCibleVue c : plan.classesCibles()) {
            List<Object[]> lignes = new ArrayList<>();
            for (InscriptionVue i : inscriptions.listerParClasse(c.id(), false)) {
                lignes.add(new Object[] { i.nom() + " " + i.prenoms(), i.sexe() == null ? "" : i.sexe().name(),
                        i.matricule(), "", i.redoublant() ? "Redoublant" : "", "Inscrit" });
            }
            for (LignePlan l : proposes.getOrDefault(c.id(), List.of())) {
                lignes.add(new Object[] { l.nom() + " " + l.prenoms(), l.sexe() == null ? "" : l.sexe(), l.matricule(),
                        l.classeSourceCode(), l.redoublant() ? "Redoublant" : "", "Proposé" });
            }
            if (lignes.isEmpty()) {
                continue;
            }
            lignes.sort(Comparator.comparing(o -> String.valueOf(o[0]), String.CASE_INSENSITIVE_ORDER));
            long filles = lignes.stream().filter(o -> "F".equals(o[1])).count();
            Tableau t = new Tableau(c.code(), "Classe de " + c.code() + " — année " + libelle)
                    .sousTitre(lignes.size() + " élève(s) : " + filles + " fille(s), " + (lignes.size() - filles)
                            + " garçon(s)" + (c.effectifMax() != null ? " · effectif maximal " + c.effectifMax() : ""))
                    .colonnes(Colonne.nombre("N°", 0.5f), Colonne.texte("Nom et prénoms", 3), Colonne.texte("Sexe", 0.6f),
                            Colonne.texte("Matricule", 1.4f), Colonne.texte("Classe d'origine", 1.2f),
                            Colonne.texte("Mention", 1.1f), Colonne.texte("Statut", 0.9f))
                    .portrait();
            int n = 1;
            for (Object[] o : lignes) {
                t.ligne(n++, o[0], o[1], o[2], o[3], o[4], o[5]);
            }
            t.note("« Proposé » : place prévue par le plan de passage, pas encore confirmée.");
            r.add(t);
        }
        List<LignePlan> sortants = plan.eleves().stream().filter(l -> l.sortie() != null && l.dejaInscrit() == null).toList();
        if (!sortants.isEmpty()) {
            Tableau t = new Tableau("Sortants", "Élèves sans classe en " + libelle)
                    .sousTitre(sortants.size() + " élève(s) : exclus, orientés, certifiés, fin de cycle ou à placer")
                    .colonnes(Colonne.nombre("N°", 0.5f), Colonne.texte("Nom et prénoms", 3), Colonne.texte("Matricule", 1.4f),
                            Colonne.texte("Classe", 1.1f), Colonne.texte("Décision", 1.6f), Colonne.texte("Motif", 1.6f))
                    .portrait();
            int n = 1;
            for (LignePlan l : sortants) {
                t.ligne(n++, l.nom() + " " + l.prenoms(), l.matricule(), l.classeSourceCode(),
                        l.decision() == null ? "" : l.decision().libelle(), libelleSortie(l.sortie()));
            }
            r.add(t);
        }
        if (r.isEmpty()) {
            r.add(new Tableau("Plan", "Plan de passage — " + libelle).colonnes(Colonne.texte("Information", 4))
                    .ligne("Aucune décision validée ni inscription pour l'année suivante."));
        }
        return r.toArray(Tableau[]::new);
    }

    static String libelleSortie(Sortie s) {
        return switch (s) {
            case EXCLU -> "Exclu(e)";
            case ORIENTE -> "Orienté(e) : à réinscrire à la main";
            case CERTIFIE -> "Certifié(e) : fin de formation";
            case FIN_DE_CYCLE -> "Fin de cycle (dernier niveau de la filière)";
            case SANS_CLASSE_CIBLE -> "Aucune classe de ce niveau l'an prochain : à placer";
        };
    }

    // ------------------------------------------------------------------ répartition

    private static final class Candidat {
        final InscriptionVue inscription;
        final ClasseVue source;
        final DecisionFinAnnee decision;
        final boolean redouble;
        final Sortie sortie;
        final String deja;

        Candidat(InscriptionVue inscription, ClasseVue source, DecisionFinAnnee decision, boolean redouble,
                Sortie sortie, String deja) {
            this.inscription = inscription;
            this.source = source;
            this.decision = decision;
            this.redouble = redouble;
            this.sortie = sortie;
            this.deja = deja;
        }

        String categorie() {
            return (inscription.sexe() == null ? "?" : inscription.sexe().name()) + (redouble ? "R" : "");
        }

        LignePlan ligne(UUID cible) {
            InscriptionVue i = inscription;
            return new LignePlan(i.id(), i.eleveId(), i.matricule(), i.nom(), i.prenoms(),
                    i.sexe() == null ? null : i.sexe().name(), source.id(), source.code(), decision.getDecision(),
                    decision.getMoyenneAnnuelle(), redouble, cible, sortie, deja);
        }
    }

    /**
     * Répartition équilibrée : par catégorie (sexe, redoublant), les élèves vont tour à tour dans la classe
     * qui en compte le moins, puis la moins chargée ; une classe pleine (effectif maximal) n'est choisie que
     * si toutes le sont. Les élèves déjà inscrits comptent dans l'effectif. Résultat déterministe.
     */
    static Map<UUID, UUID> repartir(List<Candidat> candidats, List<ClasseVue> classesGroupe, Map<UUID, Integer> effectifs) {
        Map<UUID, UUID> resultat = new HashMap<>();
        if (classesGroupe == null || classesGroupe.isEmpty()) {
            return resultat;
        }
        List<ClasseVue> ordre = classesGroupe.stream().sorted(Comparator.comparing(ClasseVue::code)).toList();
        Map<UUID, Integer> total = new HashMap<>();
        ordre.forEach(c -> total.put(c.id(), effectifs.getOrDefault(c.id(), 0)));
        Map<String, Integer> parCategorie = new HashMap<>();
        List<Candidat> tries = candidats.stream()
                .sorted(Comparator.comparing(Candidat::categorie)
                        .thenComparing(c -> c.decision.getMoyenneAnnuelle(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(c -> c.inscription.nom()).thenComparing(c -> c.inscription.prenoms()))
                .toList();
        for (Candidat c : tries) {
            String cat = c.categorie();
            ClasseVue choisie = null;
            boolean choisiePleine = true;
            for (ClasseVue k : ordre) {
                boolean pleine = k.effectifMax() != null && total.get(k.id()) >= k.effectifMax();
                if (choisie == null) {
                    choisie = k;
                    choisiePleine = pleine;
                    continue;
                }
                int a = parCategorie.getOrDefault(k.id() + cat, 0);
                int b = parCategorie.getOrDefault(choisie.id() + cat, 0);
                boolean mieux = choisiePleine && !pleine
                        || pleine == choisiePleine && (a < b || a == b && total.get(k.id()) < total.get(choisie.id()));
                if (mieux) {
                    choisie = k;
                    choisiePleine = pleine;
                }
            }
            resultat.put(c.inscription.id(), choisie.id());
            total.merge(choisie.id(), 1, Integer::sum);
            parCategorie.merge(choisie.id() + cat, 1, Integer::sum);
        }
        return resultat;
    }
}
