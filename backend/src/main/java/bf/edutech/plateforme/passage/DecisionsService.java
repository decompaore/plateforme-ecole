package bf.edutech.plateforme.passage;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.ResultatsService;
import bf.edutech.plateforme.evaluations.Vues.ResultatEleveVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatsPeriodeVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.passage.ParametresPassageService.ParametresPassage;
import bf.edutech.plateforme.passage.ParametresPassageService.PoidsPeriode;
import bf.edutech.plateforme.pedagogie.CodeModele;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Décisions de fin d'année d'une classe : calcul de la moyenne annuelle et de la proposition
 * (toutes les périodes verrouillées), examen et modification en conseil de classe (motif obligatoire
 * pour s'écarter de la proposition), saisie des résultats d'examen, validation avec SMS aux familles.
 */
@Service
public class DecisionsService {

    public record DecisionVue(UUID inscriptionId, String matricule, String nom, String prenoms, boolean redoublant,
            BigDecimal moyenneAnnuelle, Integer rang, BigDecimal tauxMaitrise, int redoublements,
            ResultatExamen resultatExamen, Decision proposition, Decision decision, String motif, boolean validee) {
    }

    public record DecisionsClasseVue(UUID classeId, String classeCode, String examen, boolean validees,
            Map<Decision, Long> repartition, List<DecisionVue> eleves) {
    }

    /** Saisie du conseil ; chaque champ absent reste inchangé. */
    public record SaisieDecision(UUID inscriptionId, Decision decision, String motif, ResultatExamen resultatExamen) {
    }

    private final DecisionRepository decisions;
    private final ParametresPassageService parametres;
    private final ResultatsService resultats;
    private final ClassesService classes;
    private final PeriodesService periodes;
    private final AnneesService annees;
    private final ProfilsService profils;
    private final InscriptionsService inscriptions;
    private final ElevesService eleves;
    private final ContactsEleves contacts;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final Clock horloge;

    DecisionsService(DecisionRepository decisions, ParametresPassageService parametres, ResultatsService resultats,
            ClassesService classes, PeriodesService periodes, AnneesService annees, ProfilsService profils,
            InscriptionsService inscriptions, ElevesService eleves, ContactsEleves contacts,
            NotificationsService notifications, AuditService audit, Clock horloge) {
        this.decisions = decisions;
        this.parametres = parametres;
        this.resultats = resultats;
        this.classes = classes;
        this.periodes = periodes;
        this.annees = annees;
        this.profils = profils;
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.contacts = contacts;
        this.notifications = notifications;
        this.audit = audit;
        this.horloge = horloge;
    }

    /** Calcule (ou recalcule) les moyennes annuelles et les propositions de la classe. */
    @Transactional
    public DecisionsClasseVue proposer(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        List<DecisionFinAnnee> existantes = decisions.findByClasseId(classeId);
        if (existantes.stream().anyMatch(DecisionFinAnnee::estValidee)) {
            throw new RegleMetierException("DECISIONS_VALIDEES", "Les décisions de la classe " + classe.code()
                    + " sont validées : elles ne se recalculent plus");
        }
        ProfilVue profil = profils.trouver(classe.profilId());
        List<PeriodeVue> periodesClasse = periodes.lister(classe.anneeId()).stream()
                .filter(p -> p.profilId().equals(classe.profilId()))
                .sorted(Comparator.comparingInt(PeriodeVue::ordre)).toList();
        if (periodesClasse.isEmpty()) {
            throw new RegleMetierException("AUCUNE_PERIODE", "Aucune période n'est définie pour cette classe");
        }
        List<String> ouvertes = periodesClasse.stream().filter(p -> !p.verrouillee()).map(PeriodeVue::libelle).toList();
        if (!ouvertes.isEmpty()) {
            throw new RegleMetierException("PERIODES_NON_VERROUILLEES", "Verrouillez d'abord : "
                    + String.join(", ", ouvertes));
        }
        Map<Integer, BigDecimal> poidsParOrdre = parametres.poids(classe.profilId()).stream()
                .collect(Collectors.toMap(PoidsPeriode::ordre, PoidsPeriode::poids));
        List<BigDecimal> poids = periodesClasse.stream()
                .map(p -> poidsParOrdre.getOrDefault(p.ordre(), BigDecimal.ONE)).toList();
        List<Map<UUID, ResultatEleveVue>> parPeriode = new ArrayList<>();
        for (PeriodeVue p : periodesClasse) {
            ResultatsPeriodeVue r = resultats.calculer(classeId, p.id());
            parPeriode.add(r.eleves().stream()
                    .collect(Collectors.toMap(ResultatEleveVue::inscriptionId, Function.identity())));
        }
        boolean competences = profil.modele() == CodeModele.COMPETENCES;
        boolean examen = parametres.examen(classeId).isPresent();
        ParametresPassage regles = parametres.lire();
        MoteurPassage.Regles r = new MoteurPassage.Regles(profil.seuilAdmission(), regles.seuilExclusion(),
                regles.redoublementsMax());

        List<InscriptionVue> eleves = inscriptions.listerParClasse(classeId, false);
        Map<UUID, BigDecimal> moyennes = new LinkedHashMap<>();
        for (InscriptionVue i : eleves) {
            List<BigDecimal> ms = parPeriode.stream().map(m -> m.get(i.id()))
                    .map(e -> e == null ? null : e.moyenne()).toList();
            moyennes.put(i.id(), competences ? null : MoteurPassage.moyenneAnnuelle(ms, poids, profil.decimalesMoyenne()));
        }
        Map<UUID, Integer> rangs = MoteurPassage.rangs(moyennes);
        Map<UUID, DecisionFinAnnee> parInscription = new HashMap<>();
        existantes.forEach(d -> parInscription.put(d.getInscriptionId(), d));
        if (!eleves.isEmpty()) {
            // Élève arrivé d'une autre classe après un premier calcul : sa décision le suit
            for (DecisionFinAnnee d : decisions.findByInscriptionIdIn(eleves.stream().map(InscriptionVue::id).toList())) {
                if (!d.getClasseId().equals(classeId) && d.estValidee()) {
                    throw new RegleMetierException("DECISION_VALIDEE_AILLEURS", "La décision d'un élève de la classe "
                            + "a déjà été validée dans une autre classe : voyez avec la direction");
                }
                parInscription.put(d.getInscriptionId(), d);
            }
        }
        Set<UUID> presents = new HashSet<>();
        Instant maintenant = horloge.instant();
        Map<UUID, String> niveauxClasses = new HashMap<>();
        for (InscriptionVue i : eleves) {
            presents.add(i.id());
            ResultatEleveVue derniere = parPeriode.get(parPeriode.size() - 1).get(i.id());
            int redoublements = redoublements(i, classe, niveauxClasses);
            DecisionFinAnnee d = parInscription.computeIfAbsent(i.id(), DecisionFinAnnee::new);
            Decision proposition = MoteurPassage.proposer(competences, moyennes.get(i.id()),
                    derniere != null && derniere.admis(), examen, d.getResultatExamen(), redoublements, r);
            d.calculer(classeId, moyennes.get(i.id()), rangs.get(i.id()),
                    derniere != null ? derniere.tauxMaitrise() : null, redoublements, proposition, maintenant);
            decisions.save(d);
        }
        // Élèves sortis de la classe depuis le dernier calcul
        existantes.stream().filter(d -> !presents.contains(d.getInscriptionId())).forEach(decisions::delete);
        audit.enregistrer("DECISIONS_PROPOSEES", classe.code(), Map.of("eleves", eleves.size()));
        return vue(classe);
    }

    @Transactional(readOnly = true)
    public DecisionsClasseVue lister(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        return vue(classes.trouver(classeId));
    }

    /** Le conseil modifie des décisions (motif obligatoire si différente de la proposition) ou saisit l'examen. */
    @Transactional
    public DecisionsClasseVue modifier(UUID classeId, List<SaisieDecision> saisies) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        Map<UUID, DecisionFinAnnee> parInscription = decisions.findByClasseId(classeId).stream()
                .collect(Collectors.toMap(DecisionFinAnnee::getInscriptionId, Function.identity()));
        if (parInscription.isEmpty()) {
            throw new RegleMetierException("DECISIONS_NON_CALCULEES", "Calculez d'abord les propositions de la classe");
        }
        if (parInscription.values().stream().anyMatch(DecisionFinAnnee::estValidee)) {
            throw new RegleMetierException("DECISIONS_VALIDEES", "Les décisions de la classe " + classe.code()
                    + " sont validées : elles ne changent plus");
        }
        boolean examen = parametres.examen(classeId).isPresent();
        ProfilVue profil = profils.trouver(classe.profilId());
        ParametresPassage regles = parametres.lire();
        MoteurPassage.Regles r = new MoteurPassage.Regles(profil.seuilAdmission(), regles.seuilExclusion(),
                regles.redoublementsMax());
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        Set<UUID> vus = new HashSet<>();
        for (SaisieDecision s : saisies) {
            if (s == null || s.inscriptionId() == null || !vus.add(s.inscriptionId())) {
                throw new IllegalArgumentException("Chaque élève figure une seule fois, avec son inscription");
            }
            DecisionFinAnnee d = parInscription.get(s.inscriptionId());
            if (d == null) {
                throw new IllegalArgumentException("Élève sans décision calculée dans cette classe : "
                        + s.inscriptionId());
            }
            if (s.resultatExamen() != null) {
                if (!examen) {
                    throw new RegleMetierException("CLASSE_SANS_EXAMEN", "La classe " + classe.code()
                            + " ne prépare pas d'examen");
                }
                d.resultatExamen(s.resultatExamen());
                // en compétences, « admis en compétences » n'intervient pas pour une classe d'examen
                d.proposer(MoteurPassage.proposer(profil.modele() == CodeModele.COMPETENCES, d.getMoyenneAnnuelle(),
                        false, true, s.resultatExamen(), d.getRedoublements(), r));
            }
            if (s.decision() != null) {
                String motif = s.motif() == null ? null : s.motif().trim();
                if (s.decision() == Decision.EN_ATTENTE_EXAMEN && d.getProposition() != Decision.EN_ATTENTE_EXAMEN) {
                    throw new IllegalArgumentException("« En attente d'examen » n'est pas une décision du conseil");
                }
                if (examen && d.getResultatExamen() == null && s.decision() != Decision.EN_ATTENTE_EXAMEN) {
                    throw new RegleMetierException("RESULTATS_EXAMEN_MANQUANTS",
                            "Saisissez d'abord le résultat de l'examen de cet élève");
                }
                if (s.decision() != d.getProposition() && (motif == null || motif.isEmpty() || motif.length() > 300)) {
                    throw new IllegalArgumentException("Motif obligatoire (300 caractères au plus) pour modifier la "
                            + "proposition « " + d.getProposition().name() + " »");
                }
                d.decider(s.decision(), motif, moi);
            }
        }
        audit.enregistrer("DECISIONS_MODIFIEES", classe.code(), Map.of("eleves", saisies.size()));
        return vue(classe);
    }

    /** Valide les décisions de la classe (définitives) et prévient les familles par SMS. */
    @Transactional
    public DecisionsClasseVue valider(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        List<DecisionFinAnnee> liste = decisions.findByClasseId(classeId);
        if (liste.isEmpty()) {
            throw new RegleMetierException("DECISIONS_NON_CALCULEES", "Calculez d'abord les propositions de la classe");
        }
        if (liste.stream().anyMatch(DecisionFinAnnee::estValidee)) {
            throw new RegleMetierException("DECISIONS_VALIDEES", "Décisions déjà validées");
        }
        long enAttente = liste.stream().filter(d -> d.getDecision() == Decision.EN_ATTENTE_EXAMEN).count();
        if (enAttente > 0) {
            throw new RegleMetierException("RESULTATS_EXAMEN_MANQUANTS", enAttente
                    + " élève(s) attendent le résultat de l'examen : saisissez-le avant de valider");
        }
        Instant maintenant = horloge.instant();
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        liste.forEach(d -> d.valider(moi, maintenant));
        Map<UUID, ContactEleve> parInscription = contacts
                .pourInscriptions(liste.stream().map(DecisionFinAnnee::getInscriptionId).toList());
        String etablissement = notifications.nomEtablissement();
        AnneeVue annee = annees.trouver(classe.anneeId());
        int decimales = profils.trouver(classe.profilId()).decimalesMoyenne();
        int sms = 0;
        for (DecisionFinAnnee d : liste) {
            ContactEleve c = parInscription.get(d.getInscriptionId());
            if (c == null || c.telephone() == null) {
                continue;
            }
            String moyenne = d.getMoyenneAnnuelle() == null ? ""
                    : " (moyenne annuelle " + echelle(d.getMoyenneAnnuelle(), decimales).toPlainString()
                            .replace('.', ',') + "/20)";
            notifications.planifierSms("decision:" + d.getId(), c.telephone(), c.langueSms(),
                    etablissement + " : année " + annee.libelle() + ", " + c.prenoms() + " " + c.nom() + " ("
                            + classe.code() + ") est " + d.getDecision().libelle() + moyenne + ".");
            sms++;
        }
        audit.enregistrer("DECISIONS_VALIDEES", classe.code(), Map.of("eleves", liste.size(), "sms", sms));
        return vue(classe);
    }

    // ------------------------------------------------------------------

    /** Années déjà passées dans le même niveau, avant celle-ci (dans cet établissement). */
    private int redoublements(InscriptionVue i, ClasseVue classe, Map<UUID, String> niveauxClasses) {
        java.time.LocalDate debutAnnee = annees.trouver(i.anneeId()).debut();
        int n = 0;
        for (InscriptionVue autre : eleves.trouver(i.eleveId()).inscriptions()) {
            if (autre.anneeId().equals(i.anneeId()) || !annees.trouver(autre.anneeId()).debut().isBefore(debutAnnee)) {
                continue;                                    // seulement les années précédentes
            }
            String niveau = niveauxClasses.computeIfAbsent(autre.classeId(), id -> classes.trouver(id).niveau());
            if (niveau.equalsIgnoreCase(classe.niveau())) {
                n++;
            }
        }
        return n;
    }

    /** Moyenne au nombre de décimales du profil (la base conserve jusqu'à 3 décimales). */
    private static BigDecimal echelle(BigDecimal valeur, int decimales) {
        return valeur == null ? null : valeur.setScale(decimales, java.math.RoundingMode.HALF_UP);
    }

    private DecisionsClasseVue vue(ClasseVue classe) {
        int decimales = profils.trouver(classe.profilId()).decimalesMoyenne();
        Map<UUID, InscriptionVue> infos = inscriptions.listerParClasse(classe.id(), true).stream()
                .collect(Collectors.toMap(InscriptionVue::id, Function.identity()));
        List<DecisionVue> lignes = decisions.findByClasseId(classe.id()).stream()
                .map(d -> {
                    InscriptionVue i = infos.get(d.getInscriptionId());
                    return new DecisionVue(d.getInscriptionId(), i != null ? i.matricule() : null,
                            i != null ? i.nom() : null, i != null ? i.prenoms() : null, i != null && i.redoublant(),
                            echelle(d.getMoyenneAnnuelle(), decimales), d.getRang(), d.getTauxMaitrise(), d.getRedoublements(),
                            d.getResultatExamen(), d.getProposition(), d.getDecision(), d.getMotif(), d.estValidee());
                })
                .sorted(Comparator.comparing((DecisionVue d) -> d.rang() == null ? Integer.MAX_VALUE : d.rang())
                        .thenComparing(d -> d.nom() == null ? "" : d.nom())
                        .thenComparing(d -> d.prenoms() == null ? "" : d.prenoms()))
                .toList();
        Map<Decision, Long> repartition = new LinkedHashMap<>();
        for (Decision dec : Decision.values()) {
            long n = lignes.stream().filter(l -> l.decision() == dec).count();
            if (n > 0) {
                repartition.put(dec, n);
            }
        }
        return new DecisionsClasseVue(classe.id(), classe.code(), parametres.examen(classe.id()).orElse(null),
                !lignes.isEmpty() && lignes.stream().allMatch(DecisionVue::validee), repartition, lignes);
    }
}
