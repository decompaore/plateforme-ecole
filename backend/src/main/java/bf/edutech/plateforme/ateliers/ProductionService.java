package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Activités de production des ateliers (v0.47.2) : commandes publiques ou privées (tables-bancs, tenues
 * scolaires…) que l'établissement exécute pour s'autofinancer. Le chef d'atelier les coordonne avec le chef des
 * travaux ; les enseignants y rattachent leurs séances de travaux pratiques, dont le compte rendu donne la quantité
 * produite. L'encaissement relève de l'intendance (hors de ce module).
 */
@Service
public class ProductionService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    public enum TypeClient {
        PUBLIC, PRIVE, INTERNE
    }

    public enum StatutProduction {
        EN_COURS, TERMINEE, LIVREE, ANNULEE
    }

    public record DonneesProduction(UUID atelierId, String client, TypeClient typeClient, String objet,
            BigDecimal quantite, String unite, Long montant, LocalDate dateCommande, LocalDate echeance,
            String observations) {
    }

    public record DemandeStatut(StatutProduction statut, LocalDate date, String observations) {
    }

    public record ProductionVue(UUID id, String numero, UUID atelierId, String atelierCode, String client,
            TypeClient typeClient, String objet, BigDecimal quantite, String unite, Long montant,
            LocalDate dateCommande, LocalDate echeance, StatutProduction statut, LocalDate livreeLe,
            String observations, BigDecimal quantiteProduite, int seances, boolean enRetard, boolean gerer) {
    }

    private final JdbcTemplate jdbc;
    private final AccesAteliers acces;
    private final AteliersService ateliers;
    private final Compteurs compteurs;
    private final AuditService audit;
    private final Clock horloge;

    ProductionService(JdbcTemplate jdbc, AccesAteliers acces, AteliersService ateliers, Compteurs compteurs,
            AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.ateliers = ateliers;
        this.compteurs = compteurs;
        this.audit = audit;
        this.horloge = horloge;
    }

    /**
     * Commandes visibles : toutes pour la direction des ateliers et l'intendance ; celles des ateliers où l'on est
     * responsable ou enseignant sinon.
     */
    @Transactional(readOnly = true)
    public List<ProductionVue> lister(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        if (atelierId != null) {
            ateliers.charger(atelierId);
            acces.exigerLecture(atelierId);
        }
        boolean tout = acces.direction() || acces.intendance();
        List<ProductionVue> liste = jdbc.query(select() + (atelierId != null ? " where p.atelier_id = ?" : "")
                + " order by p.statut = 'EN_COURS' desc, p.date_commande desc", this::vue,
                atelierId != null ? new Object[] { atelierId } : new Object[0]);
        if (tout || atelierId != null) {
            return liste;
        }
        return liste.stream().filter(p -> lisible(p.atelierId())).toList();
    }

    @Transactional(readOnly = true)
    public ProductionVue trouver(UUID id) {
        UtilisateurConnecte.etablissementActif();
        List<ProductionVue> l = jdbc.query(select() + " where p.id = ?", this::vue, id);
        if (l.isEmpty()) {
            throw new RessourceIntrouvableException("Commande de production introuvable");
        }
        ProductionVue p = l.get(0);
        if (!acces.direction() && !acces.intendance() && !lisible(p.atelierId())) {
            throw new AccesRefuseException("Vous n'êtes pas dans cet atelier");
        }
        return p;
    }

    @Transactional
    public ProductionVue creer(DonneesProduction d) {
        UtilisateurConnecte.etablissementActif();
        if (d == null || d.atelierId() == null) {
            throw new IllegalArgumentException("Indiquez l'atelier qui exécute la commande");
        }
        Atelier atelier = ateliers.charger(d.atelierId());
        acces.exigerTenue(d.atelierId());
        Valeurs v = valider(d);
        LocalDate dateCommande = d.dateCommande() != null ? d.dateCommande() : aujourdhui();
        String numero = "PR-" + dateCommande.getYear() + "-"
                + String.format("%03d", compteurs.suivant("production-" + dateCommande.getYear()));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into commande_production (tenant_id, id, numero, atelier_id, client, type_client, objet,
                    quantite, unite, montant, date_commande, echeance, observations, cree_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, numero, d.atelierId(),
                v.client, d.typeClient().name(), v.objet, v.quantite, v.unite, d.montant(), Date.valueOf(dateCommande),
                d.echeance() == null ? null : Date.valueOf(d.echeance()), v.observations, UtilisateurConnecte.id());
        audit.enregistrer("PRODUCTION_ENREGISTREE", numero, Map.of("atelier", atelier.getCode(), "client", v.client));
        return trouver(id);
    }

    @Transactional
    public ProductionVue modifier(UUID id, DonneesProduction d) {
        ProductionVue p = trouver(id);
        acces.exigerTenue(p.atelierId());
        if (p.statut() != StatutProduction.EN_COURS) {
            throw new RegleMetierException("PRODUCTION_CLOSE", "Seule une commande en cours se modifie");
        }
        Valeurs v = valider(d);
        LocalDate dateCommande = d.dateCommande() != null ? d.dateCommande() : p.dateCommande();
        jdbc.update("""
                update commande_production set client = ?, type_client = ?, objet = ?, quantite = ?, unite = ?,
                    montant = ?, date_commande = ?, echeance = ?, observations = ? where id = ?""", v.client,
                d.typeClient().name(), v.objet, v.quantite, v.unite, d.montant(), Date.valueOf(dateCommande),
                d.echeance() == null ? null : Date.valueOf(d.echeance()), v.observations, id);
        return trouver(id);
    }

    /** EN_COURS → TERMINEE → LIVREE (date de livraison) ; ANNULEE tant qu'elle n'est pas livrée. */
    @Transactional
    public ProductionVue changerStatut(UUID id, DemandeStatut d) {
        ProductionVue p = trouver(id);
        acces.exigerTenue(p.atelierId());
        if (d == null || d.statut() == null) {
            throw new IllegalArgumentException("Indiquez le nouveau statut");
        }
        StatutProduction actuel = p.statut();
        StatutProduction cible = d.statut();
        boolean permis = switch (cible) {
            case TERMINEE -> actuel == StatutProduction.EN_COURS;
            case LIVREE -> actuel == StatutProduction.EN_COURS || actuel == StatutProduction.TERMINEE;
            case ANNULEE -> actuel == StatutProduction.EN_COURS || actuel == StatutProduction.TERMINEE;
            case EN_COURS -> actuel == StatutProduction.TERMINEE;
        };
        if (!permis) {
            throw new RegleMetierException("STATUT_INVALIDE", "Passage de " + actuel + " à " + cible + " impossible");
        }
        LocalDate livree = null;
        if (cible == StatutProduction.LIVREE) {
            livree = d.date() != null ? d.date() : aujourdhui();
            if (livree.isAfter(aujourdhui()) || livree.isBefore(p.dateCommande())) {
                throw new IllegalArgumentException("Date de livraison entre la commande et aujourd'hui");
            }
        }
        String obs = Textes.facultatif(d.observations(), "Les observations", 1000);
        jdbc.update("""
                update commande_production set statut = ?, livree_le = ?,
                    observations = coalesce(?, observations) where id = ?""", cible.name(),
                livree == null ? null : Date.valueOf(livree), obs, id);
        audit.enregistrer("PRODUCTION_" + cible.name(), p.numero(), null);
        return trouver(id);
    }

    // ------------------------------------------------------------------

    private record Valeurs(String client, String objet, BigDecimal quantite, String unite, String observations) {
    }

    private Valeurs valider(DonneesProduction d) {
        if (d == null || d.typeClient() == null) {
            throw new IllegalArgumentException("Indiquez le type de client (PUBLIC, PRIVE, INTERNE)");
        }
        if (d.montant() != null && d.montant() < 0) {
            throw new IllegalArgumentException("Montant positif");
        }
        if (d.echeance() != null && d.dateCommande() != null && d.echeance().isBefore(d.dateCommande())) {
            throw new IllegalArgumentException("L'échéance suit la date de commande");
        }
        return new Valeurs(Textes.obligatoire(d.client(), "Le client", 200), Textes.obligatoire(d.objet(), "L'objet",
                300), Textes.quantite(d.quantite(), "La quantité", false), Textes.obligatoire(d.unite(), "L'unité", 30),
                Textes.facultatif(d.observations(), "Les observations", 1000));
    }

    private boolean lisible(UUID atelierId) {
        Set<UUID> responsable = acces.mesAteliersResponsable();
        return responsable.contains(atelierId) || acces.enseignantDe(atelierId);
    }

    private static String select() {
        return """
                select p.id, p.numero, p.atelier_id, a.code, p.client, p.type_client, p.objet, p.quantite, p.unite,
                       p.montant, p.date_commande, p.echeance, p.statut, p.livree_le, p.observations,
                       coalesce((select sum(s.quantite_produite) from seance_tp s where s.production_id = p.id
                                 and s.statut = 'REALISEE'), 0),
                       (select count(*) from seance_tp s where s.production_id = p.id
                        and s.statut in ('PLANIFIEE', 'REALISEE'))
                from commande_production p join atelier a on a.tenant_id = p.tenant_id and a.id = p.atelier_id""";
    }

    private ProductionVue vue(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        UUID atelierId = rs.getObject(3, UUID.class);
        Number montant = (Number) rs.getObject(10);
        java.sql.Date echeance = rs.getDate(12);
        java.sql.Date livree = rs.getDate(14);
        StatutProduction statut = StatutProduction.valueOf(rs.getString(13));
        boolean enRetard = echeance != null && statut == StatutProduction.EN_COURS
                && echeance.toLocalDate().isBefore(aujourdhui());
        return new ProductionVue(rs.getObject(1, UUID.class), rs.getString(2), atelierId, rs.getString(4),
                rs.getString(5), TypeClient.valueOf(rs.getString(6)), rs.getString(7), rs.getBigDecimal(8),
                rs.getString(9), montant == null ? null : montant.longValue(), rs.getDate(11).toLocalDate(),
                echeance == null ? null : echeance.toLocalDate(), statut,
                livree == null ? null : livree.toLocalDate(), rs.getString(15), rs.getBigDecimal(16), rs.getInt(17),
                enRetard, acces.droits(atelierId).tenir());
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(FUSEAU));
    }
}
