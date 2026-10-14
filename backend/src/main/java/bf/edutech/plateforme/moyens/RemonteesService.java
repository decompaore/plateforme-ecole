package bf.edutech.plateforme.moyens;

import static bf.edutech.plateforme.moyens.EtatBesoinsService.instant;
import static bf.edutech.plateforme.moyens.EtatBesoinsService.vide;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.moyens.PorteeMoyens.Acteur;
import bf.edutech.plateforme.moyens.PorteeMoyens.Instance;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Remontée des besoins (v0.48) vue des directions et du niveau central.
 * <ul>
 * <li>Un état transmis par un établissement arrive à sa direction de rattachement ; il n'arrive à une direction
 * supérieure que lorsque chaque direction intermédiaire a transmis ses besoins de l'année.</li>
 * <li>Chaque niveau arbitre : la quantité qu'il retient, au plus celle retenue par le niveau inférieur (ou
 * demandée). Sans arbitrage, la quantité du niveau inférieur est reprise.</li>
 * <li>Une direction renvoie pour correction l'état d'un établissement qui lui est rattaché, ou la transmission
 * d'une direction inférieure, tant qu'elle n'a pas elle-même transmis.</li>
 * </ul>
 */
@Service
public class RemonteesService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    public enum StatutEtablissement {
        NON_TRANSMIS, EN_COURS, RECU, RENVOYE
    }

    public enum StatutTransmission {
        NON_TRANSMISE, TRANSMISE, RENVOYEE
    }

    public record InstanceVue(TypeInstance type, UUID id, String nom, String chemin) {
    }

    public record TransmissionVue(StatutTransmission statut, Instant transmiseLe, String transmisePar,
            String observations, Instant renvoyeeLe, String motifRenvoi) {
    }

    public record EtablissementBesoins(UUID id, String code, String nom, String direction, StatutEtablissement statut,
            String etape, UUID remonteeId, Instant transmiseLe, int lignes, String motifRenvoi) {
    }

    public record DirectionBesoins(UUID id, String nom, StatutTransmission statut, Instant transmiseLe,
            int etablissements, int recues, String motifRenvoi, boolean peutRenvoyer) {
    }

    public record LigneConsolidee(Categorie categorie, String designation, String unite, BigDecimal demandee,
            BigDecimal retenue, int etablissements, int priorite) {
    }

    public record VueBesoins(InstanceVue perimetre, String annee, List<String> annees, String destination,
            TransmissionVue transmission, boolean peutArbitrer, boolean peutTransmettre,
            List<EtablissementBesoins> etablissements, List<DirectionBesoins> directions,
            List<LigneConsolidee> consolidation) {
    }

    public record ArbitrageVue(TypeInstance niveau, String instance, BigDecimal quantite, String observation) {
    }

    public record LigneRemonteeVue(UUID id, Categorie categorie, Domaine domaine, String designation, String unite,
            BigDecimal quantite, int priorite, String justification, List<ArbitrageVue> arbitrages,
            BigDecimal maximum, BigDecimal retenue, String observation, boolean arbitreIci) {
    }

    public record RemonteeVue(UUID id, UUID etablissementId, String etablissement, String direction, String annee,
            StatutEtablissement statut, Instant transmiseLe, String transmisePar, String observations,
            String motifRenvoi, String niveau, boolean peutArbitrer, boolean peutRenvoyer,
            List<LigneRemonteeVue> lignes) {
    }

    public record LigneArbitree(UUID ligneId, BigDecimal quantiteRetenue, String observation) {
    }

    public record DemandeArbitrage(List<LigneArbitree> lignes) {
    }

    public record DemandeTransmission(String annee, String observations) {
    }

    public record DemandeRenvoi(String annee, String motif) {
    }

    private final JdbcTemplate jdbc;
    private final PorteeMoyens portee;
    private final AuditService audit;

    RemonteesService(JdbcTemplate jdbc, PorteeMoyens portee, AuditService audit) {
        this.jdbc = jdbc;
        this.portee = portee;
        this.audit = audit;
    }

    // ================================================================== vue d'ensemble

    @Transactional(readOnly = true)
    public VueBesoins vue(UUID direction, UUID pays, String annee) {
        Acteur a = portee.acteur();
        Instance cible = portee.cible(a, direction, pays);
        Contexte c = contexte(cible, annee);
        boolean ici = a.agit(cible);
        Transmission propre = cible.type() == TypeInstance.DIRECTION ? c.transmissions.get(cible.id()) : null;
        boolean ouvert = ici && (cible.type() == TypeInstance.PAYS || propre == null || !propre.transmise());

        // Établissements du ressort
        List<EtablissementBesoins> etablissements = new ArrayList<>();
        for (Etab e : c.etablissements) {
            Remontee r = c.parEtablissement.get(e.id);
            StatutEtablissement s;
            String etape = null;
            if (r == null) {
                s = StatutEtablissement.NON_TRANSMIS;
            } else if (r.renvoyee()) {
                s = StatutEtablissement.RENVOYE;
            } else {
                UUID bloquee = c.bloqueeA(r);
                s = bloquee == null ? StatutEtablissement.RECU : StatutEtablissement.EN_COURS;
                etape = bloquee == null ? null : c.nom(bloquee);
            }
            etablissements.add(new EtablissementBesoins(e.id, e.code, e.nom, e.direction, s, etape,
                    r == null ? null : r.id, r == null ? null : r.transmiseLe, r == null ? 0 : r.lignes,
                    r == null ? null : r.motif));
        }

        // Directions immédiatement inférieures
        List<DirectionBesoins> directions = new ArrayList<>();
        for (Instance enfant : portee.enfants(cible)) {
            Set<UUID> sous = portee.descendantes(enfant.id());
            int nb = (int) c.etablissements.stream().filter(e -> sous.contains(e.directionId)).count();
            int recues = (int) c.remontees.stream().filter(r -> !r.renvoyee() && sous.contains(r.directionId)).count();
            Transmission t = c.transmissions.get(enfant.id());
            StatutTransmission st = t == null ? StatutTransmission.NON_TRANSMISE
                    : t.transmise() ? StatutTransmission.TRANSMISE : StatutTransmission.RENVOYEE;
            directions.add(new DirectionBesoins(enfant.id(), enfant.nom(), st, t == null ? null : t.transmiseLe, nb,
                    recues, t == null ? null : t.motif, ouvert && st == StatutTransmission.TRANSMISE));
        }

        // Consolidation des lignes arrivées à ce niveau
        Map<String, Object[]> groupes = new LinkedHashMap<>();
        List<LigneCalculee> lignes = c.lignesArrivees();
        lignes.sort(Comparator.comparing((LigneCalculee l) -> l.ligne.categorie).thenComparing(l -> l.ligne.designation,
                String.CASE_INSENSITIVE_ORDER));
        for (LigneCalculee l : lignes) {
            String cle = l.ligne.categorie + "|" + normaliser(l.ligne.designation) + "|" + normaliser(l.ligne.unite);
            Object[] g = groupes.computeIfAbsent(cle, k -> new Object[] { l.ligne.categorie, l.ligne.designation,
                    l.ligne.unite, BigDecimal.ZERO, BigDecimal.ZERO, new HashSet<UUID>(), 3 });
            g[3] = ((BigDecimal) g[3]).add(l.ligne.quantite);
            g[4] = ((BigDecimal) g[4]).add(l.retenue);
            @SuppressWarnings("unchecked")
            Set<UUID> etabs = (Set<UUID>) g[5];
            etabs.add(l.remontee.tenantId);
            g[6] = Math.min((Integer) g[6], l.ligne.priorite);
        }
        List<LigneConsolidee> consolidation = new ArrayList<>();
        for (Object[] g : groupes.values()) {
            consolidation.add(new LigneConsolidee((Categorie) g[0], (String) g[1], (String) g[2], (BigDecimal) g[3],
                    (BigDecimal) g[4], ((Set<?>) g[5]).size(), (Integer) g[6]));
        }

        String destination = null;
        if (cible.type() == TypeInstance.DIRECTION) {
            destination = portee.parent(cible.id()).map(p -> portee.instance(TypeInstance.DIRECTION, p).nom())
                    .orElse("Niveau central");
        }
        TransmissionVue transmission = propre == null ? null
                : new TransmissionVue(propre.transmise() ? StatutTransmission.TRANSMISE : StatutTransmission.RENVOYEE,
                        propre.transmiseLe, propre.par, propre.observations, propre.renvoyeeLe, propre.motif);
        return new VueBesoins(new InstanceVue(cible.type(), cible.id(), cible.nom(), portee.chemin(cible)), c.annee,
                c.annees, destination, transmission, ouvert,
                ouvert && cible.type() == TypeInstance.DIRECTION, etablissements, directions, consolidation);
    }

    // ================================================================== détail d'un état

    @Transactional(readOnly = true)
    public RemonteeVue remontee(UUID id, UUID direction) {
        Acteur a = portee.acteur();
        Remontee r = chargerRemontee(id);
        Instance cible = cibleRemontee(a, r, direction);
        Contexte c = contexte(cible, r.annee);
        Remontee rc = c.parId.get(id);
        if (rc == null) {
            throw new RessourceIntrouvableException("État des besoins introuvable");
        }
        return vueRemontee(a, cible, c, rc);
    }

    // ================================================================== arbitrage

    @Transactional
    public RemonteeVue arbitrer(UUID id, DemandeArbitrage demande) {
        Acteur a = exigerActeurQuiAgit();
        Remontee r = chargerRemontee(id);
        Instance cible = a.instance();
        if (!portee.directionsDe(cible).contains(r.directionId)) {
            throw new AccesRefuseException("Cet établissement n'est pas dans votre ressort");
        }
        Contexte c = contexte(cible, r.annee);
        Remontee rc = c.parId.get(id);
        exigerOuvert(c, cible);
        if (rc.renvoyee() || c.bloqueeA(rc) != null) {
            throw new RegleMetierException("ETAT_NON_RECU", "Cet état des besoins n'est pas arrivé à votre niveau");
        }
        if (demande == null || demande.lignes() == null || demande.lignes().isEmpty()) {
            throw new IllegalArgumentException("Aucune ligne à arbitrer");
        }
        Map<UUID, LigneCalculee> parLigne = new HashMap<>();
        for (LigneCalculee l : c.calculer(rc)) {
            parLigne.put(l.ligne.id, l);
        }
        UUID utilisateur = UtilisateurConnecte.id();
        for (LigneArbitree la : demande.lignes()) {
            LigneCalculee l = parLigne.get(la.ligneId());
            if (l == null) {
                throw new RessourceIntrouvableException("Ligne introuvable dans cet état");
            }
            if (la.quantiteRetenue() == null) {
                jdbc.update("delete from arbitrage_besoin where ligne_id = ? and instance_id = ?", l.ligne.id,
                        cible.id());
                continue;
            }
            if (la.quantiteRetenue().signum() < 0) {
                throw new IllegalArgumentException("La quantité retenue ne peut pas être négative");
            }
            if (la.quantiteRetenue().compareTo(l.maximum) > 0) {
                throw new RegleMetierException("QUANTITE_SUPERIEURE", "« " + l.ligne.designation
                        + " » : au plus " + l.maximum.stripTrailingZeros().toPlainString() + " " + l.ligne.unite);
            }
            String observation = vide(la.observation());
            if (observation != null && observation.length() > 300) {
                throw new IllegalArgumentException("Observation de 300 caractères au plus");
            }
            jdbc.update("""
                    insert into arbitrage_besoin (ligne_id, niveau, instance_id, quantite_retenue, observation, arbitre_par)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (ligne_id, instance_id) do update set quantite_retenue = excluded.quantite_retenue,
                        observation = excluded.observation, arbitre_par = excluded.arbitre_par, arbitre_le = now()""",
                    l.ligne.id, cible.type().name(), cible.id(), la.quantiteRetenue(), observation, utilisateur);
        }
        audit.enregistrer("BESOINS_ARBITRES", r.annee, Map.of("etablissement", r.etablissement, "niveau", cible.nom()));
        Contexte apres = contexte(cible, r.annee);
        return vueRemontee(a, cible, apres, apres.parId.get(id));
    }

    // ================================================================== renvois et transmission

    /** Renvoie à l'établissement (rattaché à la direction) son état pour correction. */
    @Transactional
    public RemonteeVue renvoyerRemontee(UUID id, DemandeRenvoi demande) {
        String motif = motif(demande);
        Acteur a = exigerActeurQuiAgit();
        Remontee r = chargerRemontee(id);
        if (!a.estDirection() || !r.directionId.equals(a.instance().id())) {
            throw new AccesRefuseException("Seule la direction de rattachement renvoie l'état d'un établissement");
        }
        Contexte c = contexte(a.instance(), r.annee);
        exigerOuvert(c, a.instance());
        if (r.renvoyee()) {
            throw new RegleMetierException("DEJA_RENVOYE", "Cet état a déjà été renvoyé à l'établissement");
        }
        jdbc.update("""
                update remontee_besoins set statut = 'RENVOYEE', renvoyee_le = now(), renvoyee_par = ?, motif_renvoi = ?
                where id = ?""", UtilisateurConnecte.id(), motif, id);
        audit.enregistrer("BESOINS_RENVOYES", r.annee, Map.of("etablissement", r.etablissement));
        Contexte apres = contexte(a.instance(), r.annee);
        return vueRemontee(a, a.instance(), apres, apres.parId.get(id));
    }

    /** La direction transmet ses besoins de l'année à la direction supérieure (ou au niveau central). */
    @Transactional
    public VueBesoins transmettre(DemandeTransmission demande) {
        Acteur a = exigerActeurQuiAgit();
        if (!a.estDirection()) {
            throw new AccesRefuseException("Le niveau central reçoit les besoins ; il ne les transmet pas");
        }
        String annee = annee(demande == null ? null : demande.annee());
        Contexte c = contexte(a.instance(), annee);
        exigerOuvert(c, a.instance());
        if (c.lignesArrivees().isEmpty()) {
            throw new RegleMetierException("AUCUN_BESOIN", "Aucun état des besoins n'est arrivé pour " + annee);
        }
        String observations = demande == null ? null : vide(demande.observations());
        jdbc.update("""
                insert into transmission_besoins (id, direction_id, annee, observations, transmise_par)
                values (?, ?, ?, ?, ?)
                on conflict (direction_id, annee) do update set statut = 'TRANSMISE', observations = excluded.observations,
                    transmise_le = now(), transmise_par = excluded.transmise_par, renvoyee_le = null,
                    renvoyee_par = null, motif_renvoi = null""",
                UUID.randomUUID(), a.instance().id(), annee, observations, UtilisateurConnecte.id());
        audit.enregistrer("BESOINS_DIRECTION_TRANSMIS", annee, Map.of("direction", a.instance().nom()));
        return vue(null, null, annee);
    }

    /** Renvoie à une direction inférieure sa transmission, pour correction. */
    @Transactional
    public VueBesoins renvoyerDirection(UUID directionId, DemandeRenvoi demande) {
        String motif = motif(demande);
        Acteur a = exigerActeurQuiAgit();
        String annee = annee(demande.annee());
        boolean enfant = portee.enfants(a.instance()).stream().anyMatch(e -> e.id().equals(directionId));
        if (!enfant) {
            throw new AccesRefuseException("Cette direction ne vous transmet pas ses besoins");
        }
        Contexte c = contexte(a.instance(), annee);
        exigerOuvert(c, a.instance());
        Transmission t = c.transmissions.get(directionId);
        if (t == null || !t.transmise()) {
            throw new RegleMetierException("NON_TRANSMIS", "Cette direction n'a pas transmis ses besoins");
        }
        jdbc.update("""
                update transmission_besoins set statut = 'RENVOYEE', renvoyee_le = now(), renvoyee_par = ?,
                    motif_renvoi = ? where direction_id = ? and annee = ?""",
                UtilisateurConnecte.id(), motif, directionId, annee);
        audit.enregistrer("BESOINS_DIRECTION_RENVOYES", annee, Map.of("direction", directionId));
        return vue(null, null, annee);
    }

    // ================================================================== calculs

    private record Etab(UUID id, String code, String nom, UUID directionId, String direction) {
    }

    private record Remontee(UUID id, UUID tenantId, String etablissement, UUID directionId, String direction,
            String annee, String statut, Instant transmiseLe, String transmisePar, String observations, String motif,
            int lignes) {

        boolean renvoyee() {
            return "RENVOYEE".equals(statut);
        }
    }

    private record Transmission(String statut, Instant transmiseLe, String par, String observations,
            Instant renvoyeeLe, String motif) {

        boolean transmise() {
            return "TRANSMISE".equals(statut);
        }
    }

    private record Ligne(UUID id, UUID remonteeId, Categorie categorie, Domaine domaine, String designation,
            String unite, BigDecimal quantite, int priorite, String justification) {
    }

    private record Arbitrage(UUID instanceId, TypeInstance niveau, BigDecimal quantite, String observation) {
    }

    private record LigneCalculee(Remontee remontee, Ligne ligne, List<ArbitrageVue> chaine, BigDecimal maximum,
            BigDecimal retenue, String observation, boolean arbitreIci) {
    }

    /** Tout ce qu'il faut pour une instance et une année. */
    private final class Contexte {

        final Instance cible;
        final String annee;
        List<String> annees;
        List<Etab> etablissements;
        List<Remontee> remontees;
        final Map<UUID, Remontee> parId = new HashMap<>();
        final Map<UUID, Remontee> parEtablissement = new HashMap<>();
        Map<UUID, Transmission> transmissions;
        final Map<UUID, List<UUID>> chemins = new HashMap<>();
        final Map<UUID, String> noms = new HashMap<>();
        Map<UUID, List<Ligne>> lignes;
        Map<UUID, Map<UUID, Arbitrage>> arbitrages;

        Contexte(Instance cible, String annee) {
            this.cible = cible;
            this.annee = annee;
        }

        /** Directions de l'établissement à l'instance, de bas en haut, instance exclue. */
        List<UUID> intermediaires(Remontee r) {
            List<UUID> chemin = chemins.computeIfAbsent(r.directionId, portee::ascendantes);
            List<UUID> r2 = new ArrayList<>();
            for (int i = chemin.size() - 1; i >= 0; i--) {
                UUID d = chemin.get(i);
                if (cible.type() == TypeInstance.DIRECTION && d.equals(cible.id())) {
                    break;
                }
                r2.add(d);
            }
            return r2;
        }

        /** Direction où l'état attend une transmission (vide s'il est arrivé à l'instance). */
        UUID bloqueeA(Remontee r) {
            for (UUID d : intermediaires(r)) {
                Transmission t = transmissions.get(d);
                if (t == null || !t.transmise()) {
                    return d;
                }
            }
            return null;
        }

        List<LigneCalculee> calculer(Remontee r) {
            List<UUID> niveaux = intermediaires(r);
            List<LigneCalculee> res = new ArrayList<>();
            for (Ligne l : lignes.getOrDefault(r.id, List.of())) {
                Map<UUID, Arbitrage> arb = arbitrages.getOrDefault(l.id, Map.of());
                BigDecimal effectif = l.quantite;
                List<ArbitrageVue> chaine = new ArrayList<>();
                for (UUID d : niveaux) {
                    Arbitrage x = arb.get(d);
                    if (x != null) {
                        effectif = x.quantite;
                        chaine.add(new ArbitrageVue(TypeInstance.DIRECTION, nom(d), x.quantite, x.observation));
                    }
                }
                Arbitrage ici = arb.get(cible.id());
                res.add(new LigneCalculee(r, l, chaine, effectif, ici != null ? ici.quantite : effectif,
                        ici == null ? null : ici.observation, ici != null));
            }
            return res;
        }

        List<LigneCalculee> lignesArrivees() {
            List<LigneCalculee> r = new ArrayList<>();
            for (Remontee x : remontees) {
                if (!x.renvoyee() && bloqueeA(x) == null) {
                    r.addAll(calculer(x));
                }
            }
            return r;
        }

        String nom(UUID direction) {
            return noms.computeIfAbsent(direction, d -> portee.instance(TypeInstance.DIRECTION, d).nom());
        }
    }

    private Contexte contexte(Instance cible, String anneeDemandee) {
        Set<UUID> dirs = portee.directionsDe(cible);
        String tableau = "{" + String.join(",", dirs.stream().map(UUID::toString).toList()) + "}";
        List<String> connues = jdbc.queryForList(
                "select distinct annee from remontee_besoins where direction_id = any (?::uuid[])", String.class, tableau);
        TreeSet<String> annees = new TreeSet<>(Comparator.reverseOrder());
        annees.addAll(connues);
        String annee = anneeDemandee != null && !anneeDemandee.isBlank() ? anneeDemandee.strip()
                : annees.isEmpty() ? anneeCourante() : annees.first();
        annees.add(annee);
        Contexte c = new Contexte(cible, annee);
        c.annees = new ArrayList<>(annees);
        c.etablissements = jdbc.query("""
                select t.id, t.code, t.nom, t.direction_id, d.nom from tenant t join direction d on d.id = t.direction_id
                where t.direction_id = any (?::uuid[]) and t.statut <> 'RESILIE' order by d.nom, t.nom""",
                (l, n) -> new Etab(l.getObject(1, UUID.class), l.getString(2), l.getString(3),
                        l.getObject(4, UUID.class), l.getString(5)),
                tableau);
        c.remontees = jdbc.query("""
                select r.id, r.tenant_id, t.nom, r.direction_id, d.nom, r.annee, r.statut, r.transmise_le,
                       u.nom || ' ' || u.prenoms, r.observations, r.motif_renvoi,
                       (select count(*) from ligne_remontee l where l.remontee_id = r.id)
                from remontee_besoins r join tenant t on t.id = r.tenant_id join direction d on d.id = r.direction_id
                left join utilisateur u on u.id = r.transmise_par
                where r.annee = ? and r.direction_id = any (?::uuid[]) order by t.nom""",
                (l, n) -> new Remontee(l.getObject(1, UUID.class), l.getObject(2, UUID.class), l.getString(3),
                        l.getObject(4, UUID.class), l.getString(5), l.getString(6), l.getString(7),
                        instant(l.getTimestamp(8)), l.getString(9), l.getString(10), l.getString(11), l.getInt(12)),
                annee, tableau);
        for (Remontee r : c.remontees) {
            c.parId.put(r.id, r);
            c.parEtablissement.put(r.tenantId, r);
            c.noms.put(r.directionId, r.direction);
        }
        c.transmissions = new HashMap<>();
        jdbc.query("""
                select x.direction_id, x.statut, x.transmise_le, u.nom || ' ' || u.prenoms, x.observations,
                       x.renvoyee_le, x.motif_renvoi, d.nom
                from transmission_besoins x join direction d on d.id = x.direction_id
                left join utilisateur u on u.id = x.transmise_par
                where x.annee = ? and x.direction_id = any (?::uuid[])""", l -> {
            UUID d = l.getObject(1, UUID.class);
            c.transmissions.put(d, new Transmission(l.getString(2), instant(l.getTimestamp(3)), l.getString(4),
                    l.getString(5), instant(l.getTimestamp(6)), l.getString(7)));
            c.noms.put(d, l.getString(8));
        }, annee, tableau);
        String ids = "{" + String.join(",", c.remontees.stream().map(r -> r.id.toString()).toList()) + "}";
        c.lignes = new HashMap<>();
        jdbc.query("""
                select id, remontee_id, categorie, domaine, designation, unite, quantite, priorite, justification
                from ligne_remontee where remontee_id = any (?::uuid[]) order by ordre""", l -> {
            Ligne x = new Ligne(l.getObject(1, UUID.class), l.getObject(2, UUID.class),
                    Categorie.valueOf(l.getString(3)), Domaine.valueOf(l.getString(4)), l.getString(5), l.getString(6),
                    l.getBigDecimal(7), l.getInt(8), l.getString(9));
            c.lignes.computeIfAbsent(x.remonteeId, k -> new ArrayList<>()).add(x);
        }, ids);
        c.arbitrages = new HashMap<>();
        jdbc.query("""
                select a.ligne_id, a.instance_id, a.niveau, a.quantite_retenue, a.observation
                from arbitrage_besoin a join ligne_remontee l on l.id = a.ligne_id
                where l.remontee_id = any (?::uuid[])""", l -> {
            c.arbitrages.computeIfAbsent(l.getObject(1, UUID.class), k -> new HashMap<>()).put(
                    l.getObject(2, UUID.class), new Arbitrage(l.getObject(2, UUID.class),
                            TypeInstance.valueOf(l.getString(3)), l.getBigDecimal(4), l.getString(5)));
        }, ids);
        return c;
    }

    private RemonteeVue vueRemontee(Acteur a, Instance cible, Contexte c, Remontee r) {
        boolean ici = a.agit(cible);
        Transmission propre = cible.type() == TypeInstance.DIRECTION ? c.transmissions.get(cible.id()) : null;
        boolean ouvert = ici && (propre == null || !propre.transmise());
        UUID bloquee = r.renvoyee() ? null : c.bloqueeA(r);
        StatutEtablissement statut = r.renvoyee() ? StatutEtablissement.RENVOYE
                : bloquee == null ? StatutEtablissement.RECU : StatutEtablissement.EN_COURS;
        boolean arbitrable = ouvert && statut == StatutEtablissement.RECU;
        List<LigneRemonteeVue> lignes = new ArrayList<>();
        for (LigneCalculee l : c.calculer(r)) {
            lignes.add(new LigneRemonteeVue(l.ligne.id, l.ligne.categorie, l.ligne.domaine, l.ligne.designation,
                    l.ligne.unite, l.ligne.quantite, l.ligne.priorite, l.ligne.justification, l.chaine, l.maximum,
                    l.retenue, l.observation, l.arbitreIci));
        }
        boolean renvoyable = ouvert && statut == StatutEtablissement.RECU && cible.type() == TypeInstance.DIRECTION
                && r.directionId.equals(cible.id());
        return new RemonteeVue(r.id, r.tenantId, r.etablissement, r.direction, r.annee, statut, r.transmiseLe,
                r.transmisePar, r.observations, r.motif, cible.nom(), arbitrable, renvoyable, lignes);
    }

    private Instance cibleRemontee(Acteur a, Remontee r, UUID direction) {
        if (direction != null) {
            Instance cible = portee.cible(a, direction, null);
            if (!portee.descendantes(direction).contains(r.directionId)) {
                throw new AccesRefuseException("Cet établissement ne dépend pas de cette direction");
            }
            return cible;
        }
        if (a.superAdmin()) {
            return portee.instance(TypeInstance.PAYS, portee.paysDeDirection(r.directionId));
        }
        if (!portee.directionsDe(a.instance()).contains(r.directionId)) {
            throw new AccesRefuseException("Cet établissement n'est pas dans votre ressort");
        }
        return a.instance();
    }

    private Remontee chargerRemontee(UUID id) {
        return jdbc.query("""
                select r.id, r.tenant_id, t.nom, r.direction_id, d.nom, r.annee, r.statut, r.transmise_le, null,
                       r.observations, r.motif_renvoi, 0
                from remontee_besoins r join tenant t on t.id = r.tenant_id join direction d on d.id = r.direction_id
                where r.id = ?""", (l, n) -> new Remontee(l.getObject(1, UUID.class), l.getObject(2, UUID.class),
                l.getString(3), l.getObject(4, UUID.class), l.getString(5), l.getString(6), l.getString(7),
                instant(l.getTimestamp(8)), null, l.getString(10), l.getString(11), 0), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("État des besoins introuvable"));
    }

    private Acteur exigerActeurQuiAgit() {
        Acteur a = portee.acteur();
        if (a.superAdmin()) {
            throw new AccesRefuseException("Le super administrateur consulte les besoins sans les arbitrer");
        }
        return a;
    }

    private static void exigerOuvert(Contexte c, Instance cible) {
        if (cible.type() == TypeInstance.DIRECTION) {
            Transmission t = c.transmissions.get(cible.id());
            if (t != null && t.transmise()) {
                throw new RegleMetierException("BESOINS_DEJA_TRANSMIS",
                        "Vos besoins de " + c.annee + " ont déjà été transmis à la direction supérieure");
            }
        }
    }

    private static String motif(DemandeRenvoi d) {
        String m = d == null ? null : vide(d.motif());
        if (m == null || m.length() > 500) {
            throw new IllegalArgumentException("Motif du renvoi obligatoire (500 caractères au plus)");
        }
        return m;
    }

    private static String annee(String a) {
        return a == null || a.isBlank() ? anneeCourante() : a.strip();
    }

    /** Année scolaire en cours : « 2026-2027 » à partir de septembre. */
    static String anneeCourante() {
        LocalDate j = LocalDate.now(FUSEAU);
        int debut = j.getMonthValue() >= 9 ? j.getYear() : j.getYear() - 1;
        return debut + "-" + (debut + 1);
    }

    private static String normaliser(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Pour l'export : consolidation et état de chaque établissement. */
    @Transactional(readOnly = true)
    public Map.Entry<VueBesoins, UUID> vuePourExport(UUID direction, UUID pays, String annee) {
        VueBesoins v = vue(direction, pays, annee);
        Instance i = new Instance(v.perimetre().type(), v.perimetre().id(), v.perimetre().nom());
        UUID p = portee.paysDe(i);
        return Map.entry(v, p);
    }
}
