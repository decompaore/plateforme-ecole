package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.VuesBesoins.AtelierCourt;
import bf.edutech.plateforme.ateliers.VuesBesoins.CommandeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeAnnulation;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeCommande;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeLivraison;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeRepartition;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneCommandeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LigneLivraisonVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LivraisonResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.LivraisonVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.PartAtelierVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.SaisieLigneCommande;
import bf.edutech.plateforme.ateliers.VuesBesoins.SaisieLigneLivraison;
import bf.edutech.plateforme.ateliers.VuesBesoins.SaisieRepartition;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Circuit des besoins, seconde partie : commandes passées par la direction régionale ou
 * l'établissement (direction, intendant), réception par le chef des travaux avec contrôle des
 * quantités et de la conformité aux spécifications et normes, puis répartition de ce qui est
 * conforme entre les ateliers (entrée en stock de la matière d'œuvre, fiches des équipements).
 */
@Service
public class CommandesService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final CommandeRepository commandes;
    private final LigneCommandeRepository lignesCommande;
    private final LivraisonRepository livraisons;
    private final LigneLivraisonRepository lignesLivraison;
    private final LigneRepartitionRepository repartitions;
    private final AtelierRepository ateliers;
    private final BesoinsService besoins;
    private final CatalogueService catalogue;
    private final StockService stock;
    private final EquipementsService equipements;
    private final AccesAteliers acces;
    private final Noms noms;
    private final AuditService audit;
    private final Clock horloge;

    CommandesService(CommandeRepository commandes, LigneCommandeRepository lignesCommande, LivraisonRepository livraisons,
            LigneLivraisonRepository lignesLivraison, LigneRepartitionRepository repartitions, AtelierRepository ateliers,
            BesoinsService besoins, CatalogueService catalogue, StockService stock, EquipementsService equipements,
            AccesAteliers acces, Noms noms, AuditService audit, Clock horloge) {
        this.commandes = commandes;
        this.lignesCommande = lignesCommande;
        this.livraisons = livraisons;
        this.lignesLivraison = lignesLivraison;
        this.repartitions = repartitions;
        this.ateliers = ateliers;
        this.besoins = besoins;
        this.catalogue = catalogue;
        this.stock = stock;
        this.equipements = equipements;
        this.acces = acces;
        this.noms = noms;
        this.audit = audit;
        this.horloge = horloge;
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(FUSEAU));
    }

    // ------------------------------------------------------------------ commandes

    @Transactional
    public CommandeVue creer(UUID campagneId, DemandeCommande d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirectionOuIntendance();
        CampagneBesoins c = besoins.charger(campagneId);
        if (c.getStatut() != StatutCampagne.TRANSMISE) {
            throw new RegleMetierException("CAMPAGNE_NON_TRANSMISE",
                    "Les commandes s'enregistrent une fois les besoins transmis à la direction régionale");
        }
        String reference = Textes.obligatoire(d.reference(), "La référence de la commande", 60).toUpperCase(Locale.ROOT);
        if (commandes.existsByReference(reference)) {
            throw new RegleMetierException("REFERENCE_EXISTANTE", "Une commande porte déjà la référence " + reference);
        }
        if (d.passeePar() == null) {
            throw new IllegalArgumentException("Indiquez qui passe la commande : DIRECTION_REGIONALE ou ETABLISSEMENT");
        }
        LocalDate date = d.dateCommande() == null ? aujourdhui() : d.dateCommande();
        if (date.isAfter(aujourdhui())) {
            throw new IllegalArgumentException("La date de la commande ne peut pas être dans le futur");
        }
        if (d.lignes() == null || d.lignes().isEmpty()) {
            throw new IllegalArgumentException("Ajoutez au moins un article à la commande");
        }
        Commande co = new Commande(campagneId, UtilisateurConnecte.id(), horloge.instant());
        co.definir(reference, Textes.obligatoire(d.fournisseur(), "Le fournisseur", 150), d.passeePar(), date,
                Textes.facultatif(d.observations(), "Les observations", 500));
        commandes.saveAndFlush(co);
        Set<UUID> vus = new HashSet<>();
        for (SaisieLigneCommande s : d.lignes()) {
            if (s.articleId() == null || !vus.add(s.articleId())) {
                throw new IllegalArgumentException("Chaque article figure une seule fois dans la commande");
            }
            Article a = catalogue.charger(s.articleId());
            BigDecimal q = BesoinsService.quantite(s.quantite(), a, false);
            Long prix = s.prixUnitaire() != null ? Textes.montant(s.prixUnitaire(), "Le prix unitaire") : a.getPrixReference();
            lignesCommande.save(new LigneCommande(co.getId(), a.getId(), q, prix == null ? 0 : prix));
        }
        audit.enregistrer("COMMANDE_ENREGISTREE", reference, Map.of("passeePar", d.passeePar().name()));
        return vue(co);
    }

    @Transactional(readOnly = true)
    public CommandeVue fiche(UUID id) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirectionOuIntendance();
        return vue(charger(id));
    }

    @Transactional
    public CommandeVue annuler(UUID id, DemandeAnnulation d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirectionOuIntendance();
        Commande co = charger(id);
        if (!livraisons.findByCommandeIdOrderByDateReceptionAscRecueLeAsc(id).isEmpty()) {
            throw new RegleMetierException("COMMANDE_LIVREE", "Une commande déjà livrée (même en partie) ne s'annule pas");
        }
        if (co.getStatut() == StatutCommande.ANNULEE) {
            throw new RegleMetierException("COMMANDE_ANNULEE", "Cette commande est déjà annulée");
        }
        co.annuler(Textes.obligatoire(d == null ? null : d.motif(), "Le motif", 200));
        audit.enregistrer("COMMANDE_ANNULEE", co.getReference(), null);
        return vue(co);
    }

    // ------------------------------------------------------------------ réception

    /** Réception : quantités reçues et conformes aux spécifications et normes (le reste est refusé avec un motif). */
    @Transactional
    public LivraisonVue recevoir(UUID commandeId, DemandeLivraison d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("réception d'une commande");
        Commande co = charger(commandeId);
        if (co.getStatut() == StatutCommande.ANNULEE || co.getStatut() == StatutCommande.LIVREE) {
            throw new RegleMetierException("COMMANDE_CLOSE", co.getStatut() == StatutCommande.ANNULEE
                    ? "Cette commande est annulée" : "Cette commande est entièrement livrée");
        }
        LocalDate date = d.dateReception() == null ? aujourdhui() : d.dateReception();
        if (date.isAfter(aujourdhui()) || date.isBefore(co.getDateCommande())) {
            throw new IllegalArgumentException("La date de réception est comprise entre la date de la commande et aujourd'hui");
        }
        Map<UUID, LigneCommande> commandees = lignesCommande.findByCommandeId(commandeId).stream()
                .collect(Collectors.toMap(LigneCommande::getArticleId, Function.identity()));
        Map<UUID, BigDecimal> dejaConforme = conformeParArticle(commandeId);
        List<SaisieLigneLivraison> saisies = d.lignes() == null ? List.of() : d.lignes().stream()
                .filter(s -> s.quantiteRecue() != null && s.quantiteRecue().signum() > 0).toList();
        if (saisies.isEmpty()) {
            throw new IllegalArgumentException("Indiquez au moins un article reçu");
        }
        Livraison l = livraisons.saveAndFlush(new Livraison(commandeId, date,
                Textes.facultatif(d.bonLivraison(), "Le numéro du bon de livraison", 60),
                Textes.facultatif(d.observations(), "Les observations", 500), UtilisateurConnecte.id(), horloge.instant()));
        Set<UUID> vus = new HashSet<>();
        for (SaisieLigneLivraison s : saisies) {
            LigneCommande lc = commandees.get(s.articleId());
            if (lc == null || !vus.add(s.articleId())) {
                throw new IllegalArgumentException("Article absent de la commande, ou saisi deux fois");
            }
            Article a = catalogue.charger(s.articleId());
            BigDecimal recue = BesoinsService.quantite(s.quantiteRecue(), a, false);
            BigDecimal conforme = s.quantiteConforme() == null ? recue : BesoinsService.quantite(s.quantiteConforme(), a, true);
            if (conforme.compareTo(recue) > 0) {
                throw new IllegalArgumentException(a.getDesignation() + " : la quantité conforme dépasse la quantité reçue");
            }
            String motif = Textes.facultatif(s.motifNonConformite(), "Le motif de non-conformité", 300);
            if (conforme.compareTo(recue) < 0 && motif == null) {
                throw new IllegalArgumentException(a.getDesignation()
                        + " : indiquez pourquoi une partie n'est pas conforme (spécifications, normes, état)");
            }
            BigDecimal total = dejaConforme.getOrDefault(a.getId(), BigDecimal.ZERO).add(conforme);
            if (total.compareTo(lc.getQuantite()) > 0) {
                throw new RegleMetierException("LIVRAISON_EXCEDENTAIRE", a.getDesignation() + " : "
                        + total.stripTrailingZeros().toPlainString() + " conformes pour "
                        + lc.getQuantite().stripTrailingZeros().toPlainString() + " commandés");
            }
            lignesLivraison.save(new LigneLivraison(l.getId(), a.getId(), recue, conforme, conforme.compareTo(recue) < 0 ? motif : null));
        }
        lignesLivraison.flush();
        actualiserStatut(co);
        audit.enregistrer("LIVRAISON_RECUE", co.getReference(), Map.of("bon", l.getBonLivraison() == null ? "" : l.getBonLivraison()));
        return vueLivraison(l);
    }

    @Transactional(readOnly = true)
    public LivraisonVue livraison(UUID id) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirectionOuIntendance();
        return vueLivraison(chargerLivraison(id));
    }

    /** Répartition entre les ateliers, enregistrée en brouillon (remplace la précédente). */
    @Transactional
    public LivraisonVue enregistrerRepartition(UUID livraisonId, DemandeRepartition d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("répartition d'une livraison");
        Livraison l = chargerLivraison(livraisonId);
        l.exigerARepartir();
        Map<UUID, LigneLivraison> recues = lignesLivraison.findByLivraisonId(livraisonId).stream()
                .collect(Collectors.toMap(LigneLivraison::getArticleId, Function.identity()));
        Map<UUID, Atelier> parId = ateliers.findAll().stream().collect(Collectors.toMap(Atelier::getId, Function.identity()));
        Map<UUID, BigDecimal> parArticle = new LinkedHashMap<>();
        List<LigneRepartition> nouvelles = new ArrayList<>();
        Set<String> vus = new HashSet<>();
        for (SaisieRepartition s : d == null || d.lignes() == null ? List.<SaisieRepartition>of() : d.lignes()) {
            if (s.quantite() == null || s.quantite().signum() == 0) {
                continue;
            }
            LigneLivraison r = recues.get(s.articleId());
            if (r == null || !parId.containsKey(s.atelierId()) || !vus.add(s.articleId() + "|" + s.atelierId())) {
                throw new IllegalArgumentException("Répartition invalide : article ou atelier inconnu, ou saisi deux fois");
            }
            Article a = catalogue.charger(s.articleId());
            BigDecimal q = BesoinsService.quantite(s.quantite(), a, false);
            BigDecimal total = parArticle.merge(s.articleId(), q, BigDecimal::add);
            if (total.compareTo(r.getQuantiteConforme()) > 0) {
                throw new RegleMetierException("REPARTITION_EXCEDENTAIRE", a.getDesignation() + " : "
                        + total.stripTrailingZeros().toPlainString() + " répartis pour "
                        + r.getQuantiteConforme().stripTrailingZeros().toPlainString() + " conformes");
            }
            nouvelles.add(new LigneRepartition(livraisonId, s.articleId(), s.atelierId(), q));
        }
        repartitions.supprimerDe(livraisonId);
        repartitions.flush();
        repartitions.saveAll(nouvelles);
        return vueLivraison(l);
    }

    /**
     * Valide la répartition : toute la quantité conforme doit être attribuée. La matière d'œuvre entre
     * dans le stock de chaque atelier ; chaque équipement reçoit sa fiche (numéro attribué automatiquement).
     */
    @Transactional
    public LivraisonVue validerRepartition(UUID livraisonId) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("répartition d'une livraison");
        Livraison l = chargerLivraison(livraisonId);
        l.exigerARepartir();
        Commande co = charger(l.getCommandeId());
        List<LigneLivraison> recues = lignesLivraison.findByLivraisonId(livraisonId);
        List<LigneRepartition> parts = repartitions.findByLivraisonId(livraisonId);
        Map<UUID, BigDecimal> reparti = new LinkedHashMap<>();
        parts.forEach(p -> reparti.merge(p.getArticleId(), p.getQuantite(), BigDecimal::add));
        Map<UUID, Article> articles = catalogue.parId(recues.stream().map(LigneLivraison::getArticleId).toList());
        List<String> incompletes = recues.stream()
                .filter(r -> r.getQuantiteConforme().compareTo(reparti.getOrDefault(r.getArticleId(), BigDecimal.ZERO)) != 0)
                .map(r -> articles.get(r.getArticleId()).getDesignation()).toList();
        if (!incompletes.isEmpty()) {
            throw new RegleMetierException("REPARTITION_INCOMPLETE",
                    "Répartissez toute la quantité conforme : " + String.join(", ", incompletes));
        }
        Map<UUID, Long> prix = lignesCommande.findByCommandeId(co.getId()).stream()
                .collect(Collectors.toMap(LigneCommande::getArticleId, LigneCommande::getPrixUnitaire));
        Map<UUID, Atelier> parAtelier = ateliers.findAllById(parts.stream().map(LigneRepartition::getAtelierId).toList())
                .stream().collect(Collectors.toMap(Atelier::getId, Function.identity()));
        String motif = "Livraison " + (l.getBonLivraison() == null ? "" : l.getBonLivraison() + " ") + "— commande " + co.getReference();
        for (LigneRepartition p : parts) {
            Article a = articles.get(p.getArticleId());
            Atelier atelier = parAtelier.get(p.getAtelierId());
            if (a.getNature() == NatureArticle.MATIERE_OEUVRE) {
                stock.entreeLivraison(atelier, a.getId(), p.getQuantite(), l.getDateReception(), Textes.facultatif(motif, "Le motif", 200));
            } else {
                equipements.creerDepuisLivraison(atelier, a, p.getQuantite().intValueExact(), l.getDateReception(),
                        prix.get(a.getId()), "Reçu : commande " + co.getReference());
            }
        }
        l.repartir(UtilisateurConnecte.id(), horloge.instant());
        audit.enregistrer("LIVRAISON_REPARTIE", co.getReference(), Map.of("lignes", parts.size()));
        return vueLivraison(l);
    }

    // ------------------------------------------------------------------ outils

    Commande charger(UUID id) {
        return commandes.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Commande introuvable"));
    }

    Livraison chargerLivraison(UUID id) {
        return livraisons.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Livraison introuvable"));
    }

    private Map<UUID, BigDecimal> conformeParArticle(UUID commandeId) {
        Map<UUID, BigDecimal> r = new LinkedHashMap<>();
        List<UUID> ids = livraisons.findByCommandeIdOrderByDateReceptionAscRecueLeAsc(commandeId).stream().map(Livraison::getId).toList();
        lignesLivraison.findByLivraisonIdIn(ids).forEach(l -> r.merge(l.getArticleId(), l.getQuantiteConforme(), BigDecimal::add));
        return r;
    }

    private Map<UUID, BigDecimal> recuParArticle(UUID commandeId) {
        Map<UUID, BigDecimal> r = new LinkedHashMap<>();
        List<UUID> ids = livraisons.findByCommandeIdOrderByDateReceptionAscRecueLeAsc(commandeId).stream().map(Livraison::getId).toList();
        lignesLivraison.findByLivraisonIdIn(ids).forEach(l -> r.merge(l.getArticleId(), l.getQuantiteRecue(), BigDecimal::add));
        return r;
    }

    private void actualiserStatut(Commande co) {
        Map<UUID, BigDecimal> conforme = conformeParArticle(co.getId());
        boolean complete = lignesCommande.findByCommandeId(co.getId()).stream()
                .allMatch(lc -> conforme.getOrDefault(lc.getArticleId(), BigDecimal.ZERO).compareTo(lc.getQuantite()) >= 0);
        co.changerStatut(complete ? StatutCommande.LIVREE : StatutCommande.LIVREE_PARTIELLEMENT);
    }

    CommandeVue vue(Commande co) {
        CampagneBesoins c = besoins.charger(co.getCampagneId());
        List<LigneCommande> ls = lignesCommande.findByCommandeId(co.getId());
        Map<UUID, Article> articles = catalogue.parId(ls.stream().map(LigneCommande::getArticleId).toList());
        Map<UUID, BigDecimal> recue = recuParArticle(co.getId());
        Map<UUID, BigDecimal> conforme = conformeParArticle(co.getId());
        List<LigneCommandeVue> lignes = ls.stream().map(l -> {
            Article a = articles.get(l.getArticleId());
            BigDecimal conf = conforme.getOrDefault(a.getId(), BigDecimal.ZERO);
            return new LigneCommandeVue(a.getId(), a.getCode(), a.getDesignation(), a.getNature(), a.getUnite(), l.getQuantite(),
                    l.getPrixUnitaire(), BesoinsService.montant(l.getQuantite(), l.getPrixUnitaire()),
                    recue.getOrDefault(a.getId(), BigDecimal.ZERO), conf, l.getQuantite().subtract(conf).max(BigDecimal.ZERO));
        }).sorted(Comparator.comparing(LigneCommandeVue::nature).thenComparing(LigneCommandeVue::designation)).toList();
        List<Livraison> livs = livraisons.findByCommandeIdOrderByDateReceptionAscRecueLeAsc(co.getId());
        Map<UUID, List<LigneLivraison>> parLiv = lignesLivraison.findByLivraisonIdIn(livs.stream().map(Livraison::getId).toList())
                .stream().collect(Collectors.groupingBy(LigneLivraison::getLivraisonId));
        List<LivraisonResumeVue> resumes = livs.stream().map(l -> {
            List<LigneLivraison> ll = parLiv.getOrDefault(l.getId(), List.of());
            return new LivraisonResumeVue(l.getId(), l.getDateReception(), l.getBonLivraison(), l.getStatut(), ll.size(),
                    (int) ll.stream().filter(x -> x.getQuantiteConforme().compareTo(x.getQuantiteRecue()) < 0).count());
        }).toList();
        boolean gerer = acces.direction() || acces.intendance();
        boolean ouverte = co.getStatut() == StatutCommande.EN_COURS || co.getStatut() == StatutCommande.LIVREE_PARTIELLEMENT;
        return new CommandeVue(co.getId(), c.getId(), c.getLibelle(), co.getReference(), co.getFournisseur(), co.getPasseePar(),
                co.getDateCommande(), co.getStatut(), co.getObservations(), co.getMotifAnnulation(), lignes,
                lignes.stream().mapToLong(LigneCommandeVue::montant).sum(), resumes,
                gerer && co.getStatut() == StatutCommande.EN_COURS && livs.isEmpty(), acces.direction() && ouverte);
    }

    LivraisonVue vueLivraison(Livraison l) {
        Commande co = charger(l.getCommandeId());
        List<LigneLivraison> ls = lignesLivraison.findByLivraisonId(l.getId());
        Map<UUID, Article> articles = catalogue.parId(ls.stream().map(LigneLivraison::getArticleId).toList());
        Map<UUID, Map<UUID, BigDecimal>> retenu = besoins.retenuParArticleEtAtelier(co.getCampagneId());
        Map<String, BigDecimal> enregistre = new LinkedHashMap<>();
        repartitions.findByLivraisonId(l.getId()).forEach(p -> enregistre.put(p.getArticleId() + "|" + p.getAtelierId(), p.getQuantite()));
        List<Atelier> tous = ateliers.findAllByOrderByCodeAsc();
        Map<UUID, Atelier> parId = tous.stream().collect(Collectors.toMap(Atelier::getId, Function.identity()));
        List<LigneLivraisonVue> lignes = ls.stream().map(r -> {
            Article a = articles.get(r.getArticleId());
            Map<UUID, BigDecimal> demandes = retenu.getOrDefault(a.getId(), Map.of());
            Map<UUID, BigDecimal> proposees = proposer(r.getQuantiteConforme(), demandes, a.getNature() == NatureArticle.EQUIPEMENT);
            Set<UUID> concernes = new java.util.LinkedHashSet<>(demandes.keySet());
            enregistre.keySet().stream().filter(k -> k.startsWith(a.getId() + "|"))
                    .forEach(k -> concernes.add(UUID.fromString(k.substring(k.indexOf('|') + 1))));
            List<PartAtelierVue> parts = concernes.stream().filter(parId::containsKey)
                    .map(id -> new PartAtelierVue(id, parId.get(id).getCode(), demandes.getOrDefault(id, BigDecimal.ZERO),
                            proposees.getOrDefault(id, BigDecimal.ZERO), enregistre.get(a.getId() + "|" + id)))
                    .sorted(Comparator.comparing(PartAtelierVue::atelierCode)).toList();
            return new LigneLivraisonVue(a.getId(), a.getCode(), a.getDesignation(), a.getNature(), a.getUnite(),
                    r.getQuantiteRecue(), r.getQuantiteConforme(), r.getMotifNonConformite(), parts);
        }).sorted(Comparator.comparing(LigneLivraisonVue::nature).thenComparing(LigneLivraisonVue::designation)).toList();
        Map<UUID, String> parNom = noms.utilisateurs();
        return new LivraisonVue(l.getId(), co.getId(), co.getReference(), co.getFournisseur(), co.getCampagneId(),
                l.getDateReception(), l.getBonLivraison(), l.getObservations(), l.getStatut(),
                l.getRecuePar() == null ? null : parNom.get(l.getRecuePar()), l.getRepartieLe(), lignes,
                tous.stream().filter(Atelier::isOuvert).map(a -> new AtelierCourt(a.getId(), a.getCode(), a.getNom())).toList(),
                acces.direction() && l.getStatut() == StatutLivraison.A_REPARTIR);
    }

    /**
     * Répartition proposée au prorata des besoins retenus de chaque atelier ; les équipements se répartissent
     * à l'unité, le reste de l'arrondi va aux plus gros besoins.
     */
    static Map<UUID, BigDecimal> proposer(BigDecimal conforme, Map<UUID, BigDecimal> demandes, boolean entier) {
        Map<UUID, BigDecimal> r = new LinkedHashMap<>();
        BigDecimal total = demandes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0 || conforme.signum() == 0) {
            return r;
        }
        int echelle = entier ? 0 : 2;
        BigDecimal attribue = BigDecimal.ZERO;
        for (var e : demandes.entrySet()) {
            BigDecimal part = conforme.multiply(e.getValue()).divide(total, echelle, RoundingMode.DOWN);
            r.put(e.getKey(), part);
            attribue = attribue.add(part);
        }
        BigDecimal reste = conforme.subtract(attribue);
        BigDecimal pas = entier ? BigDecimal.ONE : new BigDecimal("0.01");
        List<UUID> ordre = demandes.entrySet().stream().sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        int i = 0;
        while (reste.signum() > 0 && !ordre.isEmpty()) {
            BigDecimal ajout = reste.min(entier ? pas : reste);
            r.merge(ordre.get(i % ordre.size()), ajout, BigDecimal::add);
            reste = reste.subtract(ajout);
            i++;
        }
        return r;
    }
}
