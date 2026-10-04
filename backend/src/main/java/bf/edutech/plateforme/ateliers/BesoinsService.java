package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.FiliereCourte;
import bf.edutech.plateforme.ateliers.VuesBesoins.BesoinAtelierResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.BesoinResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.BesoinVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CampagneResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CampagneVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CommandeResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeArbitrage;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeCampagne;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeLigne;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeRenvoi;
import bf.edutech.plateforme.ateliers.VuesBesoins.DroitsBesoin;
import bf.edutech.plateforme.ateliers.VuesBesoins.FiliereConsolideeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneBesoinVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneConsolideeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.SaisieArbitrage;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Circuit des besoins, première partie : campagnes (année en cours, examens), expression des besoins
 * de chaque atelier avec ses enseignants techniques, transmission au chef des travaux, arbitrage avec
 * le proviseur et l'intendant, validation, consolidation par filière et transmission à la direction
 * régionale. Les commandes, livraisons et répartitions sont dans {@link CommandesService}.
 */
@Service
public class BesoinsService {

    private final CampagneRepository campagnes;
    private final BesoinRepository besoins;
    private final LigneBesoinRepository lignes;
    private final CommandeRepository commandes;
    private final LigneCommandeRepository lignesCommande;
    private final LivraisonRepository livraisons;
    private final LigneLivraisonRepository lignesLivraison;
    private final AtelierRepository ateliers;
    private final AtelierFiliereRepository liensFilieres;
    private final MandatRepository mandats;
    private final CatalogueService catalogue;
    private final AnneesService annees;
    private final FilieresService filieres;
    private final AccesAteliers acces;
    private final Noms noms;
    private final AuditService audit;
    private final Clock horloge;

