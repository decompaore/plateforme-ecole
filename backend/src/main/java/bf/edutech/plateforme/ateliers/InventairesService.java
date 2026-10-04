package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.DemandeInventaire;
import bf.edutech.plateforme.ateliers.Vues.InventaireResumeVue;
import bf.edutech.plateforme.ateliers.Vues.InventaireVue;
import bf.edutech.plateforme.ateliers.Vues.LigneEquipementVue;
import bf.edutech.plateforme.ateliers.Vues.LigneMatiereVue;
import bf.edutech.plateforme.ateliers.Vues.SaisieEquipement;
import bf.edutech.plateforme.ateliers.Vues.SaisieInventaire;
import bf.edutech.plateforme.ateliers.Vues.SaisieMatiere;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Inventaires périodiques d'un atelier (semestriels ou annuels selon l'établissement). L'ouverture
 * recopie le stock et les équipements en service ; le responsable saisit ce qu'il constate ; la
 * clôture, une fois toutes les lignes renseignées, corrige le stock (mouvements « INVENTAIRE ») et
 * l'état des équipements.
 */
@Service
public class InventairesService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final InventaireRepository inventaires;
    private final LigneMatiereRepository lignesMatiere;
    private final LigneEquipementRepository lignesEquipement;
    private final StockRepository stocks;
    private final MouvementRepository mouvements;
    private final EquipementRepository equipements;
    private final PanneRepository pannes;
    private final AteliersService ateliers;
    private final CatalogueService catalogue;
    private final ParametresAteliersService parametres;
    private final AnneesService annees;
    private final AccesAteliers acces;
    private final Noms noms;
    private final AuditService audit;
    private final Clock horloge;

    InventairesService(InventaireRepository inventaires, LigneMatiereRepository lignesMatiere,
            LigneEquipementRepository lignesEquipement, StockRepository stocks, MouvementRepository mouvements,
            EquipementRepository equipements, PanneRepository pannes, AteliersService ateliers,
            CatalogueService catalogue, ParametresAteliersService parametres, AnneesService annees,
            AccesAteliers acces, Noms noms, AuditService audit, Clock horloge) {
        this.inventaires = inventaires;
        this.lignesMatiere = lignesMatiere;
        this.lignesEquipement = lignesEquipement;
        this.stocks = stocks;
        this.mouvements = mouvements;
        this.equipements = equipements;
        this.pannes = pannes;
        this.ateliers = ateliers;
        this.catalogue = catalogue;
        this.parametres = parametres;
        this.annees = annees;
        this.acces = acces;
        this.noms = noms;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<InventaireResumeVue> lister(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerLecture(atelierId);
        return inventaires.findByAtelierIdOrderByOuvertLeDesc(atelierId).stream().map(i -> {
            List<LigneInventaireMatiere> lm = lignesMatiere.findByInventaireId(i.getId());
            List<LigneInventaireEquipement> le = lignesEquipement.findByInventaireId(i.getId());
            int ecarts = (int) (lm.stream().filter(l -> l.getQuantiteConstatee() != null
                    && l.getQuantiteConstatee().compareTo(l.getQuantiteTheorique()) != 0).count()
                    + le.stream().filter(l -> l.getEtatConstate() != null && l.getEtatConstate() != l.getEtatTheorique()).count());
            return new InventaireResumeVue(i.getId(), i.getAtelierId(), i.getLibelle(), i.getStatut(), i.getOuvertLe(),
                    i.getClosLe(), ecarts);
        }).toList();
    }

    @Transactional(readOnly = true)
    public InventaireVue fiche(UUID id) {
        UtilisateurConnecte.etablissementActif();
        Inventaire i = charger(id);
        acces.exigerLecture(i.getAtelierId());
        return vue(i);
    }

    @Transactional
    public InventaireVue ouvrir(UUID atelierId, DemandeInventaire d) {
        UtilisateurConnecte.etablissementActif();
        Atelier atelier = ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        if (inventaires.findByAtelierIdAndStatut(atelierId, StatutInventaire.EN_COURS).isPresent()) {
            throw new RegleMetierException("INVENTAIRE_EN_COURS", "Un inventaire est déjà en cours dans cet atelier");
        }
        String libelle = Textes.facultatif(d == null ? null : d.libelle(), "Le libellé", 80);
        Inventaire i = inventaires.saveAndFlush(new Inventaire(atelierId, libelle == null ? libelleParDefaut() : libelle,
                UtilisateurConnecte.id(), horloge.instant()));
        List<LigneInventaireMatiere> lm = stocks.findByAtelierId(atelierId).stream()
                .map(s -> new LigneInventaireMatiere(i.getId(), s.getArticleId(), s.getQuantite())).toList();
        List<LigneInventaireEquipement> le = equipements.findByAtelierIdOrderByDesignationAscNumeroInventaireAsc(atelierId)
                .stream().filter(e -> e.getEtat() != EtatEquipement.REFORME)
                .map(e -> new LigneInventaireEquipement(i.getId(), e.getId(), e.getEtat())).toList();
        if (lm.isEmpty() && le.isEmpty()) {
            throw new RegleMetierException("RIEN_A_INVENTORIER", "L'atelier n'a encore ni matière d'œuvre ni équipement");
        }
        lignesMatiere.saveAll(lm);
        lignesEquipement.saveAll(le);
        audit.enregistrer("INVENTAIRE_OUVERT", atelier.getCode(), Map.of("libelle", i.getLibelle()));
        return vue(i);
    }

    /** Saisie (partielle possible) de ce qui est constaté ; une quantité ou un état vide efface la saisie. */
    @Transactional
    public InventaireVue saisir(UUID id, SaisieInventaire d) {
        UtilisateurConnecte.etablissementActif();
        Inventaire i = charger(id);
        acces.exigerTenue(i.getAtelierId());
        i.exigerEnCours();
        Map<UUID, LigneInventaireMatiere> lm = lignesMatiere.findByInventaireId(id).stream()
                .collect(Collectors.toMap(LigneInventaireMatiere::getArticleId, Function.identity()));
        Map<UUID, LigneInventaireEquipement> le = lignesEquipement.findByInventaireId(id).stream()
                .collect(Collectors.toMap(LigneInventaireEquipement::getEquipementId, Function.identity()));
        for (SaisieMatiere s : d.matieres() == null ? List.<SaisieMatiere>of() : d.matieres()) {
            LigneInventaireMatiere l = lm.get(s.articleId());
            if (l == null) {
                throw new IllegalArgumentException("Article absent de cet inventaire");
            }
            l.constater(s.quantiteConstatee() == null ? null : Textes.quantite(s.quantiteConstatee(), "La quantité constatée", true));
        }
        for (SaisieEquipement s : d.equipements() == null ? List.<SaisieEquipement>of() : d.equipements()) {
            LigneInventaireEquipement l = le.get(s.equipementId());
            if (l == null) {
                throw new IllegalArgumentException("Équipement absent de cet inventaire");
            }
            l.constater(s.etatConstate(), Textes.facultatif(s.observation(), "L'observation", 300));
        }
        if (d.observations() != null) {
            i.observer(Textes.facultatif(d.observations(), "Les observations", 1000));
        }
        return vue(i);
    }

    @Transactional
    public InventaireVue clore(UUID id) {
        UtilisateurConnecte.etablissementActif();
        Inventaire i = charger(id);
        Atelier atelier = ateliers.charger(i.getAtelierId());
        acces.exigerTenue(i.getAtelierId());
        i.exigerEnCours();
        List<LigneInventaireMatiere> lm = lignesMatiere.findByInventaireId(id);
        List<LigneInventaireEquipement> le = lignesEquipement.findByInventaireId(id);
        long restantes = lm.stream().filter(l -> l.getQuantiteConstatee() == null).count()
                + le.stream().filter(l -> l.getEtatConstate() == null).count();
        if (restantes > 0) {
            throw new RegleMetierException("INVENTAIRE_INCOMPLET", restantes + " ligne(s) restent à renseigner");
        }
        UUID moi = UtilisateurConnecte.id();
        LocalDate aujourdhui = LocalDate.now(horloge.withZone(FUSEAU));
        int corrections = 0;
        for (LigneInventaireMatiere l : lm) {
            StockAtelier s = stocks.findByAtelierIdAndArticleId(i.getAtelierId(), l.getArticleId()).orElseThrow();
            BigDecimal ecart = l.getQuantiteConstatee().subtract(s.getQuantite());
            if (ecart.signum() != 0) {
                s.ajouter(ecart);
                mouvements.save(new MouvementStock(i.getAtelierId(), l.getArticleId(), TypeMouvement.INVENTAIRE, ecart,
                        s.getQuantite(), aujourdhui, "Inventaire : " + i.getLibelle(), i.getId(), moi, horloge.instant()));
                corrections++;
            }
        }
        for (LigneInventaireEquipement l : le) {
            Equipement e = equipements.findById(l.getEquipementId()).orElse(null);
            if (e == null || Objects.equals(e.getEtat(), l.getEtatConstate())) {
                continue;
            }
            Panne ouverte = pannes.findByEquipementIdAndStatut(e.getId(), StatutPanne.OUVERTE).orElse(null);
            if (l.getEtatConstate() == EtatEquipement.EN_PANNE && ouverte == null) {
                pannes.save(new Panne(e.getId(), "Constatée à l'inventaire : " + i.getLibelle()
                        + (l.getObservation() == null ? "" : " — " + l.getObservation()), moi, horloge.instant()));
            } else if (l.getEtatConstate() != EtatEquipement.EN_PANNE && ouverte != null) {
                ouverte.cloturer(l.getEtatConstate() == EtatEquipement.BON ? StatutPanne.REPAREE : StatutPanne.IRREPARABLE,
                        "Constaté à l'inventaire : " + i.getLibelle(), null, moi, horloge.instant());
            }
            e.changerEtat(l.getEtatConstate());
            corrections++;
        }
        i.clore(moi, horloge.instant());
        audit.enregistrer("INVENTAIRE_CLOS", atelier.getCode(), Map.of("libelle", i.getLibelle(), "corrections", corrections));
        return vue(i);
    }

    // ------------------------------------------------------------------

    private Inventaire charger(UUID id) {
        return inventaires.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Inventaire introuvable"));
    }

    /** « 1er semestre 2026-2027 », « 2e semestre 2026-2027 » ou « Inventaire annuel 2026-2027 ». */
    String libelleParDefaut() {
        LocalDate auj = LocalDate.now(horloge.withZone(FUSEAU));
        String annee;
        try {
            annee = annees.active().libelle();
        } catch (RessourceIntrouvableException e) {
            int debut = auj.getMonthValue() >= 8 ? auj.getYear() : auj.getYear() - 1;
            annee = debut + "-" + (debut + 1);
        }
        if (parametres.lire().frequenceInventaire() == FrequenceInventaire.ANNUELLE) {
            return "Inventaire annuel " + annee;
        }
        int mois = auj.getMonthValue();
        return (mois >= 8 || mois <= 2 ? "1er semestre " : "2e semestre ") + annee;
    }

    private InventaireVue vue(Inventaire i) {
        Atelier atelier = ateliers.charger(i.getAtelierId());
        List<LigneInventaireMatiere> lm = lignesMatiere.findByInventaireId(i.getId());
        List<LigneInventaireEquipement> le = lignesEquipement.findByInventaireId(i.getId());
        Map<UUID, Article> articles = catalogue.parId(lm.stream().map(LigneInventaireMatiere::getArticleId).toList());
        Map<UUID, Equipement> eq = equipements.findAllById(le.stream().map(LigneInventaireEquipement::getEquipementId).toList())
                .stream().collect(Collectors.toMap(Equipement::getId, Function.identity()));
        List<LigneMatiereVue> matieres = new ArrayList<>();
        for (LigneInventaireMatiere l : lm) {
            Article a = articles.get(l.getArticleId());
            matieres.add(new LigneMatiereVue(l.getArticleId(), a.getCode(), a.getDesignation(), a.getUnite(),
                    l.getQuantiteTheorique(), l.getQuantiteConstatee(),
                    l.getQuantiteConstatee() == null ? null : l.getQuantiteConstatee().subtract(l.getQuantiteTheorique())));
        }
        matieres.sort(Comparator.comparing(LigneMatiereVue::designation));
        List<LigneEquipementVue> lignesEq = le.stream().map(l -> {
            Equipement e = eq.get(l.getEquipementId());
            return new LigneEquipementVue(l.getEquipementId(), e == null ? "?" : e.getDesignation(),
                    e == null ? "?" : e.getNumeroInventaire(), l.getEtatTheorique(), l.getEtatConstate(), l.getObservation());
        }).sorted(Comparator.comparing(LigneEquipementVue::designation).thenComparing(LigneEquipementVue::numeroInventaire))
                .toList();
        int restantes = (int) (matieres.stream().filter(m -> m.quantiteConstatee() == null).count()
                + lignesEq.stream().filter(m -> m.etatConstate() == null).count());
        Map<UUID, String> parNom = noms.utilisateurs();
        boolean modifiable = i.getStatut() == StatutInventaire.EN_COURS && acces.droits(i.getAtelierId()).tenir();
        return new InventaireVue(i.getId(), i.getAtelierId(), atelier.getCode(), atelier.getNom(), i.getLibelle(),
                i.getStatut(), i.getOuvertLe(), i.getOuvertPar() == null ? null : parNom.get(i.getOuvertPar()),
                i.getClosLe(), i.getClosPar() == null ? null : parNom.get(i.getClosPar()), i.getObservations(), matieres,
                lignesEq, restantes, modifiable);
    }
}
