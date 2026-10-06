package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.DemandeMouvement;
import bf.edutech.plateforme.ateliers.Vues.DemandeSeuil;
import bf.edutech.plateforme.ateliers.Vues.LigneStockVue;
import bf.edutech.plateforme.ateliers.Vues.MouvementVue;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Stock de matière d'œuvre de chaque atelier : entrées (réception, dotation), sorties (travaux
 * pratiques, examens) et seuils d'alerte. Chaque mouvement est historisé avec le stock qui en résulte.
 * Pendant un inventaire, le stock est figé jusqu'à la clôture.
 */
@Service
public class StockService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final StockRepository stocks;
    private final MouvementRepository mouvements;
    private final InventaireRepository inventaires;
    private final AteliersService ateliers;
    private final CatalogueService catalogue;
    private final AccesAteliers acces;
    private final Noms noms;
    private final Clock horloge;

    StockService(StockRepository stocks, MouvementRepository mouvements, InventaireRepository inventaires,
            AteliersService ateliers, CatalogueService catalogue, AccesAteliers acces, Noms noms, Clock horloge) {
        this.stocks = stocks;
        this.mouvements = mouvements;
        this.inventaires = inventaires;
        this.ateliers = ateliers;
        this.catalogue = catalogue;
        this.acces = acces;
        this.noms = noms;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<LigneStockVue> stock(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerLecture(atelierId);
        List<StockAtelier> lignes = stocks.findByAtelierId(atelierId);
        Map<UUID, Article> articles = catalogue.parId(lignes.stream().map(StockAtelier::getArticleId).toList());
        return lignes.stream().map(s -> {
            Article a = articles.get(s.getArticleId());
            return new LigneStockVue(s.getArticleId(), a.getCode(), a.getDesignation(), a.getUnite(), s.getQuantite(),
                    s.getSeuilAlerte(), s.sousLeSeuil(), a.getPrixReference());
        }).sorted(Comparator.comparing(LigneStockVue::designation)).toList();
    }

    @Transactional
    public MouvementVue mouvement(UUID atelierId, DemandeMouvement d) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        if (d.articleId() == null || d.type() == null || d.type() == TypeMouvement.INVENTAIRE) {
            throw new IllegalArgumentException("Indiquez l'article et le type de mouvement (ENTREE ou SORTIE)");
        }
        exigerHorsInventaire(atelierId);
        Article article = catalogue.charger(d.articleId());
        if (article.getNature() != NatureArticle.MATIERE_OEUVRE) {
            throw new IllegalArgumentException("Les équipements s'inventorient un par un, pas en quantité");
        }
        BigDecimal quantite = Textes.quantite(d.quantite(), "La quantité", false);
        LocalDate date = d.date() == null ? LocalDate.now(horloge.withZone(FUSEAU)) : d.date();
        if (date.isAfter(LocalDate.now(horloge.withZone(FUSEAU)))) {
            throw new IllegalArgumentException("La date du mouvement ne peut pas être dans le futur");
        }
        StockAtelier stock = stocks.findByAtelierIdAndArticleId(atelierId, d.articleId()).orElse(null);
        if (d.type() == TypeMouvement.SORTIE) {
            BigDecimal disponible = stock == null ? BigDecimal.ZERO : stock.getQuantite();
            if (quantite.compareTo(disponible) > 0) {
                throw new RegleMetierException("STOCK_INSUFFISANT", "Stock insuffisant : " + disponible.stripTrailingZeros()
                        .toPlainString() + " " + article.getUnite() + " disponible(s)");
            }
        } else if (!article.isActif()) {
            throw new RegleMetierException("ARTICLE_INACTIF", "Cet article n'est plus au catalogue");
        }
        if (stock == null) {
            stock = stocks.save(new StockAtelier(atelierId, d.articleId()));
        }
        BigDecimal variation = d.type() == TypeMouvement.SORTIE ? quantite.negate() : quantite;
        stock.ajouter(variation);
        MouvementStock m = mouvements.save(new MouvementStock(atelierId, d.articleId(), d.type(), variation,
                stock.getQuantite(), date, Textes.facultatif(d.motif(), "Le motif", 200), null, UtilisateurConnecte.id(),
                horloge.instant()));
        return vue(m, article, noms.utilisateurs());
    }

    /** Seuil d'alerte (vide : pas d'alerte) ; ajoute l'article au stock de l'atelier s'il n'y était pas. */
    @Transactional
    public LigneStockVue definirSeuil(UUID atelierId, UUID articleId, DemandeSeuil d) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        Article a = catalogue.charger(articleId);
        if (a.getNature() != NatureArticle.MATIERE_OEUVRE) {
            throw new IllegalArgumentException("Le seuil d'alerte concerne la matière d'œuvre");
        }
        BigDecimal seuil = d == null || d.seuil() == null ? null : Textes.quantite(d.seuil(), "Le seuil", true);
        StockAtelier s = stocks.findByAtelierIdAndArticleId(atelierId, articleId)
                .orElseGet(() -> stocks.save(new StockAtelier(atelierId, articleId)));
        s.definirSeuil(seuil);
        return new LigneStockVue(articleId, a.getCode(), a.getDesignation(), a.getUnite(), s.getQuantite(),
                s.getSeuilAlerte(), s.sousLeSeuil(), a.getPrixReference());
    }

    @Transactional(readOnly = true)
    public List<MouvementVue> mouvements(UUID atelierId, UUID articleId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerLecture(atelierId);
        List<MouvementStock> liste = articleId == null ? mouvements.findTop200ByAtelierIdOrderByDateDescCreeLeDesc(atelierId)
                : mouvements.findByAtelierIdAndArticleIdOrderByDateDescCreeLeDesc(atelierId, articleId);
        Map<UUID, Article> articles = catalogue.parId(liste.stream().map(MouvementStock::getArticleId).distinct().toList());
        Map<UUID, String> parNom = noms.utilisateurs();
        return liste.stream().map(m -> vue(m, articles.get(m.getArticleId()), parNom)).toList();
    }

    /** Entrée en stock d'une livraison répartie par le chef des travaux (droits contrôlés par l'appelant). */
    void entreeLivraison(Atelier atelier, UUID articleId, BigDecimal quantite, LocalDate date, String motif) {
        if (inventaires.findByAtelierIdAndStatut(atelier.getId(), StatutInventaire.EN_COURS).isPresent()) {
            throw new RegleMetierException("INVENTAIRE_EN_COURS", "Un inventaire est en cours dans l'atelier "
                    + atelier.getCode() + " : clôturez-le avant de répartir la livraison");
        }
        StockAtelier stock = stocks.findByAtelierIdAndArticleId(atelier.getId(), articleId)
                .orElseGet(() -> stocks.save(new StockAtelier(atelier.getId(), articleId)));
        stock.ajouter(quantite);
        mouvements.save(new MouvementStock(atelier.getId(), articleId, TypeMouvement.ENTREE, quantite, stock.getQuantite(),
                date, motif, null, UtilisateurConnecte.id(), horloge.instant()));
    }

    void exigerHorsInventaire(UUID atelierId) {
        if (inventaires.findByAtelierIdAndStatut(atelierId, StatutInventaire.EN_COURS).isPresent()) {
            throw new RegleMetierException("INVENTAIRE_EN_COURS",
                    "Un inventaire est en cours : le stock est figé jusqu'à sa clôture");
        }
    }

    private static MouvementVue vue(MouvementStock m, Article a, Map<UUID, String> parNom) {
        return new MouvementVue(m.getId(), m.getArticleId(), a == null ? null : a.getDesignation(),
                a == null ? null : a.getUnite(), m.getType(), m.getQuantite(), m.getStockApres(), m.getDate(),
                m.getMotif(), m.getAuteur() == null ? null : parNom.get(m.getAuteur()));
    }
}