    BesoinsService(CampagneRepository campagnes, BesoinRepository besoins, LigneBesoinRepository lignes,
            CommandeRepository commandes, LigneCommandeRepository lignesCommande, LivraisonRepository livraisons,
            LigneLivraisonRepository lignesLivraison, AtelierRepository ateliers, AtelierFiliereRepository liensFilieres,
            MandatRepository mandats, CatalogueService catalogue, AnneesService annees, FilieresService filieres,
            AccesAteliers acces, Noms noms, AuditService audit, Clock horloge) {
        this.campagnes = campagnes;
        this.besoins = besoins;
        this.lignes = lignes;
        this.commandes = commandes;
        this.lignesCommande = lignesCommande;
        this.livraisons = livraisons;
        this.lignesLivraison = lignesLivraison;
        this.ateliers = ateliers;
        this.liensFilieres = liensFilieres;
        this.mandats = mandats;
        this.catalogue = catalogue;
        this.annees = annees;
        this.filieres = filieres;
        this.acces = acces;
        this.noms = noms;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ campagnes

    @Transactional
    public CampagneVue ouvrir(DemandeCampagne d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("ouverture d'une campagne de besoins");
        if (d.type() == null) {
            throw new IllegalArgumentException("Indiquez le type : ANNEE_EN_COURS ou EXAMENS");
        }
        AnneeVue annee = d.anneeId() == null ? annees.active() : annees.trouver(d.anneeId());
        if (annee.etat() != EtatAnnee.PREPARATION && annee.etat() != EtatAnnee.ACTIVE) {
            throw new RegleMetierException("ANNEE_FIGEE", "L'année " + annee.libelle() + " est close");
        }
        if (campagnes.existsByAnneeIdAndType(annee.id(), d.type())) {
            throw new RegleMetierException("CAMPAGNE_EXISTANTE", "Cette campagne existe déjà pour l'année " + annee.libelle());
        }
        CampagneBesoins c = new CampagneBesoins(annee.id(), d.type(), UtilisateurConnecte.id(), horloge.instant());
        c.definir(libelle(d, annee), d.dateLimite(), Textes.facultatif(d.observations(), "Les observations", 1000));
        campagnes.saveAndFlush(c);
        completer(c);
        audit.enregistrer("CAMPAGNE_BESOINS_OUVERTE", c.getLibelle(), Map.of("type", d.type().name()));
        return vue(c);
    }

    @Transactional(readOnly = true)
    public List<CampagneResumeVue> lister() {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirectionOuIntendance();
        List<CampagneBesoins> liste = campagnes.findAllByOrderByOuverteLeDesc();
        if (liste.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = liste.stream().map(CampagneBesoins::getId).toList();
        Map<UUID, List<BesoinAtelier>> parCampagne = besoins.findByCampagneIdIn(ids).stream()
                .collect(Collectors.groupingBy(BesoinAtelier::getCampagneId));
        List<LigneBesoin> toutes = lignes.findByBesoinIdIn(parCampagne.values().stream().flatMap(List::stream)
                .map(BesoinAtelier::getId).toList());
        Map<UUID, Article> articles = catalogue.parId(toutes.stream().map(LigneBesoin::getArticleId).distinct().toList());
        Map<UUID, List<LigneBesoin>> lignesParBesoin = toutes.stream().collect(Collectors.groupingBy(LigneBesoin::getBesoinId));
        Map<UUID, Long> nbCommandes = commandes.findByCampagneIdIn(ids).stream()
                .filter(c -> c.getStatut() != StatutCommande.ANNULEE)
                .collect(Collectors.groupingBy(Commande::getCampagneId, Collectors.counting()));
        return liste.stream().map(c -> {
            List<BesoinAtelier> bs = parCampagne.getOrDefault(c.getId(), List.of());
            long montant = bs.stream().filter(b -> b.getStatut() == StatutBesoin.VALIDE)
                    .flatMap(b -> lignesParBesoin.getOrDefault(b.getId(), List.of()).stream())
                    .mapToLong(l -> montant(l, articles.get(l.getArticleId()))).sum();
            return new CampagneResumeVue(c.getId(), c.getAnneeId(), c.getType(), c.getLibelle(), c.getDateLimite(),
                    c.getStatut(), c.getOuverteLe(), c.getTransmiseLe(), bs.size(),
                    (int) bs.stream().filter(b -> b.getStatut() != StatutBesoin.BROUILLON).count(),
                    (int) bs.stream().filter(b -> b.getStatut() == StatutBesoin.VALIDE).count(), montant,
                    nbCommandes.getOrDefault(c.getId(), 0L).intValue());
        }).toList();
    }

    @Transactional
    public CampagneVue fiche(UUID id) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirectionOuIntendance();
        CampagneBesoins c = charger(id);
        if (c.getStatut() == StatutCampagne.OUVERTE) {
            completer(c);
        }
        return vue(c);
    }

    @Transactional
    public CampagneVue modifier(UUID id, DemandeCampagne d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("modification d'une campagne");
        CampagneBesoins c = charger(id);
        c.definir(d.libelle() == null || d.libelle().isBlank() ? c.getLibelle() : Textes.obligatoire(d.libelle(), "Le libellé", 120),
                d.dateLimite(), Textes.facultatif(d.observations(), "Les observations", 1000));
        return vue(c);
    }

    /** Transmission à la direction régionale : tous les besoins exprimés doivent être validés. */
    @Transactional
    public CampagneVue transmettre(UUID id) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("transmission des besoins");
        CampagneBesoins c = charger(id);
        List<BesoinAtelier> bs = besoins.findByCampagneId(id);
        Map<UUID, List<LigneBesoin>> parBesoin = lignes.findByBesoinIdIn(bs.stream().map(BesoinAtelier::getId).toList())
                .stream().collect(Collectors.groupingBy(LigneBesoin::getBesoinId));
        Map<UUID, Atelier> parAtelier = ateliers.findAllById(bs.stream().map(BesoinAtelier::getAtelierId).toList()).stream()
                .collect(Collectors.toMap(Atelier::getId, Function.identity()));
        List<String> enAttente = bs.stream()
                .filter(b -> b.getStatut() != StatutBesoin.VALIDE && parBesoin.containsKey(b.getId()))
                .map(b -> parAtelier.get(b.getAtelierId()).getCode()).sorted().toList();
        if (!enAttente.isEmpty()) {
            throw new RegleMetierException("BESOINS_NON_VALIDES",
                    "Validez d'abord les besoins des ateliers : " + String.join(", ", enAttente));
        }
        if (bs.stream().noneMatch(b -> b.getStatut() == StatutBesoin.VALIDE)) {
            throw new RegleMetierException("AUCUN_BESOIN", "Aucun besoin validé à transmettre");
        }
        c.transmettre(horloge.instant());
        audit.enregistrer("CAMPAGNE_BESOINS_TRANSMISE", c.getLibelle(), null);
        return vue(c);
    }

