package bf.edutech.plateforme.moyens;

import static bf.edutech.plateforme.moyens.EtatBesoinsService.instant;
import static bf.edutech.plateforme.moyens.EtatBesoinsService.vide;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.moyens.PorteeMoyens.Acteur;
import bf.edutech.plateforme.moyens.PorteeMoyens.Instance;
import bf.edutech.plateforme.moyens.RemonteesService.InstanceVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Dotations (v0.48) : le niveau central dote les directions du premier rang (régionales), chaque direction dote les
 * directions qui en dépendent directement (provinciales) et les établissements qui lui sont rattachés. Une dotation
 * peut répartir une dotation reçue : ses lignes sont alors bornées par les quantités conformes reçues. Le
 * destinataire accuse réception (quantités reçues et conformes, observations) ; aucune entrée en stock n'est faite
 * automatiquement.
 */
@Service
public class DotationsService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    public enum StatutDotation {
        BROUILLON, ENVOYEE, RECUE
    }

    public record LigneSaisie(UUID ligneOrigineId, Categorie categorie, String designation, String unite,
            BigDecimal quantite, Long valeurUnitaire) {
    }

    public record DonneesDotation(String annee, TypeInstance destinataireType, UUID destinataireId, UUID origineId,
            String objet, List<LigneSaisie> lignes) {
    }

    public record LigneRecue(UUID ligneId, BigDecimal quantiteRecue, BigDecimal quantiteConforme,
            String observation) {
    }

    public record Reception(String observations, List<LigneRecue> lignes) {
    }

    public record DotationResume(UUID id, String reference, String annee, InstanceVue emettrice,
            InstanceVue destinataire, String origineReference, String objet, StatutDotation statut, int lignes,
            Long valeur, Instant envoyeeLe, Instant recueLe) {
    }

    public record LigneDotationVue(UUID id, UUID ligneOrigineId, Categorie categorie, String designation,
            String unite, BigDecimal quantite, Long valeurUnitaire, BigDecimal quantiteRecue,
            BigDecimal quantiteConforme, String observation, BigDecimal repartie, BigDecimal disponible) {
    }

    public record DotationVue(UUID id, String reference, String annee, InstanceVue emettrice,
            InstanceVue destinataire, UUID origineId, String origineReference, String objet, StatutDotation statut,
            Instant creeeLe, Instant envoyeeLe, String envoyeePar, Instant recueLe, String accuseePar,
            String observationsReception, Long valeur, boolean peutModifier, boolean peutAccuser,
            boolean peutRepartir, List<LigneDotationVue> lignes, List<DotationResume> repartitions) {
    }

    public record Dotations(InstanceVue instance, String annee, List<String> annees, List<DotationResume> recues,
            List<DotationResume> emises, List<InstanceVue> destinataires, boolean peutDoter) {
    }

    private final JdbcTemplate jdbc;
    private final PorteeMoyens portee;
    private final AuditService audit;

    DotationsService(JdbcTemplate jdbc, PorteeMoyens portee, AuditService audit) {
        this.jdbc = jdbc;
        this.portee = portee;
        this.audit = audit;
    }

    // ================================================================== pilotage : lecture

    @Transactional(readOnly = true)
    public Dotations lister(UUID direction, UUID pays, String annee) {
        Acteur a = portee.acteur();
        Instance i = portee.cible(a, direction, pays);
        String filtre = annee == null || annee.isBlank() ? null : annee.strip();
        List<String> annees = jdbc.queryForList("""
                select distinct annee from dotation where emettrice_id = ? or (destinataire_id = ? and statut <> 'BROUILLON')
                order by annee desc""", String.class, i.id(), i.id());
        String parAnnee = filtre == null ? "" : " and d.annee = ?";
        Object[] p = filtre == null ? new Object[] { i.type().name(), i.id() }
                : new Object[] { i.type().name(), i.id(), filtre };
        List<DotationResume> recues = resumes(
                "d.destinataire_type = ? and d.destinataire_id = ? and d.statut <> 'BROUILLON'" + parAnnee, p);
        List<DotationResume> emises = resumes("d.emettrice_type = ? and d.emettrice_id = ?" + parAnnee, p);
        boolean doter = a.agit(i);
        List<InstanceVue> destinataires = doter ? destinataires(i).stream().map(this::vue).toList() : List.of();
        return new Dotations(vue(i), filtre, annees, recues, emises, destinataires, doter);
    }

    @Transactional(readOnly = true)
    public DotationVue detail(UUID id) {
        Acteur a = portee.acteur();
        Dotation d = charger(id);
        exigerVisible(a, d);
        return vue(d, a);
    }

    // ================================================================== pilotage : écriture

    @Transactional
    public DotationVue creer(DonneesDotation donnees) {
        Acteur a = exigerActeurQuiAgit();
        Preparee p = preparer(a, donnees, null);
        UUID id = UUID.randomUUID();
        String reference = "DOT-" + LocalDate.now(FUSEAU).getYear() + "-"
                + String.format("%05d", jdbc.queryForObject("select nextval('dotation_numero')", Long.class));
        jdbc.update("""
                insert into dotation (id, reference, annee, emettrice_type, emettrice_id, destinataire_type,
                    destinataire_id, origine_id, objet, creee_par) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, reference, p.annee, a.instance().type().name(), a.instance().id(), p.destinataire.type().name(),
                p.destinataire.id(), p.origine == null ? null : p.origine.id, p.objet, UtilisateurConnecte.id());
        inserer(id, p.lignes);
        audit.enregistrer("DOTATION_PREPAREE", reference, Map.of("destinataire", p.destinataire.nom()));
        return vue(charger(id), a);
    }

    @Transactional
    public DotationVue modifier(UUID id, DonneesDotation donnees) {
        Acteur a = exigerActeurQuiAgit();
        Dotation d = charger(id);
        exigerEmettrice(a, d);
        exigerBrouillon(d);
        Preparee p = preparer(a, donnees, id);
        jdbc.update("""
                update dotation set annee = ?, destinataire_type = ?, destinataire_id = ?, origine_id = ?, objet = ?
                where id = ?""", p.annee, p.destinataire.type().name(), p.destinataire.id(),
                p.origine == null ? null : p.origine.id, p.objet, id);
        jdbc.update("delete from ligne_dotation where dotation_id = ?", id);
        inserer(id, p.lignes);
        return vue(charger(id), a);
    }

    @Transactional
    public void supprimer(UUID id) {
        Acteur a = exigerActeurQuiAgit();
        Dotation d = charger(id);
        exigerEmettrice(a, d);
        exigerBrouillon(d);
        jdbc.update("delete from dotation where id = ?", id);
    }

    @Transactional
    public DotationVue envoyer(UUID id) {
        Acteur a = exigerActeurQuiAgit();
        Dotation d = charger(id);
        exigerEmettrice(a, d);
        exigerBrouillon(d);
        if (d.origineId != null) {
            // Les quantités disponibles ont pu changer depuis la préparation
            verifierDisponible(d.origineId, lignesSaisies(id), id);
        }
        jdbc.update("update dotation set statut = 'ENVOYEE', envoyee_le = now(), envoyee_par = ? where id = ?",
                UtilisateurConnecte.id(), id);
        audit.enregistrer("DOTATION_ENVOYEE", d.reference, null);
        return vue(charger(id), a);
    }

    /** Accusé de réception d'une direction destinataire. */
    @Transactional
    public DotationVue accuserDirection(UUID id, Reception reception) {
        Acteur a = exigerActeurQuiAgit();
        Dotation d = charger(id);
        if (!a.agit(new Instance(d.destinataireType, d.destinataireId, null))) {
            throw new AccesRefuseException("Seul le destinataire accuse réception de la dotation");
        }
        accuser(d, reception);
        return vue(charger(id), a);
    }

    // ================================================================== établissement

    @Transactional(readOnly = true)
    public List<DotationResume> recuesParEtablissement() {
        UUID tenant = UtilisateurConnecte.etablissementActif();
        return resumes("d.destinataire_type = 'ETABLISSEMENT' and d.destinataire_id = ? and d.statut <> 'BROUILLON'",
                tenant);
    }

    @Transactional(readOnly = true)
    public DotationVue detailEtablissement(UUID id) {
        return vue(dotationDeLEtablissement(id), null);
    }

    @Transactional
    public DotationVue accuserEtablissement(UUID id, Reception reception) {
        Dotation d = dotationDeLEtablissement(id);
        accuser(d, reception);
        return vue(charger(id), null);
    }

    // ================================================================== règles

    private record Dotation(UUID id, String reference, String annee, TypeInstance emettriceType, UUID emettriceId,
            TypeInstance destinataireType, UUID destinataireId, UUID origineId, String objet, StatutDotation statut,
            Instant creeeLe, Instant envoyeeLe, String envoyeePar, Instant recueLe, String accuseePar,
            String observationsReception) {
    }

    private record Preparee(String annee, Instance destinataire, Dotation origine, String objet,
            List<LigneSaisie> lignes) {
    }

    private Preparee preparer(Acteur a, DonneesDotation d, UUID enCours) {
        if (d == null || d.destinataireType() == null || d.destinataireId() == null) {
            throw new IllegalArgumentException("Destinataire obligatoire");
        }
        String objet = vide(d.objet());
        if (objet == null || objet.length() > 200) {
            throw new IllegalArgumentException("Objet obligatoire (200 caractères au plus)");
        }
        if (d.lignes() == null || d.lignes().isEmpty()) {
            throw new IllegalArgumentException("Au moins une ligne");
        }
        Instance emettrice = a.instance();
        Instance destinataire = destinataires(emettrice).stream()
                .filter(x -> x.type() == d.destinataireType() && x.id().equals(d.destinataireId())).findFirst()
                .orElseThrow(() -> new RegleMetierException("DESTINATAIRE_HORS_RESSORT", emettrice.type() == TypeInstance.PAYS
                        ? "Le niveau central dote les directions du premier rang"
                        : "Une direction dote les directions qui en dépendent directement et les établissements qui lui sont rattachés"));
        Dotation origine = null;
        String annee = vide(d.annee());
        List<LigneSaisie> lignes = new ArrayList<>();
        if (d.origineId() != null) {
            origine = jdbc.query(SELECT + " where d.id = ?", (l, n) -> dotation(l), d.origineId()).stream()
                    .findFirst().orElseThrow(() -> new RessourceIntrouvableException("Dotation d'origine introuvable"));
            if (!emettrice.est(origine.destinataireType, origine.destinataireId)
                    || origine.statut != StatutDotation.RECUE) {
                throw new RegleMetierException("ORIGINE_INVALIDE",
                        "Seule une dotation que vous avez reçue et dont vous avez accusé réception se répartit");
            }
            annee = origine.annee;
            Map<UUID, Object[]> lignesOrigine = new HashMap<>();
            jdbc.query("select id, categorie, designation, unite from ligne_dotation where dotation_id = ?", l -> {
                lignesOrigine.put(l.getObject(1, UUID.class),
                        new Object[] { l.getString(2), l.getString(3), l.getString(4) });
            }, origine.id);
            for (LigneSaisie s : d.lignes()) {
                Object[] o = s.ligneOrigineId() == null ? null : lignesOrigine.get(s.ligneOrigineId());
                if (o == null) {
                    throw new IllegalArgumentException("Chaque ligne d'une répartition reprend une ligne de la dotation reçue");
                }
                quantite(s.quantite());
                lignes.add(new LigneSaisie(s.ligneOrigineId(), Categorie.valueOf((String) o[0]), (String) o[1],
                        (String) o[2], s.quantite(), s.valeurUnitaire()));
            }
            verifierDisponible(origine.id, lignes, enCours);
        } else {
            for (LigneSaisie s : d.lignes()) {
                if (s.ligneOrigineId() != null) {
                    throw new IllegalArgumentException("Une ligne d'origine suppose une dotation d'origine");
                }
                if (s.categorie() == null) {
                    throw new IllegalArgumentException("Catégorie obligatoire");
                }
                String designation = vide(s.designation()) == null ? null : EtatBesoinsService.texte(s.designation());
                String unite = vide(s.unite()) == null ? null : EtatBesoinsService.texte(s.unite());
                if (designation == null || designation.length() > 200 || unite == null || unite.length() > 30) {
                    throw new IllegalArgumentException("Désignation (200 caractères) et unité (30) obligatoires");
                }
                quantite(s.quantite());
                lignes.add(new LigneSaisie(null, s.categorie(), designation, unite, s.quantite(), s.valeurUnitaire()));
            }
        }
        for (LigneSaisie s : lignes) {
            if (s.valeurUnitaire() != null && s.valeurUnitaire() < 0) {
                throw new IllegalArgumentException("Valeur unitaire négative");
            }
        }
        if (annee == null || annee.length() > 20) {
            throw new IllegalArgumentException("Année scolaire obligatoire (ex. 2026-2027)");
        }
        return new Preparee(annee, destinataire, origine, objet, lignes);
    }

    private static void quantite(BigDecimal q) {
        if (q == null || q.signum() <= 0) {
            throw new IllegalArgumentException("La quantité doit être positive");
        }
    }

    /** Quantités réparties (toutes répartitions, hors celle en cours) + demandées ≤ quantités conformes reçues. */
    private void verifierDisponible(UUID origine, List<LigneSaisie> lignes, UUID enCours) {
        Map<UUID, BigDecimal> demande = new HashMap<>();
        for (LigneSaisie s : lignes) {
            demande.merge(s.ligneOrigineId(), s.quantite(), BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> e : demande.entrySet()) {
            Object[] r = jdbc.queryForObject("""
                    select o.designation, o.unite, coalesce(o.quantite_conforme, o.quantite_recue, o.quantite),
                           (select coalesce(sum(l.quantite), 0) from ligne_dotation l join dotation x on x.id = l.dotation_id
                             where l.ligne_origine_id = o.id and x.id <> ?)
                    from ligne_dotation o where o.id = ? and o.dotation_id = ?""",
                    (l, n) -> new Object[] { l.getString(1), l.getString(2), l.getBigDecimal(3), l.getBigDecimal(4) },
                    enCours == null ? new UUID(0, 0) : enCours, e.getKey(), origine);
            BigDecimal reste = ((BigDecimal) r[2]).subtract((BigDecimal) r[3]);
            if (e.getValue().compareTo(reste) > 0) {
                throw new RegleMetierException("QUANTITE_DEPASSEE", "« " + r[0] + " » : il reste "
                        + reste.stripTrailingZeros().toPlainString() + " " + r[1] + " à répartir");
            }
        }
    }

    private void inserer(UUID dotation, List<LigneSaisie> lignes) {
        List<Object[]> valeurs = new ArrayList<>();
        for (LigneSaisie s : lignes) {
            valeurs.add(new Object[] { UUID.randomUUID(), dotation, s.ligneOrigineId(), valeurs.size() + 1,
                    s.categorie().name(), s.designation(), s.unite(), s.quantite(), s.valeurUnitaire() });
        }
        jdbc.batchUpdate("""
                insert into ligne_dotation (id, dotation_id, ligne_origine_id, ordre, categorie, designation, unite,
                    quantite, valeur_unitaire) values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", valeurs);
    }

    private List<LigneSaisie> lignesSaisies(UUID dotation) {
        return jdbc.query("""
                select ligne_origine_id, categorie, designation, unite, quantite, valeur_unitaire
                from ligne_dotation where dotation_id = ?""",
                (l, n) -> new LigneSaisie(l.getObject(1, UUID.class), Categorie.valueOf(l.getString(2)),
                        l.getString(3), l.getString(4), l.getBigDecimal(5), l.getObject(6, Long.class)),
                dotation);
    }

    private void accuser(Dotation d, Reception reception) {
        if (d.statut == StatutDotation.RECUE) {
            throw new RegleMetierException("DEJA_ACCUSEE", "La réception de cette dotation a déjà été accusée");
        }
        if (d.statut != StatutDotation.ENVOYEE) {
            throw new RessourceIntrouvableException("Dotation introuvable");
        }
        Map<UUID, LigneRecue> saisies = new HashMap<>();
        if (reception != null && reception.lignes() != null) {
            for (LigneRecue l : reception.lignes()) {
                saisies.put(l.ligneId(), l);
            }
        }
        Map<UUID, BigDecimal> prevues = new HashMap<>();
        jdbc.query("select id, quantite from ligne_dotation where dotation_id = ?",
                l -> {
                    prevues.put(l.getObject(1, UUID.class), l.getBigDecimal(2));
                }, d.id);
        if (!prevues.keySet().containsAll(saisies.keySet())) {
            throw new IllegalArgumentException("Ligne étrangère à cette dotation");
        }
        for (Map.Entry<UUID, BigDecimal> e : prevues.entrySet()) {
            LigneRecue s = saisies.get(e.getKey());
            BigDecimal recue = s == null || s.quantiteRecue() == null ? e.getValue() : s.quantiteRecue();
            BigDecimal conforme = s == null || s.quantiteConforme() == null ? recue : s.quantiteConforme();
            String observation = s == null ? null : vide(s.observation());
            if (recue.signum() < 0 || conforme.signum() < 0) {
                throw new IllegalArgumentException("Quantités négatives");
            }
            if (conforme.compareTo(recue) > 0) {
                throw new IllegalArgumentException("La quantité conforme ne dépasse pas la quantité reçue");
            }
            if (observation != null && observation.length() > 300) {
                throw new IllegalArgumentException("Observation de 300 caractères au plus");
            }
            jdbc.update("update ligne_dotation set quantite_recue = ?, quantite_conforme = ?, observation = ? where id = ?",
                    recue, conforme, observation, e.getKey());
        }
        String observations = reception == null ? null : vide(reception.observations());
        if (observations != null && observations.length() > 1000) {
            throw new IllegalArgumentException("Observations de 1000 caractères au plus");
        }
        jdbc.update("""
                update dotation set statut = 'RECUE', recue_le = now(), accusee_par = ?, observations_reception = ?
                where id = ?""", UtilisateurConnecte.id(), observations, d.id);
        audit.enregistrer("DOTATION_RECUE", d.reference, null);
    }

    /** Destinataires possibles d'une dotation émise par cette instance. */
    private List<Instance> destinataires(Instance emettrice) {
        List<Instance> r = new ArrayList<>(portee.enfants(emettrice));
        if (emettrice.type() == TypeInstance.DIRECTION) {
            r.addAll(portee.etablissementsRattaches(emettrice.id()));
        }
        return r;
    }

    private Acteur exigerActeurQuiAgit() {
        return portee.acteur();
    }

    private static void exigerEmettrice(Acteur a, Dotation d) {
        if (!a.agit(new Instance(d.emettriceType, d.emettriceId, null))) {
            throw new AccesRefuseException("Seul l'émetteur modifie ou envoie la dotation");
        }
    }

    private static void exigerBrouillon(Dotation d) {
        if (d.statut != StatutDotation.BROUILLON) {
            throw new RegleMetierException("DOTATION_ENVOYEE", "La dotation a été envoyée : elle n'est plus modifiable");
        }
    }

    /** v0.48.1 : une dotation n'est visible que de son émetteur et, une fois envoyée, de son destinataire. */
    private static void exigerVisible(Acteur a, Dotation d) {
        boolean emettrice = a.agit(new Instance(d.emettriceType, d.emettriceId, null));
        boolean destinataire = d.statut != StatutDotation.BROUILLON
                && a.agit(new Instance(d.destinataireType, d.destinataireId, null));
        if (!emettrice && !destinataire) {
            throw new RessourceIntrouvableException("Dotation introuvable");
        }
    }

    private Dotation dotationDeLEtablissement(UUID id) {
        UUID tenant = UtilisateurConnecte.etablissementActif();
        Dotation d = charger(id);
        if (d.destinataireType != TypeInstance.ETABLISSEMENT || !d.destinataireId.equals(tenant)
                || d.statut == StatutDotation.BROUILLON) {
            throw new RessourceIntrouvableException("Dotation introuvable");
        }
        return d;
    }

    // ================================================================== lecture

    private static final String SELECT = """
            select d.id, d.reference, d.annee, d.emettrice_type, d.emettrice_id, d.destinataire_type, d.destinataire_id,
                   d.origine_id, d.objet, d.statut, d.creee_le, d.envoyee_le, ue.nom || ' ' || ue.prenoms, d.recue_le,
                   ua.nom || ' ' || ua.prenoms, d.observations_reception
            from dotation d left join utilisateur ue on ue.id = d.envoyee_par
            left join utilisateur ua on ua.id = d.accusee_par""";

    private static Dotation dotation(java.sql.ResultSet l) throws java.sql.SQLException {
        return new Dotation(l.getObject(1, UUID.class), l.getString(2), l.getString(3),
                TypeInstance.valueOf(l.getString(4)), l.getObject(5, UUID.class), TypeInstance.valueOf(l.getString(6)),
                l.getObject(7, UUID.class), l.getObject(8, UUID.class), l.getString(9),
                StatutDotation.valueOf(l.getString(10)), instant(l.getTimestamp(11)), instant(l.getTimestamp(12)),
                l.getString(13), instant(l.getTimestamp(14)), l.getString(15), l.getString(16));
    }

    private Dotation charger(UUID id) {
        return jdbc.query(SELECT + " where d.id = ?", (l, n) -> dotation(l), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Dotation introuvable"));
    }

    private List<DotationResume> resumes(String condition, Object... parametres) {
        List<Dotation> ds = jdbc.query(SELECT + " where " + condition + " order by d.creee_le desc",
                (l, n) -> dotation(l), parametres);
        List<DotationResume> r = new ArrayList<>();
        Map<String, InstanceVue> cache = new HashMap<>();
        for (Dotation d : ds) {
            r.add(resume(d, cache));
        }
        return r;
    }

    private DotationResume resume(Dotation d, Map<String, InstanceVue> cache) {
        Object[] t = jdbc.queryForObject("""
                select count(*), sum(quantite * valeur_unitaire) from ligne_dotation where dotation_id = ?""",
                (l, n) -> new Object[] { l.getInt(1), l.getBigDecimal(2) }, d.id);
        String origine = d.origineId == null ? null
                : jdbc.queryForObject("select reference from dotation where id = ?", String.class, d.origineId);
        return new DotationResume(d.id, d.reference, d.annee, instanceVue(d.emettriceType, d.emettriceId, cache),
                instanceVue(d.destinataireType, d.destinataireId, cache), origine, d.objet, d.statut, (Integer) t[0],
                t[1] == null ? null : ((BigDecimal) t[1]).longValue(), d.envoyeeLe, d.recueLe);
    }

    private InstanceVue instanceVue(TypeInstance type, UUID id, Map<String, InstanceVue> cache) {
        return cache.computeIfAbsent(type + ":" + id, k -> {
            Instance i = portee.instance(type, id);
            return new InstanceVue(type, id, i.nom(), null);
        });
    }

    private InstanceVue vue(Instance i) {
        return new InstanceVue(i.type(), i.id(), i.nom(), null);
    }

    private DotationVue vue(Dotation d, Acteur a) {
        // v0.48.1 : la répartition d'une dotation reçue ne regarde que son destinataire (l'émetteur ne la voit pas)
        boolean repartiteur = a != null && a.agit(new Instance(d.destinataireType, d.destinataireId, null));
        List<LigneDotationVue> lignes = jdbc.query("""
                select l.id, l.ligne_origine_id, l.categorie, l.designation, l.unite, l.quantite, l.valeur_unitaire,
                       l.quantite_recue, l.quantite_conforme, l.observation,
                       (select coalesce(sum(x.quantite), 0) from ligne_dotation x where x.ligne_origine_id = l.id)
                from ligne_dotation l where l.dotation_id = ? order by l.ordre""", (l, n) -> {
            BigDecimal base = l.getBigDecimal(9) != null ? l.getBigDecimal(9)
                    : l.getBigDecimal(8) != null ? l.getBigDecimal(8) : l.getBigDecimal(6);
            BigDecimal repartie = l.getBigDecimal(11);
            return new LigneDotationVue(l.getObject(1, UUID.class), l.getObject(2, UUID.class),
                    Categorie.valueOf(l.getString(3)), l.getString(4), l.getString(5), l.getBigDecimal(6),
                    l.getObject(7, Long.class), l.getBigDecimal(8), l.getBigDecimal(9), l.getString(10),
                    repartiteur ? repartie : null, repartiteur ? base.subtract(repartie) : null);
        }, d.id);
        Map<String, InstanceVue> cache = new HashMap<>();
        List<DotationResume> repartitions = new ArrayList<>();
        if (repartiteur) {
            for (Dotation x : jdbc.query(SELECT + " where d.origine_id = ? order by d.creee_le", (l, n) -> dotation(l),
                    d.id)) {
                repartitions.add(resume(x, cache));
            }
        }
        DotationResume r = resume(d, cache);
        boolean emettrice = a != null && a.agit(new Instance(d.emettriceType, d.emettriceId, null));
        boolean destinataire = a == null
                ? d.destinataireType == TypeInstance.ETABLISSEMENT
                        && UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "INTENDANT")
                : a.agit(new Instance(d.destinataireType, d.destinataireId, null));
        boolean repartir = a != null && destinataire && d.destinataireType == TypeInstance.DIRECTION
                && d.statut == StatutDotation.RECUE
                && lignes.stream().anyMatch(l -> l.disponible() != null && l.disponible().signum() > 0);
        return new DotationVue(d.id, d.reference, d.annee, r.emettrice(), r.destinataire(), d.origineId,
                r.origineReference(), d.objet, d.statut, d.creeeLe, d.envoyeeLe, d.envoyeePar, d.recueLe, d.accuseePar,
                d.observationsReception, r.valeur(), emettrice && d.statut == StatutDotation.BROUILLON,
                destinataire && d.statut == StatutDotation.ENVOYEE, repartir, lignes, repartitions);
    }
}
