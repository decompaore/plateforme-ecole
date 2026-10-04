package bf.edutech.plateforme.ateliers;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.ArticleVue;
import bf.edutech.plateforme.ateliers.Vues.DonneesArticle;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Catalogue des prix de l'établissement : matière d'œuvre et équipements, avec spécifications et
 * normes (fixées par les enseignants spécialistes), prix de référence (convenu avec l'intendant)
 * et photo facultative. Sert aux stocks, aux équipements et, plus tard, à l'expression des besoins.
 */
@Service
public class CatalogueService {

    static final int PHOTO_MAX = 2 * 1024 * 1024;
    private static final Set<String> TYPES_PHOTO = Set.of("image/jpeg", "image/png", "image/webp");

    public record Photo(String type, byte[] contenu) {
    }

    private final ArticleRepository articles;
    private final FilieresService filieres;
    private final AccesAteliers acces;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock horloge;

    CatalogueService(ArticleRepository articles, FilieresService filieres, AccesAteliers acces, JdbcTemplate jdbc,
            AuditService audit, Clock horloge) {
        this.articles = articles;
        this.filieres = filieres;
        this.acces = acces;
        this.jdbc = jdbc;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<ArticleVue> lister() {
        UtilisateurConnecte.etablissementActif();
        Map<UUID, FiliereVue> parId = filieresParId();
        return articles.findAllByOrderByNatureAscDesignationAsc().stream().map(a -> vue(a, parId)).toList();
    }

    @Transactional
    public ArticleVue creer(DonneesArticle d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerCatalogue();
        String code = Textes.code(d.code(), "Le code", 30);
        if (articles.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Un article porte déjà le code " + code);
        }
        if (d.nature() == null) {
            throw new IllegalArgumentException("Indiquez la nature : MATIERE_OEUVRE ou EQUIPEMENT");
        }
        Article a = new Article(code, d.nature());
        appliquer(a, d);
        articles.save(a);
        audit.enregistrer("ARTICLE_CREE", code, Map.of("nature", d.nature().name()));
        return vue(a, filieresParId());
    }

    /** Modification ; le code et la nature ne changent pas (le stock et les équipements y renvoient). */
    @Transactional
    public ArticleVue modifier(UUID id, DonneesArticle d) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerCatalogue();
        Article a = charger(id);
        Long ancienPrix = a.getPrixReference();
        appliquer(a, d);
        if (ancienPrix == null ? a.getPrixReference() != null : !ancienPrix.equals(a.getPrixReference())) {
            audit.enregistrer("PRIX_MODIFIE", a.getCode(), Map.of("ancien", ancienPrix == null ? "aucun" : ancienPrix,
                    "nouveau", a.getPrixReference() == null ? "aucun" : a.getPrixReference()));
        }
        return vue(a, filieresParId());
    }

    @Transactional
    public ArticleVue enregistrerPhoto(UUID id, String type, byte[] contenu) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerCatalogue();
        Article a = charger(id);
        if (type == null || !TYPES_PHOTO.contains(type)) {
            throw new IllegalArgumentException("Photo au format JPEG, PNG ou WebP");
        }
        if (contenu == null || contenu.length == 0 || contenu.length > PHOTO_MAX) {
            throw new IllegalArgumentException("Photo de 2 Mo au plus");
        }
        jdbc.update("""
                insert into photo_article (tenant_id, article_id, type, contenu) values (tenant_courant(), ?, ?, ?)
                on conflict (tenant_id, article_id) do update set type = excluded.type, contenu = excluded.contenu""",
                id, type, contenu);
        a.definirPhoto(type);
        return vue(a, filieresParId());
    }

    @Transactional
    public void supprimerPhoto(UUID id) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerCatalogue();
        Article a = charger(id);
        jdbc.update("delete from photo_article where tenant_id = tenant_courant() and article_id = ?", id);
        a.definirPhoto(null);
    }

    @Transactional(readOnly = true)
    public Photo photo(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("select type, contenu from photo_article where tenant_id = tenant_courant() and article_id = ?",
                (l, n) -> new Photo(l.getString(1), l.getBytes(2)), id)
                .stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Aucune photo pour cet article"));
    }

    // ------------------------------------------------------------------ pour les autres services du module

    Article charger(UUID id) {
        return articles.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Article introuvable"));
    }

    Map<UUID, Article> parId(java.util.Collection<UUID> ids) {
        return articles.findByIdIn(ids).stream().collect(Collectors.toMap(Article::getId, Function.identity()));
    }

    // ------------------------------------------------------------------

    private void appliquer(Article a, DonneesArticle d) {
        String designation = Textes.obligatoire(d.designation(), "La désignation", 150);
        String unite = Textes.obligatoire(d.unite(), "L'unité", 20);
        if (d.filiereId() != null && !filieresParId().containsKey(d.filiereId())) {
            throw new RessourceIntrouvableException("Filière introuvable");
        }
        a.definir(designation, unite, d.filiereId(), Textes.facultatif(d.specifications(), "Les spécifications", 2000),
                Textes.facultatif(d.normes(), "Les normes", 500), d.actif() == null || d.actif());
        a.definirPrix(Textes.montant(d.prixReference(), "Le prix de référence"), horloge.instant());
    }

    private Map<UUID, FiliereVue> filieresParId() {
        return filieres.lister().stream().collect(Collectors.toMap(FiliereVue::id, Function.identity()));
    }

    private static ArticleVue vue(Article a, Map<UUID, FiliereVue> filieres) {
        FiliereVue f = a.getFiliereId() == null ? null : filieres.get(a.getFiliereId());
        return new ArticleVue(a.getId(), a.getCode(), a.getDesignation(), a.getNature(), a.getUnite(),
                a.getFiliereId(), f == null ? null : f.code(), a.getSpecifications(), a.getNormes(),
                a.getPrixReference(), a.getPrixModifieLe(), a.isActif(), a.getPhotoType() != null);
    }
}
