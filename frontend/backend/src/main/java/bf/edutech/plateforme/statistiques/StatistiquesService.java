package bf.edutech.plateforme.statistiques;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.enseignants.StatutEngagement;
import bf.edutech.plateforme.enseignants.TypeEngagement;
import bf.edutech.plateforme.enseignants.Vues.EnseignantVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.passage.Decision;
import bf.edutech.plateforme.passage.DecisionsService;
import bf.edutech.plateforme.passage.DecisionsService.DecisionVue;
import bf.edutech.plateforme.scolarite.SituationsService;
import bf.edutech.plateforme.scolarite.Vues.EtatClasseVue;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembreVue;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Statistiques d'une année scolaire, calculées à la demande (aucune ressaisie).
 * <ul>
 * <li>Effectifs : élèves inscrits (actifs), par niveau et par sexe ; redoublants ; nombre de classes.</li>
 * <li>Âges : âge révolu au 31 décembre de l'année de la rentrée, par niveau et par sexe.</li>
 * <li>Bourses : boursiers, semi-boursiers, non boursiers, par filière et par sexe.</li>
 * <li>Personnel : enseignants actifs (titulaires, vacataires) par sexe ; personnel administratif par fonction.</li>
 * <li>Recouvrement des frais par classe (familles et organismes) ; résultats de fin d'année par niveau.</li>
 * </ul>
 */
@Service
public class StatistiquesService {

    /** Ordre usuel des niveaux ; les autres suivent par ordre alphabétique. */
    private static final List<String> ORDRE_NIVEAUX = List.of("6e", "5e", "4e", "3e", "2nde", "1re", "tle");

    public record Compte(int garcons, int filles) {

        int total() {
            return garcons + filles;
        }

        Compte plus(Sexe sexe) {
            return sexe == Sexe.F ? new Compte(garcons, filles + 1) : new Compte(garcons + 1, filles);
        }

        static Compte vide() {
            return new Compte(0, 0);
        }
    }

    public record EffectifNiveauVue(String niveau, int classes, Compte effectif, Compte redoublants) {
    }

    public record AgeVue(String niveau, int age, Compte effectif) {
    }

    public record BourseFiliereVue(String filiere, Compte boursiers, Compte semiBoursiers, Compte nonBoursiers) {
    }

    public record PersonnelVue(Compte titulaires, Compte vacataires, int sexeNonRenseigne,
            Map<Role, Integer> administratif) {
    }

    public record RecouvrementClasseVue(String classe, long duFamilles, long payeFamilles, BigDecimal tauxFamilles,
            long duOrganismes, long payeOrganismes, BigDecimal tauxOrganismes) {
    }

    public record ResultatNiveauVue(String niveau, Compte decides, Map<Decision, Compte> parDecision,
            BigDecimal tauxAdmission) {
    }

    public record RapportVue(String etablissement, AnneeVue annee, Instant produitLe, Compte effectifTotal,
            int classes, List<EffectifNiveauVue> effectifs, List<AgeVue> ages, List<BourseFiliereVue> bourses,
            PersonnelVue personnel, List<RecouvrementClasseVue> recouvrement, RecouvrementClasseVue recouvrementTotal,
            List<ResultatNiveauVue> resultats) {
    }

    private final AnneesService annees;
    private final ClassesService classes;
    private final FilieresService filieres;
    private final InscriptionsService inscriptions;
    private final EnseignantsService enseignants;
    private final MembresService membres;
    private final SituationsService situations;
    private final DecisionsService decisions;
    private final NotificationsService notifications;
    private final Clock horloge;

