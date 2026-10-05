package bf.edutech.plateforme.ateliers;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.AlertesAtelier;
import bf.edutech.plateforme.ateliers.Vues.AtelierCourtVue;
import bf.edutech.plateforme.ateliers.Vues.AtelierResumeVue;
import bf.edutech.plateforme.ateliers.Vues.AtelierVue;
import bf.edutech.plateforme.ateliers.Vues.CandidatVue;
import bf.edutech.plateforme.ateliers.Vues.DemandeFinMandat;
import bf.edutech.plateforme.ateliers.Vues.DemandeMandat;
import bf.edutech.plateforme.ateliers.Vues.DonneesAtelier;
import bf.edutech.plateforme.ateliers.Vues.DroitsAtelier;
import bf.edutech.plateforme.ateliers.Vues.FiliereCourte;
import bf.edutech.plateforme.ateliers.Vues.MandatVue;
import bf.edutech.plateforme.ateliers.Vues.ParametresVue;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.enseignants.StatutEngagement;
import bf.edutech.plateforme.enseignants.Vues.EnseignantVue;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Ateliers et responsables d'atelier. Le responsable est un enseignant d'une matière technique
 * d'une filière de l'atelier, désigné par le chef des travaux pour la durée fixée par
 * l'établissement ; son mandat se clôt à la désignation du suivant, sur décision, ou quand son
 * engagement dans l'établissement prend fin.
 */
@Service
public class AteliersService {

    /** Délai à partir duquel la fin prévue d'un mandat est signalée. */
    static final int JOURS_ECHEANCE = 60;
    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final AtelierRepository ateliers;
    private final AtelierFiliereRepository liens;
    private final MandatRepository mandats;
    private final EquipementRepository equipements;
    private final StockRepository stocks;
    private final InventaireRepository inventaires;
    private final FilieresService filieres;
    private final EnseignantsService enseignants;
    private final ParametresAteliersService parametres;
    private final AccesAteliers acces;
    private final Noms noms;
    private final AuditService audit;
    private final Clock horloge;

    AteliersService(AtelierRepository ateliers, AtelierFiliereRepository liens, MandatRepository mandats,
            EquipementRepository equipements, StockRepository stocks, InventaireRepository inventaires,
            FilieresService filieres, EnseignantsService enseignants, ParametresAteliersService parametres,
            AccesAteliers acces, Noms noms, AuditService audit, Clock horloge) {
        this.ateliers = ateliers;
        this.liens = liens;
        this.mandats = mandats;
        this.equipements = equipements;
        this.stocks = stocks;
        this.inventaires = inventaires;
        this.filieres = filieres;
        this.enseignants = enseignants;
        this.parametres = parametres;
        this.acces = acces;
        this.noms = noms;
        this.audit = audit;
        this.horloge = horloge;
    }

    LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(FUSEAU));
    }

    // ------------------------------------------------------------------ consultation

    /**
     * Ateliers visibles : tous pour la direction et l'intendance ; pour un enseignant, ceux dont il est
     * responsable ou où il enseigne une matière technique. Chaque atelier porte ses alertes.
     */
    @Transactional
    public List<AtelierResumeVue> lister() {
        UtilisateurConnecte.etablissementActif();
        actualiserMandats();
        List<Atelier> tous = ateliers.findAllByOrderByCodeAsc();
        boolean toutVoir = acces.direction() || acces.intendance();
        List<Atelier> visibles = new ArrayList<>();
        Map<UUID, DroitsAtelier> droits = new java.util.HashMap<>();
        for (Atelier a : tous) {
            DroitsAtelier d = acces.droits(a.getId());
            if (toutVoir || d.signaler()) {
                visibles.add(a);
                droits.put(a.getId(), d);
            }
        }
        if (visibles.isEmpty()) {
            return List.of();
        }
        Contexte c = contexte(visibles.stream().map(Atelier::getId).toList());
        return visibles.stream().map(a -> new AtelierResumeVue(a.getId(), a.getCode(), a.getNom(), a.getEmplacement(),
                a.getPostes(), a.isOuvert(), c.filieresDe(a.getId()), c.responsable(a.getId()),
                c.equipements(a.getId()), c.articles(a.getId()), c.alertes(a.getId()), droits.get(a.getId()))).toList();
    }

    /**
     * Ateliers ouverts et leurs filières, pour l'emploi du temps : lisibles par toute la direction
     * (censeur compris), sans les stocks ni les mandats.
     */
    @Transactional(readOnly = true)
    public List<AtelierCourtVue> ouverts() {
        UtilisateurConnecte.etablissementActif();
        List<Atelier> liste = ateliers.findAllByOrderByCodeAsc().stream().filter(Atelier::isOuvert).toList();
        if (liste.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UUID>> parAtelier = liens.findByAtelierIdIn(liste.stream().map(Atelier::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(AtelierFiliere::getAtelierId,
                        java.util.stream.Collectors.mapping(AtelierFiliere::getFiliereId,
                                java.util.stream.Collectors.toList())));
        return liste.stream().map(a -> new AtelierCourtVue(a.getId(), a.getCode(), a.getNom(), a.getPostes(),
                parAtelier.getOrDefault(a.getId(), List.of()))).toList();
    }

    @Transactional
    public AtelierVue fiche(UUID id) {
        UtilisateurConnecte.etablissementActif();
        actualiserMandats();
        Atelier a = charger(id);
        DroitsAtelier d = acces.exigerLecture(id);
        return vue(a, d);
    }

    // ------------------------------------------------------------------ gestion (direction)

    @Transactional
    public AtelierVue creer(DonneesAtelier d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("création d'un atelier");
        String code = Textes.code(d.code(), "Le code de l'atelier", 20);
        if (ateliers.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Un atelier porte déjà le code " + code);
        }
        Atelier a = new Atelier(code);
        appliquer(a, code, d);
        ateliers.save(a);
        definirFilieres(a.getId(), d.filieres());
        audit.enregistrer("ATELIER_CREE", code, null);
        return vue(a, acces.droits(a.getId()));
    }

    @Transactional
    public AtelierVue modifier(UUID id, DonneesAtelier d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("modification d'un atelier");
        Atelier a = charger(id);
        String code = Textes.code(d.code(), "Le code de l'atelier", 20);
        if (!code.equals(a.getCode()) && ateliers.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Un atelier porte déjà le code " + code);
        }
        appliquer(a, code, d);
        liens.supprimerDe(a.getId());
        liens.flush();
        definirFilieres(a.getId(), d.filieres());
        return vue(a, acces.droits(a.getId()));
    }

    /** Enseignants qui peuvent être désignés responsables de l'atelier. */
    @Transactional(readOnly = true)
    public List<CandidatVue> candidats(UUID id) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("désignation du responsable");
        charger(id);
        Map<UUID, Set<String>> techniques = acces.enseignantsTechniques(acces.filieresDe(id));
        Map<UUID, String> parNom = noms.engagements(techniques.keySet());
        return techniques.entrySet().stream()
                .filter(e -> parNom.containsKey(e.getKey()))
                .map(e -> new CandidatVue(e.getKey(), parNom.get(e.getKey()), List.copyOf(e.getValue())))
                .sorted(Comparator.comparing(CandidatVue::enseignant))
                .toList();
    }

    /** Désigne le responsable ; le mandat en cours (s'il y en a un) se termine la veille du nouveau. */
    @Transactional
    public AtelierVue designer(UUID id, DemandeMandat d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("désignation du responsable");
        Atelier a = charger(id);
        if (d.engagementId() == null) {
            throw new IllegalArgumentException("Choisissez l'enseignant");
        }
        if (!acces.enseignantsTechniques(acces.filieresDe(id)).containsKey(d.engagementId())) {
            throw new RegleMetierException("RESPONSABLE_NON_ELIGIBLE", "Le responsable doit enseigner une matière "
                    + "technique ou pratique dans une filière de l'atelier, cette année");
        }
        LocalDate debut = d.debut() == null ? aujourdhui() : d.debut();
        ParametresVue p = parametres.lire();
        LocalDate finPrevue = d.finPrevue() != null ? d.finPrevue()
                : p.dureeMandatMois() == null ? null : debut.plusMonths(p.dureeMandatMois());
        if (finPrevue != null && !finPrevue.isAfter(debut)) {
            throw new IllegalArgumentException("La fin prévue du mandat doit suivre son début");
        }
        Optional<MandatResponsable> enCours = mandats.findByAtelierIdAndFinIsNull(id);
        if (enCours.isPresent()) {
            MandatResponsable m = enCours.get();
            if (m.getEngagementId().equals(d.engagementId())) {
                throw new RegleMetierException("DEJA_RESPONSABLE", "Cet enseignant est déjà responsable de l'atelier");
            }
            if (!debut.isAfter(m.getDebut())) {
                throw new IllegalArgumentException("Le nouveau mandat doit commencer après le début du mandat en cours ("
                        + m.getDebut() + ")");
            }
            m.terminer(debut.minusDays(1), "Remplacé par un nouveau responsable");
            mandats.saveAndFlush(m);
        }
        mandats.save(new MandatResponsable(id, d.engagementId(), debut, finPrevue, UtilisateurConnecte.id(),
                horloge.instant()));
        audit.enregistrer("RESPONSABLE_ATELIER_DESIGNE", a.getCode(), Map.of("engagement", d.engagementId()));
        return vue(a, acces.droits(id));
    }

    @Transactional
    public AtelierVue terminerMandat(UUID id, DemandeFinMandat d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("fin du mandat du responsable");
        Atelier a = charger(id);
        MandatResponsable m = mandats.findByAtelierIdAndFinIsNull(id)
                .orElseThrow(() -> new RegleMetierException("SANS_RESPONSABLE", "L'atelier n'a pas de responsable"));
        m.terminer(d == null || d.date() == null ? aujourdhui() : d.date(),
                Textes.facultatif(d == null ? null : d.motif(), "Le motif", 200));
        audit.enregistrer("MANDAT_ATELIER_TERMINE", a.getCode(), Map.of("engagement", m.getEngagementId()));
        return vue(a, acces.droits(id));
    }

    // ------------------------------------------------------------------ outils

    Atelier charger(UUID id) {
        return ateliers.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Atelier introuvable"));
    }

    /** Clôt les mandats des enseignants dont l'engagement n'est plus actif (mutation, fin de contrat…). */
    void actualiserMandats() {
        List<MandatResponsable> enCours = mandats.findByFinIsNull();
        if (enCours.isEmpty()) {
            return;
        }
        Set<UUID> actifs = enseignants.lister(StatutEngagement.ACTIF).stream().map(EnseignantVue::engagementId)
                .collect(Collectors.toSet());
        for (MandatResponsable m : enCours) {
            if (!actifs.contains(m.getEngagementId())) {
                m.terminer(aujourdhui(), "Fin d'engagement de l'enseignant");
            }
        }
    }

    private void appliquer(Atelier a, String code, DonneesAtelier d) {
        if (d.postes() != null && (d.postes() < 1 || d.postes() > 500)) {
            throw new IllegalArgumentException("Le nombre de postes est compris entre 1 et 500");
        }
        a.definir(code, Textes.obligatoire(d.nom(), "Le nom de l'atelier", 120),
                Textes.facultatif(d.emplacement(), "L'emplacement", 120), d.postes(), d.ouvert() == null || d.ouvert(),
                Textes.facultatif(d.observations(), "Les observations", 500));
    }

    private void definirFilieres(UUID atelierId, List<UUID> choisies) {
        if (choisies == null || choisies.isEmpty()) {
            throw new IllegalArgumentException("Indiquez au moins une filière servie par l'atelier");
        }
        Set<UUID> connues = filieres.lister().stream().map(FiliereVue::id).collect(Collectors.toSet());
        for (UUID f : new LinkedHashSet<>(choisies)) {
            if (!connues.contains(f)) {
                throw new RessourceIntrouvableException("Filière introuvable");
            }
            liens.save(new AtelierFiliere(atelierId, f));
        }
    }

    private AtelierVue vue(Atelier a, DroitsAtelier d) {
        Contexte c = contexte(List.of(a.getId()));
        List<MandatResponsable> historique = mandats.findByAtelierIdOrderByDebutDesc(a.getId());
        Map<UUID, String> parNom = noms.engagements(historique.stream().map(MandatResponsable::getEngagementId)
                .collect(Collectors.toSet()));
        return new AtelierVue(a.getId(), a.getCode(), a.getNom(), a.getEmplacement(), a.getPostes(), a.isOuvert(),
                a.getObservations(), c.filieresDe(a.getId()), c.responsable(a.getId()),
                historique.stream().filter(m -> !m.enCours()).map(m -> mandat(m, parNom)).toList(),
                c.equipements(a.getId()), c.articles(a.getId()), c.alertes(a.getId()), d);
    }

    private MandatVue mandat(MandatResponsable m, Map<UUID, String> parNom) {
        LocalDate auj = aujourdhui();
        boolean enCours = m.enCours();
        boolean echu = enCours && m.getFinPrevue() != null && m.getFinPrevue().isBefore(auj);
        boolean proche = enCours && !echu && m.getFinPrevue() != null
                && !m.getFinPrevue().isAfter(auj.plusDays(JOURS_ECHEANCE));
        return new MandatVue(m.getId(), m.getEngagementId(), parNom.getOrDefault(m.getEngagementId(), "Enseignant"),
                m.getDebut(), m.getFinPrevue(), m.getFin(), m.getMotifFin(), proche, echu);
    }

    /** Données de plusieurs ateliers chargées en une fois (filières, responsables, compteurs, alertes). */
    private Contexte contexte(Collection<UUID> ids) {
        Map<UUID, FiliereVue> fil = filieres.lister().stream().collect(Collectors.toMap(FiliereVue::id, Function.identity()));
        Map<UUID, List<FiliereCourte>> filieresParAtelier = liens.findByAtelierIdIn(ids).stream()
                .filter(l -> fil.containsKey(l.getFiliereId()))
                .collect(Collectors.groupingBy(AtelierFiliere::getAtelierId, Collectors.mapping(l -> {
                    FiliereVue f = fil.get(l.getFiliereId());
                    return new FiliereCourte(f.id(), f.code(), f.libelle());
                }, Collectors.toList())));
        Map<UUID, MandatResponsable> responsables = mandats.findByFinIsNull().stream()
                .filter(m -> ids.contains(m.getAtelierId()))
                .collect(Collectors.toMap(MandatResponsable::getAtelierId, Function.identity()));
        Map<UUID, String> parNom = noms.engagements(responsables.values().stream()
                .map(MandatResponsable::getEngagementId).collect(Collectors.toSet()));
        Map<UUID, List<Equipement>> eq = equipements.findByAtelierIdIn(ids).stream()
                .collect(Collectors.groupingBy(Equipement::getAtelierId));
        Map<UUID, List<StockAtelier>> st = stocks.findByAtelierIdIn(ids).stream()
                .collect(Collectors.groupingBy(StockAtelier::getAtelierId));
        Map<UUID, List<Inventaire>> inv = inventaires.findByAtelierIdIn(ids).stream()
                .collect(Collectors.groupingBy(Inventaire::getAtelierId));
        int moisEntreInventaires = parametres.lire().frequenceInventaire() == FrequenceInventaire.ANNUELLE ? 12 : 6;
        return new Contexte(filieresParAtelier, responsables, parNom, eq, st, inv, moisEntreInventaires);
    }

    private final class Contexte {
        private final Map<UUID, List<FiliereCourte>> filieres;
        private final Map<UUID, MandatResponsable> responsables;
        private final Map<UUID, String> noms;
        private final Map<UUID, List<Equipement>> equipements;
        private final Map<UUID, List<StockAtelier>> stocks;
        private final Map<UUID, List<Inventaire>> inventaires;
        private final int moisEntreInventaires;

        Contexte(Map<UUID, List<FiliereCourte>> filieres, Map<UUID, MandatResponsable> responsables,
                Map<UUID, String> noms, Map<UUID, List<Equipement>> equipements, Map<UUID, List<StockAtelier>> stocks,
                Map<UUID, List<Inventaire>> inventaires, int moisEntreInventaires) {
            this.filieres = filieres;
            this.responsables = responsables;
            this.noms = noms;
            this.equipements = equipements;
            this.stocks = stocks;
            this.inventaires = inventaires;
            this.moisEntreInventaires = moisEntreInventaires;
        }

        List<FiliereCourte> filieresDe(UUID id) {
            return filieres.getOrDefault(id, List.of()).stream().sorted(Comparator.comparing(FiliereCourte::code)).toList();
        }

        MandatVue responsable(UUID id) {
            MandatResponsable m = responsables.get(id);
            return m == null ? null : mandat(m, noms);
        }

        int equipements(UUID id) {
            return (int) equipements.getOrDefault(id, List.of()).stream().filter(e -> e.getEtat() != EtatEquipement.REFORME).count();
        }

        int articles(UUID id) {
            return stocks.getOrDefault(id, List.of()).size();
        }

        AlertesAtelier alertes(UUID id) {
            MandatVue r = responsable(id);
            List<Equipement> eq = equipements.getOrDefault(id, List.of());
            List<Inventaire> inv = inventaires.getOrDefault(id, List.of());
            LocalDate dernier = inv.stream().filter(i -> i.getStatut() == StatutInventaire.CLOS)
                    .map(i -> LocalDate.ofInstant(i.getClosLe(), FUSEAU)).max(Comparator.naturalOrder()).orElse(null);
            boolean enCours = inv.stream().anyMatch(i -> i.getStatut() == StatutInventaire.EN_COURS);
            boolean aInventorier = !eq.isEmpty() || !stocks.getOrDefault(id, List.of()).isEmpty();
            boolean retard = !enCours && aInventorier
                    && (dernier == null || dernier.isBefore(aujourdhui().minusMonths(moisEntreInventaires)));
            return new AlertesAtelier(r == null, r != null && r.echeanceProche(), r != null && r.echu(),
                    (int) eq.stream().filter(e -> e.getEtat() == EtatEquipement.EN_PANNE).count(),
                    (int) eq.stream().filter(e -> e.getEtat() == EtatEquipement.MANQUANT).count(),
                    (int) stocks.getOrDefault(id, List.of()).stream().filter(StockAtelier::sousLeSeuil).count(),
                    enCours, dernier, retard);
        }
    }
}
