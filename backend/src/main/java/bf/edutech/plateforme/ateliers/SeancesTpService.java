package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.DemandeMouvement;
import bf.edutech.plateforme.ateliers.Vues.DemandePanne;
import bf.edutech.plateforme.ateliers.Vues.DroitsAtelier;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.enseignants.StatutEngagement;
import bf.edutech.plateforme.enseignants.Vues.EnseignantVue;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.messagerie.MessagerieService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Séances de travaux pratiques dans les ateliers (v0.47.2) : la relation de travail entre le chef d'atelier
 * (responsable de l'atelier, autorité logistique et de sécurité) et l'enseignant (autorité pédagogique sur sa classe).
 * <ol>
 * <li><b>Planning</b> : l'enseignant demande un créneau ; le chef d'atelier le planifie (ou le refuse) en évitant les
 * conflits avec les autres séances et l'emploi du temps ; il peut aussi générer les séances depuis l'emploi du temps.
 * Le chef des travaux supervise le planning de tous les ateliers.</li>
 * <li><b>Fiche de préparation</b> : la matière d'œuvre demandée par l'enseignant ; le chef d'atelier accorde les
 * quantités ; ce qui manque au stock est signalé « à acheter » pour l'administration.</li>
 * <li><b>Mise à disposition</b> : le chef d'atelier prépare (sortie du stock, équipements vérifiés, accès remis).</li>
 * <li><b>Compte rendu</b> de l'enseignant : nettoyage, rangement, port des EPI, consommation réelle, pannes survenues
 * (fiches de panne rattachées à la séance), quantité produite pour une commande de production.</li>
 * <li><b>Bilan</b> du chef d'atelier : la matière non utilisée revient au stock.</li>
 * </ol>
 */
@Service
public class SeancesTpService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final int JOURS_MAX_GENERATION = 62;

    public record LigneBesoinTp(UUID articleId, BigDecimal quantite) {
    }

    public record DonneesSeanceTp(UUID classeId, UUID matiereId, LocalDate date, LocalTime heureDebut,
            LocalTime heureFin, String groupe, String titre, Integer effectif, UUID productionId,
            List<LigneBesoinTp> besoins) {
    }

    public record DemandeValidation(List<LigneBesoinTp> accordees) {
    }

    public record DemandeMotif(String motif) {
    }

    public record DemandePreparation(String observations) {
    }

    public record PanneSeance(UUID equipementId, String description) {
    }

    public record DemandeCompteRendu(Boolean nettoyage, Boolean rangement, Boolean epiRespectes,
            BigDecimal quantiteProduite, String observations, List<LigneBesoinTp> consommation,
            List<PanneSeance> pannes) {
    }

    public record DemandeGeneration(LocalDate du, LocalDate au) {
    }

    public record ResultatGeneration(int creees, int dejaPresentes, int sansEnseignant) {
    }

    public record LigneBesoinTpVue(UUID articleId, String code, String designation, String unite,
            BigDecimal demandee, BigDecimal accordee, BigDecimal aAcheter, BigDecimal servie, BigDecimal utilisee,
            BigDecimal rendue, BigDecimal stock) {
    }

    public record DroitsSeance(boolean modifier, boolean valider, boolean preparer, boolean compteRendu,
            boolean bilan, boolean annuler) {
    }

    public record EquipementIndisponible(UUID id, String numeroInventaire, String designation, String etat) {
    }

    public record SeanceTpVue(UUID id, UUID atelierId, String atelierCode, String atelier, UUID classeId,
            String classeCode, UUID matiereId, String matiere, UUID engagementId, String enseignant, LocalDate date,
            LocalTime heureDebut, LocalTime heureFin, String groupe, String titre, Integer effectif, UUID productionId,
            String production, String origine, String statut, String motif, Instant prepareeLe,
            String observationsPreparation, Instant realiseeLe, Boolean nettoyage, Boolean rangement,
            Boolean epiRespectes, BigDecimal quantiteProduite, String observations, Instant bilanLe,
            List<LigneBesoinTpVue> besoins, List<String> conflits, List<EquipementIndisponible> equipementsIndisponibles,
            DroitsSeance droits) {
    }

    /** Ligne brute de seance_tp (avec les libellés joints). */
    private record Ligne(UUID id, UUID atelierId, String atelierCode, String atelier, UUID classeId, String classeCode,
            UUID anneeId, UUID matiereId, String matiere, UUID engagementId, LocalDate date, LocalTime debut,
            LocalTime fin, String groupe, String titre, Integer effectif, UUID productionId, String production,
            String origine, String statut, String motif, Instant prepareeLe, String obsPreparation,
            Instant realiseeLe, Boolean nettoyage, Boolean rangement, Boolean epi, BigDecimal produite,
            String observations, Instant bilanLe) {
    }

    private static final String SELECT = """
            select s.id, s.atelier_id, a.code, a.nom, s.classe_id, c.code, c.annee_id, s.matiere_id, m.libelle,
                   s.engagement_id, s.date_seance, s.heure_debut, s.heure_fin, s.groupe, s.titre, s.effectif,
                   s.production_id, p.numero || ' · ' || p.objet, s.origine, s.statut, s.motif, s.preparee_le,
                   s.observations_preparation, s.realisee_le, s.nettoyage, s.rangement, s.epi_respectes,
                   s.quantite_produite, s.observations, s.bilan_le
            from seance_tp s
            join atelier a on a.tenant_id = s.tenant_id and a.id = s.atelier_id
            join classe c on c.tenant_id = s.tenant_id and c.id = s.classe_id
            join matiere m on m.tenant_id = s.tenant_id and m.id = s.matiere_id
            left join commande_production p on p.tenant_id = s.tenant_id and p.id = s.production_id
            """;

    private final JdbcTemplate jdbc;
    private final AccesAteliers acces;
    private final AteliersService ateliers;
    private final ClassesService classes;
    private final CatalogueService catalogue;
    private final StockService stock;
    private final EquipementsService equipements;
    private final EnseignantsService enseignants;
    private final MessagerieService messagerie;
    private final Noms noms;
    private final AuditService audit;
    private final Clock horloge;

    SeancesTpService(JdbcTemplate jdbc, AccesAteliers acces, AteliersService ateliers, ClassesService classes,
            CatalogueService catalogue, StockService stock, EquipementsService equipements,
            EnseignantsService enseignants, MessagerieService messagerie, Noms noms, AuditService audit,
            Clock horloge) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.ateliers = ateliers;
        this.classes = classes;
        this.catalogue = catalogue;
        this.stock = stock;
        this.equipements = equipements;
        this.enseignants = enseignants;
        this.messagerie = messagerie;
        this.noms = noms;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ lecture

    /** Planning d'un atelier entre deux dates (sept jours par défaut). */
    @Transactional(readOnly = true)
    public List<SeanceTpVue> planning(UUID atelierId, LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerLecture(atelierId);
        LocalDate[] p = periode(du, au);
        return vues(jdbc.query(SELECT + " where s.atelier_id = ? and s.date_seance between ? and ? "
                + "order by s.date_seance, s.heure_debut", this::ligne, atelierId, Date.valueOf(p[0]),
                Date.valueOf(p[1])));
    }

    /** Planning de tous les ateliers : la direction des ateliers (chef des travaux). */
    @Transactional(readOnly = true)
    public List<SeanceTpVue> planningGeneral(LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirection("planning de tous les ateliers");
        LocalDate[] p = periode(du, au);
        return vues(jdbc.query(SELECT + " where s.date_seance between ? and ? order by a.code, s.date_seance, "
                + "s.heure_debut", this::ligne, Date.valueOf(p[0]), Date.valueOf(p[1])));
    }

    /** Séances de l'enseignant connecté. */
    @Transactional(readOnly = true)
    public List<SeanceTpVue> miennes(LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        UUID moi = acces.monEngagement().orElseThrow(
                () -> new AccesRefuseException("Réservé aux enseignants"));
        LocalDate debut = du != null ? du : aujourdhui().minusDays(14);
        LocalDate fin = au != null ? au : aujourdhui().plusDays(60);
        return vues(jdbc.query(SELECT + " where s.engagement_id = ? and s.date_seance between ? and ? "
                + "order by s.date_seance, s.heure_debut", this::ligne, moi, Date.valueOf(debut), Date.valueOf(fin)));
    }

    @Transactional(readOnly = true)
    public SeanceTpVue trouver(UUID id) {
        Ligne l = charger(id);
        acces.exigerLecture(l.atelierId());
        return vues(List.of(l)).get(0);
    }

    public record OptionSeance(UUID classeId, String classeCode, UUID matiereId, String matiere, UUID engagementId,
            String enseignant) {
    }

    /**
     * Classes et matières (techniques, pratiques, modules) qu'on peut programmer dans l'atelier, pour l'année
     * active : toutes pour le chef d'atelier et la direction, les siennes pour un enseignant.
     */
    @Transactional(readOnly = true)
    public List<OptionSeance> options(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        DroitsAtelier d = acces.exigerLecture(atelierId);
        UUID moi = d.tenir() ? null : acces.monEngagement().orElse(null);
        if (!d.tenir() && moi == null) {
            return List.of();
        }
        java.util.Set<UUID> filieresAtelier = new java.util.HashSet<>(acces.filieresDe(atelierId));
        List<ClasseVue> lesClasses = new ArrayList<>();
        jdbc.query("select id from annee_scolaire where etat = 'ACTIVE'", (ResultSet rs) -> {
            lesClasses.addAll(classes.lister(rs.getObject(1, UUID.class)));
        });
        List<OptionSeance> options = new ArrayList<>();
        java.util.Set<UUID> engagements = new java.util.HashSet<>();
        for (ClasseVue c : lesClasses) {
            if (!filieresAtelier.contains(c.filiereId())) {
                continue;
            }
            for (MatiereDeClasseVue m : classes.matieres(c.id())) {
                if (m.type() == TypeMatiere.GENERALE || m.engagementId() == null
                        || (moi != null && !moi.equals(m.engagementId()))) {
                    continue;
                }
                engagements.add(m.engagementId());
                options.add(new OptionSeance(c.id(), c.code(), m.matiereId(), m.matiereLibelle(), m.engagementId(),
                        null));
            }
        }
        Map<UUID, String> nomsEns = noms.engagements(engagements);
        return options.stream().map(o -> new OptionSeance(o.classeId(), o.classeCode(), o.matiereId(), o.matiere(),
                o.engagementId(), nomsEns.get(o.engagementId())))
                .sorted(java.util.Comparator.comparing(OptionSeance::classeCode).thenComparing(OptionSeance::matiere))
                .toList();
    }

    // ------------------------------------------------------------------ planning

    /**
     * Demande (enseignant) ou planification directe (chef d'atelier, chef des travaux) d'une séance. L'enseignant
     * doit avoir pris connaissance des consignes de sécurité de l'atelier.
     */
    @Transactional
    public SeanceTpVue creer(UUID atelierId, DonneesSeanceTp d) {
        UtilisateurConnecte.etablissementActif();
        Atelier atelier = ateliers.charger(atelierId);
        DroitsAtelier droits = acces.exigerLecture(atelierId);
        if (!atelier.isOuvert()) {
            throw new RegleMetierException("ATELIER_FERME", "L'atelier " + atelier.getCode() + " est fermé");
        }
        if (d == null || d.classeId() == null || d.matiereId() == null || d.date() == null || d.heureDebut() == null
                || d.heureFin() == null) {
            throw new IllegalArgumentException("Indiquez la classe, la matière, la date et les heures de la séance");
        }
        boolean chef = droits.tenir();
        if (!chef && !droits.signaler()) {
            throw new AccesRefuseException("Vous n'enseignez pas dans cet atelier");
        }
        ClasseVue classe = classes.trouver(d.classeId());
        if (!acces.filieresDe(atelierId).contains(classe.filiereId())) {
            throw new RegleMetierException("CLASSE_HORS_ATELIER", "L'atelier " + atelier.getCode()
                    + " ne sert pas la filière de la classe " + classe.code());
        }
        MatiereDeClasseVue matiere = classes.matieres(classe.id()).stream()
                .filter(m -> m.matiereId().equals(d.matiereId())).findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Matière absente du programme de " + classe.code()));
        if (matiere.type() == TypeMatiere.GENERALE) {
            throw new RegleMetierException("MATIERE_GENERALE", "Les travaux pratiques concernent une matière technique, "
                    + "pratique ou un module");
        }
        if (matiere.engagementId() == null) {
            throw new RegleMetierException("SANS_ENSEIGNANT", matiere.matiereLibelle() + " n'a pas d'enseignant en "
                    + classe.code());
        }
        if (!chef) {
            UUID moi = acces.monEngagement().orElse(null);
            if (!matiere.engagementId().equals(moi)) {
                throw new AccesRefuseException("Vous n'enseignez pas " + matiere.matiereLibelle() + " en "
                        + classe.code());
            }
            exigerConsignesLues(atelierId, moi);
        }
        if (d.date().isBefore(aujourdhui())) {
            throw new IllegalArgumentException("Une séance se planifie pour aujourd'hui ou plus tard");
        }
        controlerHeures(d.heureDebut(), d.heureFin());
        String titre = Textes.obligatoire(d.titre(), "L'intitulé du TP", 200);
        String groupe = Textes.facultatif(d.groupe(), "Le groupe", 20);
        if (d.effectif() != null && (d.effectif() < 1 || d.effectif() > 200)) {
            throw new IllegalArgumentException("Effectif de 1 à 200 élèves");
        }
        controlerProduction(d.productionId(), atelierId);
        UUID id = UUID.randomUUID();
        String statut = chef ? "PLANIFIEE" : "DEMANDEE";
        if (chef) {
            List<String> conflits = conflits(null, atelierId, classe.id(), classe.anneeId(), d.date(), d.heureDebut(),
                    d.heureFin());
            if (!conflits.isEmpty()) {
                throw new RegleMetierException("CONFLIT_ATELIER", String.join(" ; ", conflits));
            }
        }
        jdbc.update("""
                insert into seance_tp (tenant_id, id, atelier_id, classe_id, matiere_id, engagement_id, date_seance,
                    heure_debut, heure_fin, groupe, titre, effectif, production_id, statut, demandee_par,
                    planifiee_par, planifiee_le)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, atelierId, classe.id(), d.matiereId(), matiere.engagementId(), Date.valueOf(d.date()),
                Time.valueOf(d.heureDebut()), Time.valueOf(d.heureFin()), groupe, titre, d.effectif(),
                d.productionId(), statut, UtilisateurConnecte.id(), chef ? UtilisateurConnecte.id() : null,
                chef ? Timestamp.from(horloge.instant()) : null);
        enregistrerBesoins(id, d.besoins(), false);
        audit.enregistrer(chef ? "SEANCE_TP_PLANIFIEE" : "SEANCE_TP_DEMANDEE", atelier.getCode(),
                Map.of("classe", classe.code(), "date", d.date().toString()));
        return trouver(id);
    }

    /** Modification d'une demande encore en attente (l'enseignant) ou d'une séance non préparée (chef d'atelier). */
    @Transactional
    public SeanceTpVue modifier(UUID id, DonneesSeanceTp d) {
        Ligne l = charger(id);
        DroitsSeance droits = droits(l);
        if (!droits.modifier()) {
            throw new RegleMetierException("SEANCE_NON_MODIFIABLE", "Cette séance ne se modifie plus");
        }
        if (d == null || d.date() == null || d.heureDebut() == null || d.heureFin() == null) {
            throw new IllegalArgumentException("Indiquez la date et les heures de la séance");
        }
        if (d.date().isBefore(aujourdhui())) {
            throw new IllegalArgumentException("Une séance se planifie pour aujourd'hui ou plus tard");
        }
        controlerHeures(d.heureDebut(), d.heureFin());
        String titre = Textes.obligatoire(d.titre(), "L'intitulé du TP", 200);
        controlerProduction(d.productionId(), l.atelierId());
        if (l.statut().equals("PLANIFIEE")) {
            List<String> conflits = conflits(id, l.atelierId(), l.classeId(), l.anneeId(), d.date(), d.heureDebut(),
                    d.heureFin());
            if (!conflits.isEmpty()) {
                throw new RegleMetierException("CONFLIT_ATELIER", String.join(" ; ", conflits));
            }
        }
        jdbc.update("""
                update seance_tp set date_seance = ?, heure_debut = ?, heure_fin = ?, groupe = ?, titre = ?,
                    effectif = ?, production_id = ? where id = ?""", Date.valueOf(d.date()),
                Time.valueOf(d.heureDebut()), Time.valueOf(d.heureFin()), Textes.facultatif(d.groupe(), "Le groupe", 20),
                titre, d.effectif(), d.productionId(), id);
        if (d.besoins() != null) {
            enregistrerBesoins(id, d.besoins(), true);
        }
        return trouver(id);
    }

    /** Validation par le chef d'atelier : contrôle des conflits, quantités accordées, manques à acheter. */
    @Transactional
    public SeanceTpVue valider(UUID id, DemandeValidation d) {
        Ligne l = charger(id);
        acces.exigerTenue(l.atelierId());
        if (!l.statut().equals("DEMANDEE")) {
            throw new RegleMetierException("SEANCE_NON_DEMANDEE", "Seule une séance demandée se valide");
        }
        List<String> conflits = conflits(id, l.atelierId(), l.classeId(), l.anneeId(), l.date(), l.debut(), l.fin());
        if (!conflits.isEmpty()) {
            throw new RegleMetierException("CONFLIT_ATELIER", String.join(" ; ", conflits));
        }
        Map<UUID, BigDecimal> accordees = new HashMap<>();
        if (d != null && d.accordees() != null) {
            for (LigneBesoinTp a : d.accordees()) {
                if (a.articleId() != null && a.quantite() != null) {
                    accordees.put(a.articleId(), Textes.quantite(a.quantite(), "La quantité accordée", true));
                }
            }
        }
        Map<UUID, BigDecimal> enStock = stockDe(l.atelierId());
        jdbc.query("select article_id, quantite_demandee from besoin_seance_tp where seance_id = ?",
                (ResultSet rs) -> {
                    UUID article = rs.getObject(1, UUID.class);
                    BigDecimal accordee = accordees.getOrDefault(article, rs.getBigDecimal(2));
                    BigDecimal manque = accordee.subtract(enStock.getOrDefault(article, BigDecimal.ZERO))
                            .max(BigDecimal.ZERO);
                    jdbc.update("""
                            update besoin_seance_tp set quantite_accordee = ?, quantite_a_acheter = ?
                            where seance_id = ? and article_id = ?""", accordee, manque, id, article);
                }, id);
        jdbc.update("update seance_tp set statut = 'PLANIFIEE', planifiee_par = ?, planifiee_le = ? where id = ?",
                UtilisateurConnecte.id(), Timestamp.from(horloge.instant()), id);
        audit.enregistrer("SEANCE_TP_PLANIFIEE", l.atelierCode(), Map.of("classe", l.classeCode(), "date",
                l.date().toString()));
        return trouver(id);
    }

    @Transactional
    public SeanceTpVue refuser(UUID id, DemandeMotif d) {
        Ligne l = charger(id);
        acces.exigerTenue(l.atelierId());
        if (!l.statut().equals("DEMANDEE")) {
            throw new RegleMetierException("SEANCE_NON_DEMANDEE", "Seule une séance demandée se refuse");
        }
        String motif = Textes.obligatoire(d == null ? null : d.motif(), "Le motif du refus", 500);
        jdbc.update("update seance_tp set statut = 'REFUSEE', motif = ? where id = ?", motif, id);
        prevenirEnseignant(l, "TP non retenu : " + l.titre(), "Votre demande de séance « " + l.titre() + " » ("
                + l.classeCode() + ", atelier " + l.atelierCode() + ", le " + l.date().format(JOUR) + " à "
                + l.debut() + ") n'est pas retenue par le chef d'atelier : " + motif);
        return trouver(id);
    }

    /** Annulation : l'enseignant (séance non préparée) ou le chef d'atelier, qui remet en stock la matière servie. */
    @Transactional
    public SeanceTpVue annuler(UUID id, DemandeMotif d) {
        Ligne l = charger(id);
        DroitsSeance droits = droits(l);
        if (!droits.annuler()) {
            if (l.prepareeLe() != null && (l.statut().equals("PLANIFIEE"))) {
                throw new RegleMetierException("SEANCE_PREPAREE",
                        "La séance est préparée : demandez son annulation au chef d'atelier");
            }
            throw new RegleMetierException("SEANCE_NON_ANNULABLE", "Cette séance ne s'annule plus");
        }
        String motif = Textes.obligatoire(d == null ? null : d.motif(), "Le motif de l'annulation", 500);
        if (l.prepareeLe() != null) {
            for (LigneBesoinTpVue b : besoins(id, l.atelierId())) {
                if (b.servie() != null && b.servie().signum() > 0) {
                    stock.mouvement(l.atelierId(), new DemandeMouvement(b.articleId(), TypeMouvement.ENTREE,
                            b.servie(), aujourdhui(), "Annulation TP " + l.classeCode() + " du " + l.date().format(JOUR)));
                    jdbc.update("update besoin_seance_tp set quantite_rendue = quantite_servie "
                            + "where seance_id = ? and article_id = ?", id, b.articleId());
                }
            }
        }
        jdbc.update("update seance_tp set statut = 'ANNULEE', motif = ? where id = ?", motif, id);
        return trouver(id);
    }

    /**
     * Génère les séances de l'emploi du temps de l'atelier entre deux dates (au plus 62 jours, à partir
     * d'aujourd'hui) : chaque séance hebdomadaire placée dans l'atelier devient une séance datée planifiée.
     */
    @Transactional
    public ResultatGeneration generer(UUID atelierId, DemandeGeneration d) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        if (d == null || d.du() == null || d.au() == null || d.au().isBefore(d.du())) {
            throw new IllegalArgumentException("Indiquez la période (du, au)");
        }
        LocalDate du = d.du().isBefore(aujourdhui()) ? aujourdhui() : d.du();
        if (ChronoUnit.DAYS.between(du, d.au()) + 1 > JOURS_MAX_GENERATION) {
            throw new IllegalArgumentException("Générez au plus " + JOURS_MAX_GENERATION + " jours à la fois");
        }
        record Hebdo(UUID classeId, UUID matiereId, int jour, LocalTime debut, LocalTime fin, String groupe,
                UUID engagementId, String matiere) {
        }
        List<Hebdo> hebdo = jdbc.query("""
                select se.classe_id, se.matiere_id, se.jour, cr.heure_debut, cr.heure_fin, se.groupe,
                       cm.engagement_id, m.libelle
                from seance_emploi se
                join annee_scolaire a on a.tenant_id = se.tenant_id and a.id = se.annee_id and a.etat = 'ACTIVE'
                join creneau_horaire cr on cr.tenant_id = se.tenant_id and cr.id = se.creneau_id
                join matiere m on m.tenant_id = se.tenant_id and m.id = se.matiere_id
                left join classe_matiere cm on cm.tenant_id = se.tenant_id and cm.classe_id = se.classe_id
                     and cm.matiere_id = se.matiere_id
                where se.atelier_id = ?""", (rs, n) -> new Hebdo(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getInt(3), rs.getTime(4).toLocalTime(), rs.getTime(5).toLocalTime(),
                rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8)), atelierId);
        int creees = 0;
        int deja = 0;
        int sans = 0;
        for (LocalDate jour = du; !jour.isAfter(d.au()); jour = jour.plusDays(1)) {
            int dow = jour.getDayOfWeek().getValue();
            for (Hebdo h : hebdo) {
                if (h.jour() != dow) {
                    continue;
                }
                if (h.engagementId() == null) {
                    sans++;
                    continue;
                }
                int n = jdbc.update("""
                        insert into seance_tp (tenant_id, id, atelier_id, classe_id, matiere_id, engagement_id,
                            date_seance, heure_debut, heure_fin, groupe, titre, origine, statut, demandee_par,
                            planifiee_par, planifiee_le)
                        values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EMPLOI_DU_TEMPS', 'PLANIFIEE', ?, ?, ?)
                        on conflict do nothing""", UUID.randomUUID(), atelierId, h.classeId(), h.matiereId(),
                        h.engagementId(), Date.valueOf(jour), Time.valueOf(h.debut()), Time.valueOf(h.fin()),
                        h.groupe(), "Travaux pratiques : " + h.matiere(), UtilisateurConnecte.id(),
                        UtilisateurConnecte.id(), Timestamp.from(horloge.instant()));
                if (n == 1) {
                    creees++;
                } else {
                    deja++;
                }
            }
        }
        return new ResultatGeneration(creees, deja, sans);
    }

    // ------------------------------------------------------------------ fiche de préparation et mise à disposition

    /** Mise à disposition par le chef d'atelier : matière servie (dans la limite du stock), équipements vérifiés. */
    @Transactional
    public SeanceTpVue preparer(UUID id, DemandePreparation d) {
        Ligne l = charger(id);
        acces.exigerTenue(l.atelierId());
        if (!l.statut().equals("PLANIFIEE")) {
            throw new RegleMetierException("SEANCE_NON_PLANIFIEE", "Seule une séance planifiée se prépare");
        }
        if (l.prepareeLe() != null) {
            throw new RegleMetierException("SEANCE_DEJA_PREPAREE", "La séance est déjà préparée");
        }
        Map<UUID, BigDecimal> enStock = stockDe(l.atelierId());
        for (LigneBesoinTpVue b : besoins(id, l.atelierId())) {
            BigDecimal voulu = b.accordee() != null ? b.accordee() : b.demandee();
            BigDecimal servie = voulu.min(enStock.getOrDefault(b.articleId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            if (servie.signum() > 0) {
                stock.mouvement(l.atelierId(), new DemandeMouvement(b.articleId(), TypeMouvement.SORTIE, servie,
                        aujourdhui(), "TP " + l.classeCode() + " du " + l.date().format(JOUR) + " : "
                                + tronquer(l.titre(), 120)));
            }
            jdbc.update("""
                    update besoin_seance_tp set quantite_accordee = ?, quantite_servie = ?, quantite_a_acheter = ?
                    where seance_id = ? and article_id = ?""", voulu, servie, voulu.subtract(servie), id,
                    b.articleId());
        }
        jdbc.update("""
                update seance_tp set preparee_par = ?, preparee_le = ?, observations_preparation = ? where id = ?""",
                UtilisateurConnecte.id(), Timestamp.from(horloge.instant()),
                Textes.facultatif(d == null ? null : d.observations(), "Les observations", 500), id);
        return trouver(id);
    }

    /** Compte rendu de l'enseignant après la séance. */
    @Transactional
    public SeanceTpVue compteRendu(UUID id, DemandeCompteRendu d) {
        Ligne l = charger(id);
        boolean enseignant = acces.monEngagement().map(e -> e.equals(l.engagementId())).orElse(false);
        if (!enseignant && !acces.droits(l.atelierId()).tenir()) {
            throw new AccesRefuseException("Le compte rendu revient à l'enseignant de la séance (ou au chef d'atelier)");
        }
        if (!l.statut().equals("PLANIFIEE")) {
            throw new RegleMetierException("SEANCE_NON_PLANIFIEE", "Seule une séance planifiée reçoit un compte rendu");
        }
        if (l.date().isAfter(aujourdhui())) {
            throw new RegleMetierException("SEANCE_A_VENIR", "Le compte rendu se fait après la séance");
        }
        if (d == null || d.nettoyage() == null || d.rangement() == null || d.epiRespectes() == null) {
            throw new IllegalArgumentException("Indiquez si l'atelier est nettoyé, l'outillage rangé et les EPI portés");
        }
        Map<UUID, LigneBesoinTpVue> parArticle = new LinkedHashMap<>();
        besoins(id, l.atelierId()).forEach(b -> parArticle.put(b.articleId(), b));
        if (d.consommation() != null) {
            for (LigneBesoinTp c : d.consommation()) {
                LigneBesoinTpVue b = parArticle.get(c.articleId());
                if (b == null || c.quantite() == null) {
                    continue;
                }
                BigDecimal utilisee = Textes.quantite(c.quantite(), "La quantité utilisée", true);
                BigDecimal servie = b.servie() == null ? BigDecimal.ZERO : b.servie();
                if (utilisee.compareTo(servie) > 0) {
                    throw new RegleMetierException("CONSOMMATION_EXCESSIVE", b.designation() + " : "
                            + utilisee.stripTrailingZeros().toPlainString() + " utilisé(s) pour "
                            + servie.stripTrailingZeros().toPlainString() + " servi(s)");
                }
                jdbc.update("update besoin_seance_tp set quantite_utilisee = ? where seance_id = ? and article_id = ?",
                        utilisee, id, c.articleId());
            }
        }
        BigDecimal produite = null;
        if (d.quantiteProduite() != null) {
            if (l.productionId() == null) {
                throw new IllegalArgumentException("La séance n'est pas rattachée à une commande de production");
            }
            produite = Textes.quantite(d.quantiteProduite(), "La quantité produite", true);
        }
        if (d.pannes() != null) {
            for (PanneSeance p : d.pannes()) {
                if (p == null || p.equipementId() == null) {
                    continue;
                }
                UUID atelierEquipement = jdbc.query("select atelier_id from equipement where id = ?",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null, p.equipementId());
                if (!l.atelierId().equals(atelierEquipement)) {
                    throw new IllegalArgumentException("Équipement inconnu dans cet atelier");
                }
                equipements.signaler(p.equipementId(), new DemandePanne(p.description()));
                jdbc.update("update panne set seance_tp_id = ? where equipement_id = ? and statut = 'OUVERTE'", id,
                        p.equipementId());
            }
        }
        jdbc.update("""
                update seance_tp set statut = 'REALISEE', realisee_le = ?, nettoyage = ?, rangement = ?,
                    epi_respectes = ?, quantite_produite = ?, observations = ? where id = ?""",
                Timestamp.from(horloge.instant()), d.nettoyage(), d.rangement(), d.epiRespectes(), produite,
                Textes.facultatif(d.observations(), "Les observations", 1000), id);
        return trouver(id);
    }

    /** Bilan de consommation par le chef d'atelier : la matière servie et non utilisée revient au stock. */
    @Transactional
    public SeanceTpVue bilan(UUID id) {
        Ligne l = charger(id);
        acces.exigerTenue(l.atelierId());
        if (!l.statut().equals("REALISEE")) {
            throw new RegleMetierException("SEANCE_NON_REALISEE", "Le bilan suit le compte rendu de l'enseignant");
        }
        if (l.bilanLe() != null) {
            throw new RegleMetierException("BILAN_DEJA_FAIT", "Le bilan de cette séance est déjà fait");
        }
        for (LigneBesoinTpVue b : besoins(id, l.atelierId())) {
            BigDecimal servie = b.servie() == null ? BigDecimal.ZERO : b.servie();
            BigDecimal utilisee = b.utilisee() == null ? servie : b.utilisee();
            BigDecimal rendue = servie.subtract(utilisee).max(BigDecimal.ZERO);
            if (rendue.signum() > 0) {
                stock.mouvement(l.atelierId(), new DemandeMouvement(b.articleId(), TypeMouvement.ENTREE, rendue,
                        aujourdhui(), "Retour TP " + l.classeCode() + " du " + l.date().format(JOUR)));
            }
            jdbc.update("""
                    update besoin_seance_tp set quantite_utilisee = ?, quantite_rendue = ?
                    where seance_id = ? and article_id = ?""", utilisee, rendue, id, b.articleId());
        }
        jdbc.update("update seance_tp set bilan_par = ?, bilan_le = ? where id = ?", UtilisateurConnecte.id(),
                Timestamp.from(horloge.instant()), id);
        return trouver(id);
    }

    /** Matière d'œuvre à acheter pour les séances à venir (administration, intendance). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> achats() {
        UtilisateurConnecte.etablissementActif();
        acces.exigerDirectionOuIntendance();
        return jdbc.queryForList("""
                select a.code as atelier, s.date_seance as date, c.code as classe, s.titre, ac.code as article,
                       ac.designation, ac.unite, b.quantite_a_acheter as quantite, ac.prix_reference as prix
                from besoin_seance_tp b
                join seance_tp s on s.tenant_id = b.tenant_id and s.id = b.seance_id
                join atelier a on a.tenant_id = s.tenant_id and a.id = s.atelier_id
                join classe c on c.tenant_id = s.tenant_id and c.id = s.classe_id
                join article_catalogue ac on ac.tenant_id = b.tenant_id and ac.id = b.article_id
                where b.quantite_a_acheter > 0 and s.statut = 'PLANIFIEE' and s.date_seance >= ?
                order by s.date_seance, a.code""", Date.valueOf(aujourdhui()));
    }

    // ------------------------------------------------------------------ règles

    /** Conflits avec les autres séances planifiées de l'atelier et l'emploi du temps des autres classes. */
    List<String> conflits(UUID seanceId, UUID atelierId, UUID classeId, UUID anneeId, LocalDate date,
            LocalTime debut, LocalTime fin) {
        List<String> conflits = new ArrayList<>(jdbc.query("""
                select c.code, s.heure_debut, s.heure_fin, s.titre from seance_tp s
                join classe c on c.tenant_id = s.tenant_id and c.id = s.classe_id
                where s.atelier_id = ? and s.date_seance = ? and s.statut in ('PLANIFIEE', 'REALISEE')
                  and s.heure_debut < ? and s.heure_fin > ? and (cast(? as uuid) is null or s.id <> ?)""",
                (rs, n) -> "atelier déjà occupé par " + rs.getString(1) + " de " + heure(rs.getTime(2)) + " à "
                        + heure(rs.getTime(3)) + " (" + rs.getString(4) + ")",
                atelierId, Date.valueOf(date), Time.valueOf(fin), Time.valueOf(debut), seanceId, seanceId));
        conflits.addAll(jdbc.query("""
                select c.code, cr.heure_debut, cr.heure_fin from seance_emploi se
                join creneau_horaire cr on cr.tenant_id = se.tenant_id and cr.id = se.creneau_id
                join classe c on c.tenant_id = se.tenant_id and c.id = se.classe_id
                where se.atelier_id = ? and se.annee_id = ? and se.jour = ? and se.classe_id <> ?
                  and cr.heure_debut < ? and cr.heure_fin > ?
                  and not exists (select 1 from seance_tp x where x.atelier_id = se.atelier_id
                                  and x.classe_id = se.classe_id and x.date_seance = ?
                                  and x.heure_debut = cr.heure_debut and x.statut in ('ANNULEE', 'REFUSEE'))
                  and not exists (select 1 from seance_tp y where y.atelier_id = se.atelier_id
                                  and y.classe_id = se.classe_id and y.date_seance = ?
                                  and y.heure_debut = cr.heure_debut and y.statut in ('PLANIFIEE', 'REALISEE'))""",
                (rs, n) -> "emploi du temps : " + rs.getString(1) + " de " + heure(rs.getTime(2)) + " à "
                        + heure(rs.getTime(3)),
                atelierId, anneeId, date.getDayOfWeek().getValue(), classeId, Time.valueOf(fin), Time.valueOf(debut),
                Date.valueOf(date), Date.valueOf(date)));
        return conflits;
    }

    private void exigerConsignesLues(UUID atelierId, UUID engagementId) {
        Boolean aJour = jdbc.queryForObject("""
                select not exists (select 1 from consigne_securite where atelier_id = ?)
                    or exists (select 1 from lecture_consignes l join atelier a on a.tenant_id = l.tenant_id
                               and a.id = l.atelier_id
                               where l.atelier_id = ? and l.engagement_id = ?
                                 and (a.consignes_modifiees_le is null or l.lu_le >= a.consignes_modifiees_le))""",
                Boolean.class, atelierId, atelierId, engagementId);
        if (!Boolean.TRUE.equals(aJour)) {
            throw new RegleMetierException("CONSIGNES_A_LIRE", "Prenez d'abord connaissance des consignes de sécurité "
                    + "de l'atelier (onglet Sécurité)");
        }
    }

    private void controlerProduction(UUID productionId, UUID atelierId) {
        if (productionId == null) {
            return;
        }
        List<Map<String, Object>> p = jdbc.queryForList(
                "select atelier_id, statut from commande_production where id = ?", productionId);
        if (p.isEmpty() || !atelierId.equals(p.get(0).get("atelier_id"))) {
            throw new IllegalArgumentException("Commande de production inconnue dans cet atelier");
        }
        if (!"EN_COURS".equals(p.get(0).get("statut"))) {
            throw new RegleMetierException("PRODUCTION_CLOSE", "Cette commande de production n'est plus en cours");
        }
    }

    private static void controlerHeures(LocalTime debut, LocalTime fin) {
        if (!fin.isAfter(debut)) {
            throw new IllegalArgumentException("L'heure de fin doit suivre l'heure de début");
        }
        if (ChronoUnit.MINUTES.between(debut, fin) > 8 * 60) {
            throw new IllegalArgumentException("Une séance dure 8 heures au plus");
        }
    }

    private void enregistrerBesoins(UUID seanceId, List<LigneBesoinTp> lignes, boolean remplacer) {
        if (remplacer) {
            jdbc.update("delete from besoin_seance_tp where seance_id = ?", seanceId);
        }
        if (lignes == null) {
            return;
        }
        Map<UUID, BigDecimal> parArticle = new LinkedHashMap<>();
        for (LigneBesoinTp b : lignes) {
            if (b == null || b.articleId() == null) {
                continue;
            }
            Article a = catalogue.charger(b.articleId());
            if (a.getNature() != NatureArticle.MATIERE_OEUVRE) {
                throw new IllegalArgumentException("La fiche de préparation porte sur la matière d'œuvre");
            }
            parArticle.merge(b.articleId(), Textes.quantite(b.quantite(), "La quantité demandée", false),
                    BigDecimal::add);
        }
        parArticle.forEach((article, q) -> jdbc.update("""
                insert into besoin_seance_tp (tenant_id, id, seance_id, article_id, quantite_demandee)
                values (tenant_courant(), ?, ?, ?, ?)""", UUID.randomUUID(), seanceId, article, q));
    }

    /** Droits de la personne connectée sur une séance. */
    private DroitsSeance droits(Ligne l) {
        DroitsAtelier d = acces.droits(l.atelierId());
        boolean chef = d.tenir();
        boolean enseignant = acces.monEngagement().map(e -> e.equals(l.engagementId())).orElse(false);
        String s = l.statut();
        boolean demandee = s.equals("DEMANDEE");
        boolean planifiee = s.equals("PLANIFIEE");
        boolean modifier = (demandee && (enseignant || chef)) || (planifiee && chef && l.prepareeLe() == null);
        boolean annuler = (demandee || planifiee) && (chef || (enseignant && l.prepareeLe() == null));
        boolean compteRendu = planifiee && (enseignant || chef) && !l.date().isAfter(aujourdhui());
        return new DroitsSeance(modifier, demandee && chef, planifiee && chef && l.prepareeLe() == null, compteRendu,
                s.equals("REALISEE") && chef && l.bilanLe() == null, annuler);
    }

    private List<SeanceTpVue> vues(List<Ligne> lignes) {
        if (lignes.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> enseignantsNoms = noms.engagements(lignes.stream().map(Ligne::engagementId).distinct()
                .toList());
        Map<UUID, Map<UUID, BigDecimal>> stocks = new HashMap<>();
        Map<UUID, List<EquipementIndisponible>> indisponibles = new HashMap<>();
        List<SeanceTpVue> vues = new ArrayList<>();
        for (Ligne l : lignes) {
            DroitsSeance droits = droits(l);
            Map<UUID, BigDecimal> st = stocks.computeIfAbsent(l.atelierId(), this::stockDe);
            List<LigneBesoinTpVue> besoins = besoins(l.id(), st);
            List<String> conflits = l.statut().equals("DEMANDEE")
                    ? conflits(l.id(), l.atelierId(), l.classeId(), l.anneeId(), l.date(), l.debut(), l.fin())
                    : List.of();
            List<EquipementIndisponible> eq = l.statut().equals("PLANIFIEE")
                    ? indisponibles.computeIfAbsent(l.atelierId(), this::equipementsIndisponibles)
                    : List.of();
            vues.add(new SeanceTpVue(l.id(), l.atelierId(), l.atelierCode(), l.atelier(), l.classeId(),
                    l.classeCode(), l.matiereId(), l.matiere(), l.engagementId(),
                    enseignantsNoms.get(l.engagementId()), l.date(), l.debut(), l.fin(), l.groupe(), l.titre(),
                    l.effectif(), l.productionId(), l.production(), l.origine(), l.statut(), l.motif(),
                    l.prepareeLe(), l.obsPreparation(), l.realiseeLe(), l.nettoyage(), l.rangement(), l.epi(),
                    l.produite(), l.observations(), l.bilanLe(), besoins, conflits, eq, droits));
        }
        return vues;
    }

    private List<LigneBesoinTpVue> besoins(UUID seanceId, UUID atelierId) {
        return besoins(seanceId, stockDe(atelierId));
    }

    private List<LigneBesoinTpVue> besoins(UUID seanceId, Map<UUID, BigDecimal> stockAtelier) {
        return jdbc.query("""
                select b.article_id, a.code, a.designation, a.unite, b.quantite_demandee, b.quantite_accordee,
                       b.quantite_a_acheter, b.quantite_servie, b.quantite_utilisee, b.quantite_rendue
                from besoin_seance_tp b join article_catalogue a on a.tenant_id = b.tenant_id and a.id = b.article_id
                where b.seance_id = ? order by a.designation""", (rs, n) -> new LigneBesoinTpVue(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                rs.getBigDecimal(10), stockAtelier.getOrDefault(rs.getObject(1, UUID.class), BigDecimal.ZERO)),
                seanceId);
    }

    private Map<UUID, BigDecimal> stockDe(UUID atelierId) {
        Map<UUID, BigDecimal> m = new HashMap<>();
        jdbc.query("select article_id, quantite from stock_atelier where atelier_id = ?",
                (ResultSet rs) -> {
                    m.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                }, atelierId);
        return m;
    }

    private List<EquipementIndisponible> equipementsIndisponibles(UUID atelierId) {
        return jdbc.query("""
                select id, numero_inventaire, designation, etat from equipement
                where atelier_id = ? and etat in ('EN_PANNE', 'MANQUANT') order by designation""",
                (rs, n) -> new EquipementIndisponible(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4)), atelierId);
    }

    private void prevenirEnseignant(Ligne l, String objet, String corps) {
        Optional<UUID> compte = enseignants.lister(StatutEngagement.ACTIF).stream()
                .filter(e -> e.engagementId().equals(l.engagementId())).map(EnseignantVue::utilisateurId)
                .filter(java.util.Objects::nonNull).findFirst();
        compte.ifPresent(u -> {
            try {
                messagerie.transmettre(u, objet, corps, "Atelier " + l.atelierCode());
            } catch (IllegalArgumentException e) {
                // Compte qui n'est plus membre du personnel : le refus reste visible dans « Mes TP »
            }
        });
    }

    private Ligne charger(UUID id) {
        UtilisateurConnecte.etablissementActif();
        List<Ligne> l = jdbc.query(SELECT + " where s.id = ?", this::ligne, id);
        if (l.isEmpty()) {
            throw new RessourceIntrouvableException("Séance de travaux pratiques introuvable");
        }
        return l.get(0);
    }

    private Ligne ligne(ResultSet rs, int n) throws SQLException {
        Timestamp prep = rs.getTimestamp(22);
        Timestamp real = rs.getTimestamp(24);
        Timestamp bil = rs.getTimestamp(30);
        Number effectifBrut = (Number) rs.getObject(16);
        Integer effectif = effectifBrut == null ? null : effectifBrut.intValue();
        return new Ligne(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
                rs.getString(9), rs.getObject(10, UUID.class), rs.getDate(11).toLocalDate(),
                rs.getTime(12).toLocalTime(), rs.getTime(13).toLocalTime(), rs.getString(14), rs.getString(15),
                effectif, rs.getObject(17, UUID.class), rs.getString(18), rs.getString(19),
                rs.getString(20), rs.getString(21), prep == null ? null : prep.toInstant(), rs.getString(23),
                real == null ? null : real.toInstant(), booleen(rs, 25), booleen(rs, 26), booleen(rs, 27),
                rs.getBigDecimal(28), rs.getString(29), bil == null ? null : bil.toInstant());
    }

    private static Boolean booleen(ResultSet rs, int i) throws SQLException {
        boolean b = rs.getBoolean(i);
        return rs.wasNull() ? null : b;
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(FUSEAU));
    }

    private LocalDate[] periode(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : aujourdhui();
        LocalDate fin = au != null ? au : debut.plusDays(6);
        if (fin.isBefore(debut) || ChronoUnit.DAYS.between(debut, fin) > 366) {
            throw new IllegalArgumentException("Période invalide (un an au plus)");
        }
        return new LocalDate[] { debut, fin };
    }

    private static String heure(Time t) {
        return t.toLocalTime().toString();
    }

    private static String tronquer(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