    StatistiquesService(AnneesService annees, ClassesService classes, FilieresService filieres,
            InscriptionsService inscriptions, EnseignantsService enseignants, MembresService membres,
            SituationsService situations, DecisionsService decisions, NotificationsService notifications,
            Clock horloge) {
        this.annees = annees;
        this.classes = classes;
        this.filieres = filieres;
        this.inscriptions = inscriptions;
        this.enseignants = enseignants;
        this.membres = membres;
        this.situations = situations;
        this.decisions = decisions;
        this.notifications = notifications;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public RapportVue rapport(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        AnneeVue annee = annees.trouver(anneeId);
        List<ClasseVue> listeClasses = classes.lister(anneeId);
        Map<UUID, List<InscriptionVue>> elevesParClasse = new LinkedHashMap<>();
        for (ClasseVue c : listeClasses) {
            elevesParClasse.put(c.id(), inscriptions.listerParClasse(c.id(), false));
        }
        Map<UUID, ClasseVue> classeParId = listeClasses.stream()
                .collect(Collectors.toMap(ClasseVue::id, Function.identity()));
        List<InscriptionVue> tous = elevesParClasse.values().stream().flatMap(List::stream).toList();

        // Effectifs et redoublants par niveau
        Map<String, List<ClasseVue>> classesParNiveau = new TreeMap<>(StatistiquesService::comparerNiveaux);
        listeClasses.forEach(c -> classesParNiveau.computeIfAbsent(c.niveau(), k -> new ArrayList<>()).add(c));
        List<EffectifNiveauVue> effectifs = new ArrayList<>();
        classesParNiveau.forEach((niveau, cs) -> {
            Compte effectif = Compte.vide();
            Compte redoublants = Compte.vide();
            for (ClasseVue c : cs) {
                for (InscriptionVue i : elevesParClasse.get(c.id())) {
                    effectif = effectif.plus(i.sexe());
                    if (i.redoublant()) {
                        redoublants = redoublants.plus(i.sexe());
                    }
                }
            }
            effectifs.add(new EffectifNiveauVue(niveau, cs.size(), effectif, redoublants));
        });

        // Âges au 31 décembre de l'année de rentrée
        LocalDate reference = LocalDate.of(annee.debut().getYear(), 12, 31);
        Map<String, Map<Integer, Compte>> ages = new TreeMap<>(StatistiquesService::comparerNiveaux);
        for (InscriptionVue i : tous) {
            if (i.dateNaissance() == null) {
                continue;
            }
            int age = Period.between(i.dateNaissance(), reference).getYears();
            ages.computeIfAbsent(classeParId.get(i.classeId()).niveau(), k -> new TreeMap<>())
                    .merge(age, Compte.vide().plus(i.sexe()), (a, b) -> new Compte(a.garcons() + b.garcons(),
                            a.filles() + b.filles()));
        }
        List<AgeVue> lignesAges = new ArrayList<>();
        ages.forEach((niveau, parAge) -> parAge.forEach((age, compte) -> lignesAges.add(new AgeVue(niveau, age,
                compte))));

        // Bourses par filière
        Map<UUID, String> libellesFilieres = filieres.lister().stream()
                .collect(Collectors.toMap(FiliereVue::id, FiliereVue::libelle));
        Map<String, Map<StatutBourse, Compte>> bourses = new TreeMap<>();
        for (InscriptionVue i : tous) {
            String filiere = libellesFilieres.getOrDefault(classeParId.get(i.classeId()).filiereId(), "—");
            bourses.computeIfAbsent(filiere, k -> new EnumMap<>(StatutBourse.class))
                    .merge(i.statutBourse(), Compte.vide().plus(i.sexe()),
                            (a, b) -> new Compte(a.garcons() + b.garcons(), a.filles() + b.filles()));
        }
        List<BourseFiliereVue> lignesBourses = bourses.entrySet().stream()
                .map(e -> new BourseFiliereVue(e.getKey(),
                        e.getValue().getOrDefault(StatutBourse.BOURSIER, Compte.vide()),
                        e.getValue().getOrDefault(StatutBourse.SEMI_BOURSIER, Compte.vide()),
                        e.getValue().getOrDefault(StatutBourse.NON_BOURSIER, Compte.vide())))
                .toList();

        // Personnel
        Compte titulaires = Compte.vide();
        Compte vacataires = Compte.vide();
        int sansSexe = 0;
        for (EnseignantVue e : enseignants.lister(StatutEngagement.ACTIF)) {
            if (e.sexe() == null) {
                sansSexe++;
            } else if (e.type() == TypeEngagement.TITULAIRE) {
                titulaires = titulaires.plus(e.sexe());
            } else {
                vacataires = vacataires.plus(e.sexe());
            }
        }
        Map<Role, Integer> administratif = new EnumMap<>(Role.class);
        for (MembreVue m : membres.lister()) {
            if (m.actif() && m.role() != Role.ENSEIGNANT && m.role() != Role.PARENT && m.role() != Role.ELEVE) {
                administratif.merge(m.role(), 1, Integer::sum);
            }
        }

        // Recouvrement et résultats, classe par classe
        List<RecouvrementClasseVue> recouvrement = new ArrayList<>();
        long duF = 0;
        long payeF = 0;
        long duO = 0;
        long payeO = 0;
        Map<String, Map<Decision, Compte>> resultats = new TreeMap<>(StatistiquesService::comparerNiveaux);
        for (ClasseVue c : listeClasses) {
            EtatClasseVue etat = situations.etatClasse(c.id());
            recouvrement.add(new RecouvrementClasseVue(c.code(), etat.totalFamille(), etat.payeFamille(),
                    etat.tauxRecouvrementFamille(), etat.totalOrganisme(), etat.payeOrganisme(),
                    etat.tauxRecouvrementOrganisme()));
            duF += etat.totalFamille();
            payeF += etat.payeFamille();
            duO += etat.totalOrganisme();
            payeO += etat.payeOrganisme();
            Map<UUID, Sexe> sexes = elevesParClasse.get(c.id()).stream()
                    .collect(Collectors.toMap(InscriptionVue::id, InscriptionVue::sexe, (a, b) -> a, HashMap::new));
            for (DecisionVue d : decisions.lister(c.id()).eleves()) {
                Sexe sexe = sexes.get(d.inscriptionId());
                if (sexe != null && d.decision() != Decision.EN_ATTENTE_EXAMEN) {
                    resultats.computeIfAbsent(c.niveau(), k -> new EnumMap<>(Decision.class))
                            .merge(d.decision(), Compte.vide().plus(sexe),
                                    (a, b) -> new Compte(a.garcons() + b.garcons(), a.filles() + b.filles()));
                }
            }
        }
        List<ResultatNiveauVue> lignesResultats = new ArrayList<>();
        resultats.forEach((niveau, parDecision) -> {
            Compte decides = parDecision.values().stream().reduce(Compte.vide(),
                    (a, b) -> new Compte(a.garcons() + b.garcons(), a.filles() + b.filles()));
            int admis = parDecision.getOrDefault(Decision.ADMIS, Compte.vide()).total()
                    + parDecision.getOrDefault(Decision.CERTIFIE, Compte.vide()).total();
            lignesResultats.add(new ResultatNiveauVue(niveau, decides, parDecision, taux(admis, decides.total())));
        });

        Compte total = effectifs.stream().map(EffectifNiveauVue::effectif).reduce(Compte.vide(),
                (a, b) -> new Compte(a.garcons() + b.garcons(), a.filles() + b.filles()));
        return new RapportVue(notifications.nomEtablissement(), annee, horloge.instant(), total, listeClasses.size(),
                effectifs, lignesAges, lignesBourses, new PersonnelVue(titulaires, vacataires, sansSexe, administratif),
                recouvrement, new RecouvrementClasseVue("TOTAL", duF, payeF, taux(payeF, duF), duO, payeO,
                        taux(payeO, duO)),
                lignesResultats);
    }

    static BigDecimal taux(long partie, long total) {
        return total == 0 ? null
                : BigDecimal.valueOf(partie).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /** 6e, 5e, 4e, 3e, 2nde, 1re, Tle, puis les autres niveaux (CAP 1, BEP…) par ordre alphabétique. */
    static int comparerNiveaux(String a, String b) {
        int ia = ORDRE_NIVEAUX.indexOf(a.toLowerCase(Locale.ROOT));
        int ib = ORDRE_NIVEAUX.indexOf(b.toLowerCase(Locale.ROOT));
        if (ia >= 0 && ib >= 0) {
            return Integer.compare(ia, ib);
        }
        if (ia >= 0 || ib >= 0) {
            return ia >= 0 ? -1 : 1;
        }
        return Comparator.<String>naturalOrder().compare(a, b);
    }
}