    @Transactional
    public CampagneVue clore(UUID id) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("clôture d'une campagne");
        CampagneBesoins c = charger(id);
        c.clore(horloge.instant());
        audit.enregistrer("CAMPAGNE_BESOINS_CLOSE", c.getLibelle(), null);
        return vue(c);
    }

    // ------------------------------------------------------------------ besoins d'un atelier

    @Transactional
    public List<BesoinAtelierResumeVue> besoinsDeLAtelier(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        Atelier atelier = ateliers.findById(atelierId).orElseThrow(() -> new RessourceIntrouvableException("Atelier introuvable"));
        exigerLecture(atelierId);
        if (atelier.isOuvert()) {
            for (CampagneBesoins c : campagnes.findAllByOrderByOuverteLeDesc()) {
                if (c.getStatut() == StatutCampagne.OUVERTE && besoins.findByCampagneIdAndAtelierId(c.getId(), atelierId).isEmpty()) {
                    besoins.save(new BesoinAtelier(c.getId(), atelierId));
                }
            }
            besoins.flush();
        }
        List<BesoinAtelier> bs = besoins.findByAtelierId(atelierId);
        Map<UUID, CampagneBesoins> cs = campagnes.findAllById(bs.stream().map(BesoinAtelier::getCampagneId).toList()).stream()
                .collect(Collectors.toMap(CampagneBesoins::getId, Function.identity()));
        Map<UUID, List<LigneBesoin>> parBesoin = lignes.findByBesoinIdIn(bs.stream().map(BesoinAtelier::getId).toList())
                .stream().collect(Collectors.groupingBy(LigneBesoin::getBesoinId));
        Map<UUID, Article> articles = catalogue.parId(parBesoin.values().stream().flatMap(List::stream)
                .map(LigneBesoin::getArticleId).distinct().toList());
        return bs.stream().map(b -> {
            CampagneBesoins c = cs.get(b.getCampagneId());
            List<LigneBesoin> ls = parBesoin.getOrDefault(b.getId(), List.of());
            return new BesoinAtelierResumeVue(b.getId(), c.getId(), c.getLibelle(), c.getType(), c.getStatut(),
                    c.getDateLimite(), b.getStatut(), ls.size(),
                    ls.stream().mapToLong(l -> montant(l, articles.get(l.getArticleId()))).sum());
        }).sorted(Comparator.comparing((BesoinAtelierResumeVue v) -> cs.get(v.campagneId()).getOuverteLe()).reversed()).toList();
    }

    @Transactional(readOnly = true)
    public BesoinVue besoin(UUID id) {
        UtilisateurConnecte.etablissementActif();
        BesoinAtelier b = chargerBesoin(id);
        exigerLecture(b.getAtelierId());
        return vueBesoin(b);
    }

    /** Ajoute ou modifie une ligne : enseignants techniques de l'atelier, responsable, direction. */
    @Transactional
    public BesoinVue proposer(UUID besoinId, UUID articleId, DemandeLigne d) {
        UtilisateurConnecte.etablissementActif();
        BesoinAtelier b = chargerBesoin(besoinId);
        if (!acces.droits(b.getAtelierId()).signaler()) {
            throw new AccesRefuseException("Seuls les enseignants techniques de l'atelier expriment ses besoins");
        }
        charger(b.getCampagneId()).exigerOuverte();
        b.exigerBrouillon();
        Article a = catalogue.charger(articleId);
        if (!a.isActif()) {
            throw new RegleMetierException("ARTICLE_INACTIF", "Cet article n'est plus au catalogue");
        }
        BigDecimal quantite = quantite(d == null ? null : d.quantite(), a, false);
        LigneBesoin l = lignes.findByBesoinIdAndArticleId(besoinId, articleId).orElseGet(() -> new LigneBesoin(besoinId, articleId));
        l.demander(quantite, Textes.facultatif(d.justification(), "La justification", 300), UtilisateurConnecte.id(),
                horloge.instant());
        lignes.save(l);
        return vueBesoin(b);
    }

    @Transactional
    public BesoinVue retirer(UUID besoinId, UUID articleId) {
        UtilisateurConnecte.etablissementActif();
        BesoinAtelier b = chargerBesoin(besoinId);
        LigneBesoin l = lignes.findByBesoinIdAndArticleId(besoinId, articleId)
                .orElseThrow(() -> new RessourceIntrouvableException("Article absent des besoins"));
        boolean auteur = UtilisateurConnecte.id().equals(l.getProposePar()) && acces.droits(b.getAtelierId()).signaler();
        if (!auteur && !acces.droits(b.getAtelierId()).tenir()) {
            throw new AccesRefuseException("Seuls le responsable de l'atelier et l'auteur de la ligne la retirent");
        }
        charger(b.getCampagneId()).exigerOuverte();
        b.exigerBrouillon();
        lignes.delete(l);
        lignes.flush();
        return vueBesoin(b);
    }

    @Transactional
    public BesoinVue transmettreBesoin(UUID besoinId) {
        UtilisateurConnecte.etablissementActif();
        BesoinAtelier b = chargerBesoin(besoinId);
        acces.exigerTenue(b.getAtelierId());
        charger(b.getCampagneId()).exigerOuverte();
        if (lignes.findByBesoinId(besoinId).isEmpty()) {
            throw new RegleMetierException("BESOINS_VIDES", "Ajoutez au moins un article avant de transmettre");
        }
        b.transmettre(UtilisateurConnecte.id(), horloge.instant());
        audit.enregistrer("BESOINS_ATELIER_TRANSMIS", atelierCode(b), null);
        return vueBesoin(b);
    }

    @Transactional
    public BesoinVue renvoyer(UUID besoinId, DemandeRenvoi d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("renvoi des besoins");
        BesoinAtelier b = chargerBesoin(besoinId);
        charger(b.getCampagneId()).exigerOuverte();
        String commentaire = Textes.obligatoire(d == null ? null : d.commentaire(), "Le commentaire", 500);
        b.renvoyer(commentaire);
        return vueBesoin(b);
    }

    /** Quantités retenues par la direction ; une quantité vide reprend la quantité demandée. */
    @Transactional
    public BesoinVue arbitrer(UUID besoinId, DemandeArbitrage d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("arbitrage des besoins");
        BesoinAtelier b = chargerBesoin(besoinId);
        charger(b.getCampagneId()).exigerOuverte();
        b.exigerTransmis();
        Map<UUID, LigneBesoin> parArticle = lignes.findByBesoinId(besoinId).stream()
                .collect(Collectors.toMap(LigneBesoin::getArticleId, Function.identity()));
        for (SaisieArbitrage s : d == null || d.lignes() == null ? List.<SaisieArbitrage>of() : d.lignes()) {
            LigneBesoin l = parArticle.get(s.articleId());
            if (l == null) {
                throw new IllegalArgumentException("Article absent des besoins de l'atelier");
            }
            l.retenir(s.quantiteRetenue() == null ? null : quantite(s.quantiteRetenue(), catalogue.charger(s.articleId()), true));
        }
        return vueBesoin(b);
    }

    @Transactional
    public BesoinVue valider(UUID besoinId) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("validation des besoins");
        BesoinAtelier b = chargerBesoin(besoinId);
        charger(b.getCampagneId()).exigerOuverte();
        List<LigneBesoin> ls = lignes.findByBesoinId(besoinId);
        Map<UUID, Article> articles = catalogue.parId(ls.stream().map(LigneBesoin::getArticleId).toList());
        b.valider(UtilisateurConnecte.id(), horloge.instant());
        for (LigneBesoin l : ls) {
            if (l.getQuantiteRetenue() == null) {
                l.retenir(l.getQuantiteDemandee());
            }
            l.figerPrix(articles.get(l.getArticleId()).getPrixReference());
        }
        audit.enregistrer("BESOINS_ATELIER_VALIDES", atelierCode(b), null);
        return vueBesoin(b);
    }

    // ------------------------------------------------------------------ pour les commandes et les exports

    CampagneBesoins charger(UUID id) {
        return campagnes.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Campagne introuvable"));
    }

    BesoinAtelier chargerBesoin(UUID id) {
        return besoins.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Besoins introuvables"));
    }

    /** Quantité retenue par atelier et par article (besoins validés de la campagne). */
    Map<UUID, Map<UUID, BigDecimal>> retenuParArticleEtAtelier(UUID campagneId) {
        Map<UUID, Map<UUID, BigDecimal>> resultat = new LinkedHashMap<>();
        List<BesoinAtelier> bs = besoins.findByCampagneId(campagneId).stream()
                .filter(b -> b.getStatut() == StatutBesoin.VALIDE).toList();
        Map<UUID, UUID> atelierDuBesoin = bs.stream().collect(Collectors.toMap(BesoinAtelier::getId, BesoinAtelier::getAtelierId));
        for (LigneBesoin l : lignes.findByBesoinIdIn(atelierDuBesoin.keySet())) {
            resultat.computeIfAbsent(l.getArticleId(), k -> new LinkedHashMap<>())
                    .merge(atelierDuBesoin.get(l.getBesoinId()), l.quantiteFinale(), BigDecimal::add);
        }
        return resultat;
    }

    /** Consolidation par filière des besoins validés (pour la vue et l'état de la direction régionale). */
    List<FiliereConsolideeVue> consolider(CampagneBesoins c) {
        List<BesoinAtelier> bs = besoins.findByCampagneId(c.getId()).stream()
                .filter(b -> b.getStatut() == StatutBesoin.VALIDE).toList();
        Map<UUID, String> filiereDeLAtelier = libellesFilieres(bs.stream().map(BesoinAtelier::getAtelierId).toList());
        Map<UUID, UUID> atelierDuBesoin = bs.stream().collect(Collectors.toMap(BesoinAtelier::getId, BesoinAtelier::getAtelierId));
        List<LigneBesoin> ls = lignes.findByBesoinIdIn(atelierDuBesoin.keySet());
        Map<UUID, Article> articles = catalogue.parId(ls.stream().map(LigneBesoin::getArticleId).distinct().toList());
        Map<String, Map<UUID, List<LigneBesoin>>> groupes = new TreeMap<>();
        for (LigneBesoin l : ls) {
            String filiere = filiereDeLAtelier.getOrDefault(atelierDuBesoin.get(l.getBesoinId()), "Sans filière");
            groupes.computeIfAbsent(filiere, k -> new LinkedHashMap<>()).computeIfAbsent(l.getArticleId(), k -> new ArrayList<>()).add(l);
        }
        List<FiliereConsolideeVue> resultat = new ArrayList<>();
        for (var g : groupes.entrySet()) {
            List<LigneConsolideeVue> lignesFiliere = g.getValue().entrySet().stream()
                    .map(e -> consolidee(articles.get(e.getKey()), e.getValue(), null, null))
                    .sorted(Comparator.comparing(LigneConsolideeVue::nature).thenComparing(LigneConsolideeVue::designation))
                    .toList();
            resultat.add(new FiliereConsolideeVue(g.getKey(), lignesFiliere,
                    lignesFiliere.stream().mapToLong(LigneConsolideeVue::montant).sum()));
        }
        return resultat;
    }

    /** Totaux par article (toutes filières), avec ce qui est déjà commandé et livré conforme. */
    List<LigneConsolideeVue> totaux(CampagneBesoins c, Suivi suivi) {
        List<BesoinAtelier> bs = besoins.findByCampagneId(c.getId()).stream()
                .filter(b -> b.getStatut() == StatutBesoin.VALIDE).toList();
        List<LigneBesoin> ls = lignes.findByBesoinIdIn(bs.stream().map(BesoinAtelier::getId).toList());
        Map<UUID, Article> articles = catalogue.parId(ls.stream().map(LigneBesoin::getArticleId).distinct().toList());
        return ls.stream().collect(Collectors.groupingBy(LigneBesoin::getArticleId)).entrySet().stream()
                .map(e -> consolidee(articles.get(e.getKey()), e.getValue(), suivi.commandee().getOrDefault(e.getKey(), BigDecimal.ZERO),
                        suivi.livree().getOrDefault(e.getKey(), BigDecimal.ZERO)))
                .sorted(Comparator.comparing(LigneConsolideeVue::nature).thenComparing(LigneConsolideeVue::designation))
                .toList();
    }

    /** Quantités commandées et livrées conformes, par article. */
    record Suivi(Map<UUID, BigDecimal> commandee, Map<UUID, BigDecimal> livree) {
    }

    /** Quantités commandées (commandes non annulées) et livrées conformes, par article. */
    Suivi commandeEtLivre(UUID campagneId) {
        List<Commande> cs = commandes.findByCampagneIdOrderByDateCommandeAscCreeLeAsc(campagneId).stream()
                .filter(c -> c.getStatut() != StatutCommande.ANNULEE).toList();
        List<UUID> ids = cs.stream().map(Commande::getId).toList();
        Map<UUID, BigDecimal> commandee = new LinkedHashMap<>();
        for (LigneCommande l : lignesCommande.findByCommandeIdIn(ids)) {
            commandee.merge(l.getArticleId(), l.getQuantite(), BigDecimal::add);
        }
        Map<UUID, BigDecimal> livree = new LinkedHashMap<>();
        List<UUID> livs = livraisons.findByCommandeIdIn(ids).stream().map(Livraison::getId).toList();
        for (LigneLivraison l : lignesLivraison.findByLivraisonIdIn(livs)) {
            livree.merge(l.getArticleId(), l.getQuantiteConforme(), BigDecimal::add);
        }
        return new Suivi(commandee, livree);
    }

    Map<UUID, String> libellesFilieres(Collection<UUID> atelierIds) {
        Map<UUID, FiliereVue> fil = filieres.lister().stream().collect(Collectors.toMap(FiliereVue::id, Function.identity()));
        return liensFilieres.findByAtelierIdIn(atelierIds).stream()
                .filter(l -> fil.containsKey(l.getFiliereId()))
                .collect(Collectors.groupingBy(AtelierFiliere::getAtelierId, Collectors.mapping(
                        l -> fil.get(l.getFiliereId()).code() + " " + fil.get(l.getFiliereId()).libelle(),
                        Collectors.collectingAndThen(Collectors.toList(), x -> x.stream().sorted().collect(Collectors.joining(" / "))))));
    }

    static long montant(LigneBesoin l, Article a) {
        Long prix = l.getPrixUnitaire() != null ? l.getPrixUnitaire() : a == null ? null : a.getPrixReference();
        return prix == null ? 0 : l.quantiteFinale().multiply(BigDecimal.valueOf(prix)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static long montant(BigDecimal quantite, Long prix) {
        return prix == null || quantite == null ? 0
                : quantite.multiply(BigDecimal.valueOf(prix)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Quantité contrôlée : positive (ou nulle pour un arbitrage), entière pour un équipement. */
    static BigDecimal quantite(BigDecimal q, Article a, boolean zero) {
        BigDecimal v = Textes.quantite(q, "La quantité", zero);
        if (a.getNature() == NatureArticle.EQUIPEMENT && v.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Un équipement se compte à l'unité : quantité entière");
        }
        return v;
    }

    // ------------------------------------------------------------------ vues

    private void exigerLecture(UUID atelierId) {
        if (!acces.direction() && !acces.intendance()) {
            acces.exigerLecture(atelierId);
        }
    }

    private void completer(CampagneBesoins c) {
        Set<UUID> presents = besoins.findByCampagneId(c.getId()).stream().map(BesoinAtelier::getAtelierId)
                .collect(Collectors.toCollection(HashSet::new));
        for (Atelier a : ateliers.findAllByOrderByCodeAsc()) {
            if (a.isOuvert() && !presents.contains(a.getId())) {
                besoins.save(new BesoinAtelier(c.getId(), a.getId()));
            }
        }
        besoins.flush();
    }

    private String libelle(DemandeCampagne d, AnneeVue annee) {
        if (d.libelle() != null && !d.libelle().isBlank()) {
            return Textes.obligatoire(d.libelle(), "Le libellé", 120);
        }
        return d.type() == TypeCampagne.EXAMENS ? "Besoins des examens de fin d'études " + annee.libelle()
                : "Besoins de l'année scolaire " + annee.libelle();
    }

    private String atelierCode(BesoinAtelier b) {
        return ateliers.findById(b.getAtelierId()).map(Atelier::getCode).orElse("?");
    }

    private LigneConsolideeVue consolidee(Article a, List<LigneBesoin> ls, BigDecimal commandee, BigDecimal livree) {
        BigDecimal q = ls.stream().map(LigneBesoin::quantiteFinale).reduce(BigDecimal.ZERO, BigDecimal::add);
        Long prix = ls.stream().map(LigneBesoin::getPrixUnitaire).filter(p -> p != null).findFirst().orElse(a.getPrixReference());
        return new LigneConsolideeVue(a.getId(), a.getCode(), a.getDesignation(), a.getNature(), a.getUnite(), q, prix,
                montant(q, prix), commandee, livree);
    }

    private CampagneVue vue(CampagneBesoins c) {
        List<BesoinAtelier> bs = besoins.findByCampagneId(c.getId());
        Map<UUID, Atelier> parAtelier = ateliers.findAllById(bs.stream().map(BesoinAtelier::getAtelierId).toList()).stream()
                .collect(Collectors.toMap(Atelier::getId, Function.identity()));
        Map<UUID, List<FiliereCourte>> filieresParAtelier = filieresCourtes(parAtelier.keySet());
        Map<UUID, List<LigneBesoin>> parBesoin = lignes.findByBesoinIdIn(bs.stream().map(BesoinAtelier::getId).toList())
                .stream().collect(Collectors.groupingBy(LigneBesoin::getBesoinId));
        Map<UUID, Article> articles = catalogue.parId(parBesoin.values().stream().flatMap(List::stream)
                .map(LigneBesoin::getArticleId).distinct().toList());
        Map<UUID, UUID> responsables = mandats.findByFinIsNull().stream()
                .collect(Collectors.toMap(MandatResponsable::getAtelierId, MandatResponsable::getEngagementId));
        Map<UUID, String> nomsResp = noms.engagements(responsables.values());
        List<BesoinResumeVue> resumes = bs.stream().map(b -> {
            Atelier a = parAtelier.get(b.getAtelierId());
            List<LigneBesoin> ls = parBesoin.getOrDefault(b.getId(), List.of());
            UUID eng = responsables.get(a.getId());
            return new BesoinResumeVue(b.getId(), a.getId(), a.getCode(), a.getNom(),
                    filieresParAtelier.getOrDefault(a.getId(), List.of()), b.getStatut(), ls.size(),
                    ls.stream().mapToLong(l -> montant(l, articles.get(l.getArticleId()))).sum(), b.getTransmisLe(),
                    eng == null ? null : nomsResp.get(eng));
        }).sorted(Comparator.comparing(BesoinResumeVue::atelierCode)).toList();
        List<FiliereConsolideeVue> parFiliere = consolider(c);
        List<LigneConsolideeVue> tot = totaux(c, commandeEtLivre(c.getId()));
        List<Commande> cs = commandes.findByCampagneIdOrderByDateCommandeAscCreeLeAsc(c.getId());
        Map<UUID, List<LigneCommande>> lc = lignesCommande.findByCommandeIdIn(cs.stream().map(Commande::getId).toList())
                .stream().collect(Collectors.groupingBy(LigneCommande::getCommandeId));
        List<CommandeResumeVue> resumesCommandes = cs.stream().map(co -> {
            List<LigneCommande> ls = lc.getOrDefault(co.getId(), List.of());
            long m = ls.stream().mapToLong(l -> montant(l.getQuantite(), l.getPrixUnitaire())).sum();
            return new CommandeResumeVue(co.getId(), co.getReference(), co.getFournisseur(), co.getPasseePar(),
                    co.getDateCommande(), co.getStatut(), m, ls.size());
        }).toList();
        boolean gerer = acces.direction();
        return new CampagneVue(c.getId(), c.getAnneeId(), c.getType(), c.getLibelle(), c.getDateLimite(), c.getStatut(),
                c.getObservations(), c.getOuverteLe(), c.getTransmiseLe(), c.getCloseLe(), resumes, parFiliere, tot,
                tot.stream().mapToLong(LigneConsolideeVue::montant).sum(), resumesCommandes, gerer,
                (gerer || acces.intendance()) && c.getStatut() == StatutCampagne.TRANSMISE);
    }

    Map<UUID, List<FiliereCourte>> filieresCourtes(Collection<UUID> atelierIds) {
        Map<UUID, FiliereVue> fil = filieres.lister().stream().collect(Collectors.toMap(FiliereVue::id, Function.identity()));
        return liensFilieres.findByAtelierIdIn(atelierIds).stream().filter(l -> fil.containsKey(l.getFiliereId()))
                .collect(Collectors.groupingBy(AtelierFiliere::getAtelierId, Collectors.mapping(l -> {
                    FiliereVue f = fil.get(l.getFiliereId());
                    return new FiliereCourte(f.id(), f.code(), f.libelle());
                }, Collectors.toList())));
    }

    BesoinVue vueBesoin(BesoinAtelier b) {
        CampagneBesoins c = charger(b.getCampagneId());
        Atelier a = ateliers.findById(b.getAtelierId()).orElseThrow();
        List<LigneBesoin> ls = lignes.findByBesoinId(b.getId());
        Map<UUID, Article> articles = catalogue.parId(ls.stream().map(LigneBesoin::getArticleId).toList());
        Map<UUID, String> parNom = noms.utilisateurs();
        List<LigneBesoinVue> vues = ls.stream().map(l -> {
            Article art = articles.get(l.getArticleId());
            return new LigneBesoinVue(art.getId(), art.getCode(), art.getDesignation(), art.getNature(), art.getUnite(),
                    art.getSpecifications(), art.getNormes(), art.getPhotoType() != null, l.getQuantiteDemandee(),
                    l.getJustification(), l.getProposePar() == null ? null : parNom.get(l.getProposePar()),
                    l.getQuantiteRetenue(), l.getPrixUnitaire() != null ? l.getPrixUnitaire() : art.getPrixReference(),
                    montant(l, art));
        }).sorted(Comparator.comparing(LigneBesoinVue::nature).thenComparing(LigneBesoinVue::designation)).toList();
        var d = acces.droits(a.getId());
        boolean ouverte = c.getStatut() == StatutCampagne.OUVERTE;
        boolean brouillon = b.getStatut() == StatutBesoin.BROUILLON;
        DroitsBesoin droits = new DroitsBesoin(ouverte && brouillon && d.signaler(), ouverte && brouillon && d.tenir(),
                ouverte && brouillon && d.tenir(), ouverte && b.getStatut() == StatutBesoin.TRANSMIS && d.gerer());
        return new BesoinVue(b.getId(), c.getId(), c.getLibelle(), c.getType(), c.getStatut(), c.getDateLimite(), a.getId(),
                a.getCode(), a.getNom(), b.getStatut(), b.getTransmisLe(),
                b.getTransmisPar() == null ? null : parNom.get(b.getTransmisPar()), b.getValideLe(), b.getCommentaire(), vues,
                vues.stream().mapToLong(LigneBesoinVue::montant).sum(), droits);
    }

    /** Ligne de besoin enregistrée pour un article dans l'atelier ? (contrôle des répartitions) */
    Optional<BesoinAtelier> besoinDe(UUID campagneId, UUID atelierId) {
        return besoins.findByCampagneIdAndAtelierId(campagneId, atelierId);
    }
}
