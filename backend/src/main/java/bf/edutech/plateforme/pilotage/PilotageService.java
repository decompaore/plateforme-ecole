package bf.edutech.plateforme.pilotage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.time.Clock;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.pilotage.Vues.Compte;
import bf.edutech.plateforme.pilotage.Vues.ExamenVue;
import bf.edutech.plateforme.pilotage.Vues.Indicateurs;
import bf.edutech.plateforme.pilotage.Vues.LigneDirection;
import bf.edutech.plateforme.pilotage.Vues.LigneEtablissement;
import bf.edutech.plateforme.pilotage.Vues.NiveauVue;
import bf.edutech.plateforme.pilotage.Vues.ParentVue;
import bf.edutech.plateforme.pilotage.Vues.PerimetreVue;
import bf.edutech.plateforme.pilotage.Vues.PeriodeVue;
import bf.edutech.plateforme.pilotage.Vues.Personnes;
import bf.edutech.plateforme.pilotage.Vues.TableauPilotage;
import bf.edutech.plateforme.pilotage.Vues.UtilisationVue;
import bf.edutech.plateforme.socle.documents.EnteteOfficiel;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.Portee;

/**
 * Tableau de bord de pilotage : nombres par établissement, cumulés par direction.
 * <ul>
 * <li>Un compte de direction voit sa direction et tout ce qui en dépend (il peut descendre vers
 * une direction inférieure, jamais remonter au-dessus de la sienne).</li>
 * <li>L'administrateur pays voit son pays ; le super administrateur, le pays choisi.</li>
 * <li>Les établissements résiliés sont exclus ; les suspendus restent comptés.</li>
 * <li>Les années scolaires des établissements sont rapprochées par leur libellé.</li>
 * </ul>
 */
@Service
public class PilotageService {

    static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    /** Fenêtre de mesure de l'utilisation de l'application. */
    static final int JOURS_UTILISATION = 30;
    static final String PAYS = "PAYS";
    static final String DIRECTION = "DIRECTION";

    private final JdbcTemplate jdbc;
    private final Clock horloge;

    PilotageService(JdbcTemplate jdbc, Clock horloge) {
        this.jdbc = jdbc;
        this.horloge = horloge;
    }

    /** Établissement du périmètre : direction de rattachement et sous-direction (ligne de cumul). */
    private record Etablissement(UUID id, String code, String nom, String statut, UUID directionId, String direction) {
    }

    private record Enfant(UUID id, String code, String nom) {
    }

    /** Périmètre résolu, prêt à être compté. */
    record Perimetre(PerimetreVue vue, ParentVue parent, String niveauEnfants, List<Enfant> enfants,
            List<Etablissement> etablissements, Map<UUID, UUID> enfantDeDirection, EnteteOfficiel entete) {
    }

    @Transactional(readOnly = true)
    public TableauPilotage tableau(UUID paysId, UUID directionId, String annee) {
        Perimetre p = perimetre(paysId, directionId);
        return compter(p, annee);
    }

    /** Pour les exports : le tableau et l'en-tête officiel du périmètre. */
    @Transactional(readOnly = true)
    public Map.Entry<TableauPilotage, EnteteOfficiel> tableauEtEntete(UUID paysId, UUID directionId, String annee) {
        Perimetre p = perimetre(paysId, directionId);
        return Map.entry(compter(p, annee), p.entete());
    }

    // ================================================================== Portée

    Perimetre perimetre(UUID paysId, UUID directionId) {
        Optional<UUID> compteDirection = Portee.direction();
        if (compteDirection.isPresent()) {
            UUID racine = compteDirection.get();
            UUID cible = directionId != null ? directionId : racine;
            Set<UUID> visibles = new HashSet<>(descendantes(racine));
            if (!visibles.contains(cible)) {
                throw new AccesRefuseException("Cette direction ne dépend pas de la vôtre");
            }
            return perimetreDirection(cible, !cible.equals(racine));
        }
        if (Portee.superAdmin()) {
            if (directionId != null) {
                return perimetreDirection(directionId, true);
            }
            return perimetrePays(paysId != null ? paysId : paysParDefaut());
        }
        UUID pays = Portee.pays().orElseThrow(() -> new AccesRefuseException("Réservé au pilotage"));
        if (directionId != null) {
            if (!pays.equals(paysDeDirection(directionId))) {
                throw new AccesRefuseException("Hors du pays que vous administrez");
            }
            return perimetreDirection(directionId, true);
        }
        if (paysId != null && !paysId.equals(pays)) {
            throw new AccesRefuseException("Hors du pays que vous administrez");
        }
        return perimetrePays(pays);
    }

    private UUID paysParDefaut() {
        List<UUID> pays = jdbc.queryForList("""
                select p.id from pays p order by (select count(*) from tenant t join direction d on d.id = t.direction_id
                  join ministere m on m.id = d.ministere_id where m.pays_id = p.id) desc, p.nom limit 1""", UUID.class);
        if (pays.isEmpty()) {
            throw new RessourceIntrouvableException("Aucun pays");
        }
        return pays.get(0);
    }

    private UUID paysDeDirection(UUID directionId) {
        List<UUID> r = jdbc.queryForList(
                "select m.pays_id from direction d join ministere m on m.id = d.ministere_id where d.id = ?", UUID.class,
                directionId);
        if (r.isEmpty()) {
            throw new RessourceIntrouvableException("Direction introuvable");
        }
        return r.get(0);
    }

    private List<UUID> descendantes(UUID directionId) {
        return jdbc.queryForList("select directions_descendantes(?)", UUID.class, directionId);
    }

    private Perimetre perimetreDirection(UUID id, boolean peutRemonter) {
        Map<String, Object> d = jdbc.query("""
                select d.code, d.nom, d.rang, d.parent_id, d.ministere_id, chemin_direction(d.id) as chemin,
                       nd.libelle as niveau, nd2.libelle as niveau_enfants, par.nom as parent_nom,
                       p.id as pays_id, p.nom as pays_nom, p.devise_nationale, m.nom as ministere
                from direction d join ministere m on m.id = d.ministere_id join pays p on p.id = m.pays_id
                left join niveau_direction nd on nd.ministere_id = d.ministere_id and nd.rang = d.rang
                left join niveau_direction nd2 on nd2.ministere_id = d.ministere_id and nd2.rang = d.rang + 1
                left join direction par on par.id = d.parent_id
                where d.id = ?""", (ResultSet l, int n) -> {
            Map<String, Object> m = new HashMap<>();
            for (String c : List.of("code", "nom", "chemin", "niveau", "niveau_enfants", "parent_nom", "pays_nom",
                    "devise_nationale", "ministere")) {
                m.put(c, l.getString(c));
            }
            m.put("parent_id", l.getObject("parent_id", UUID.class));
            m.put("pays_id", l.getObject("pays_id", UUID.class));
            return m;
        }, id).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Direction introuvable"));

        ParentVue parent = null;
        if (peutRemonter) {
            parent = d.get("parent_id") != null
                    ? new ParentVue(DIRECTION, (UUID) d.get("parent_id"), (String) d.get("parent_nom"))
                    : new ParentVue(PAYS, (UUID) d.get("pays_id"), (String) d.get("pays_nom"));
        }
        List<Enfant> enfants = jdbc.query("select id, code, nom from direction where parent_id = ? order by nom",
                (l, n) -> new Enfant(l.getObject(1, UUID.class), l.getString(2), l.getString(3)), id);
        List<Etablissement> etablissements = jdbc.query("""
                select t.id, t.code, t.nom, t.statut, t.direction_id, dd.nom from tenant t
                join direction dd on dd.id = t.direction_id
                where t.direction_id in (select directions_descendantes(?)) and t.statut <> 'RESILIE'
                order by t.nom""", (l, n) -> etablissement(l), id);
        List<String> autorites = new ArrayList<>();
        autorites.add((String) d.get("ministere"));
        autorites.addAll(jdbc.queryForList("select nom from directions_ascendantes(?) where id <> ?", String.class, id, id));
        EnteteOfficiel entete = new EnteteOfficiel((String) d.get("pays_nom"), (String) d.get("devise_nationale"),
                autorites, (String) d.get("nom"), null);
        return new Perimetre(new PerimetreVue(DIRECTION, id, (String) d.get("nom"), (String) d.get("niveau"),
                (String) d.get("chemin")), parent, (String) d.get("niveau_enfants"), enfants, etablissements,
                rattacherAuxEnfants(enfants), entete);
    }

    private Perimetre perimetrePays(UUID id) {
        String[] p = jdbc.query("select nom, devise_nationale from pays where id = ?",
                (l, n) -> new String[] { l.getString(1), l.getString(2) }, id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Pays introuvable"));
        List<String[]> ministeres = jdbc.query("select id::text, sigle from ministere where pays_id = ? order by sigle",
                (l, n) -> new String[] { l.getString(1), l.getString(2) }, id);
        boolean plusieurs = ministeres.size() > 1;
        List<Enfant> enfants = jdbc.query("""
                select d.id, d.code, d.nom, m.sigle from direction d join ministere m on m.id = d.ministere_id
                where m.pays_id = ? and d.rang = 1 order by m.sigle, d.nom""",
                (l, n) -> new Enfant(l.getObject(1, UUID.class), l.getString(2),
                        plusieurs ? l.getString(4) + " · " + l.getString(3) : l.getString(3)), id);
        String niveau = plusieurs || ministeres.isEmpty() ? "Directions"
                : jdbc.queryForList("select libelle from niveau_direction where ministere_id = ?::uuid and rang = 1",
                        String.class, ministeres.get(0)[0]).stream().findFirst().orElse("Directions");
        List<Etablissement> etablissements = jdbc.query("""
                select t.id, t.code, t.nom, t.statut, t.direction_id, dd.nom from tenant t
                join direction dd on dd.id = t.direction_id join ministere m on m.id = dd.ministere_id
                where m.pays_id = ? and t.statut <> 'RESILIE'
                order by t.nom""", (l, n) -> etablissement(l), id);
        EnteteOfficiel entete = new EnteteOfficiel(p[0], p[1],
                ministeres.size() == 1 ? jdbc.queryForList("select nom from ministere where pays_id = ?", String.class, id)
                        : List.of(),
                p[0], null);
        return new Perimetre(new PerimetreVue(PAYS, id, p[0], "Pays", p[0]), null, niveau, enfants, etablissements,
                rattacherAuxEnfants(enfants), entete);
    }

    private static Etablissement etablissement(ResultSet l) throws java.sql.SQLException {
        return new Etablissement(l.getObject(1, UUID.class), l.getString(2), l.getString(3), l.getString(4),
                l.getObject(5, UUID.class), l.getString(6));
    }

    /** Pour chaque direction du périmètre : la direction « enfant » (ligne de cumul) dont elle dépend. */
    private Map<UUID, UUID> rattacherAuxEnfants(List<Enfant> enfants) {
        Map<UUID, UUID> r = new HashMap<>();
        for (Enfant e : enfants) {
            for (UUID d : descendantes(e.id())) {
                r.put(d, e.id());
            }
        }
        return r;
    }

    // ================================================================== Comptage

    /** Nombres d'un ou plusieurs établissements, additionnables. */
    private static final class Cumul {

        final Set<UUID> etablissements = new HashSet<>();
        int classes;
        Compte eleves = Compte.ZERO;
        Compte redoublants = Compte.ZERO;
        Personnes titulaires = Personnes.ZERO;
        Personnes vacataires = Personnes.ZERO;
        /** Par niveau : classes, élèves G/F, redoublants G/F, décidés G/F, admis G/F. */
        final Map<String, int[]> niveaux = new HashMap<>();
        /** Par examen : candidats G/F, résultats G/F, admis G/F. */
        final Map<String, int[]> examens = new TreeMap<>();
        /** Par période (découpage + rang) : bulletins G/F, admis G/F. */
        final Map<String, int[]> periodes = new TreeMap<>();
        final Map<String, BigDecimal> sommesMoyennes = new HashMap<>();
        int comptes;
        int actifs;
        int enseignants;
        int enseignantsActifs;
        LocalDate derniereActivite;

        int[] niveau(String n) {
            return niveaux.computeIfAbsent(n, k -> new int[9]);
        }

        void ajouter(Cumul c) {
            etablissements.addAll(c.etablissements);
            classes += c.classes;
            eleves = eleves.plus(c.eleves);
            redoublants = redoublants.plus(c.redoublants);
            titulaires = titulaires.plus(c.titulaires);
            vacataires = vacataires.plus(c.vacataires);
            c.niveaux.forEach((k, v) -> additionner(niveau(k), v));
            c.examens.forEach((k, v) -> additionner(examens.computeIfAbsent(k, x -> new int[6]), v));
            c.periodes.forEach((k, v) -> additionner(periodes.computeIfAbsent(k, x -> new int[4]), v));
            c.sommesMoyennes.forEach((k, v) -> sommesMoyennes.merge(k, v, BigDecimal::add));
            comptes += c.comptes;
            actifs += c.actifs;
            enseignants += c.enseignants;
            enseignantsActifs += c.enseignantsActifs;
            if (c.derniereActivite != null
                    && (derniereActivite == null || c.derniereActivite.isAfter(derniereActivite))) {
                derniereActivite = c.derniereActivite;
            }
        }

        private static void additionner(int[] cible, int[] source) {
            for (int i = 0; i < source.length; i++) {
                cible[i] += source[i];
            }
        }

        Indicateurs vue() {
            List<NiveauVue> lignesNiveaux = new ArrayList<>();
            Compte decides = Compte.ZERO;
            Compte admis = Compte.ZERO;
            Map<String, int[]> tries = new TreeMap<>(PilotageService::comparerNiveaux);
            tries.putAll(niveaux);
            for (Map.Entry<String, int[]> e : tries.entrySet()) {
                int[] v = e.getValue();
                Compte d = Compte.de(v[5], v[6]);
                Compte a = Compte.de(v[7], v[8]);
                decides = decides.plus(d);
                admis = admis.plus(a);
                lignesNiveaux.add(new NiveauVue(e.getKey(), v[0], Compte.de(v[1], v[2]), Compte.de(v[3], v[4]), d, a,
                        Vues.taux(a.total(), d.total())));
            }
            List<ExamenVue> lignesExamens = examens.entrySet().stream().map(e -> {
                int[] v = e.getValue();
                Compte resultats = Compte.de(v[2], v[3]);
                Compte reussis = Compte.de(v[4], v[5]);
                return new ExamenVue(e.getKey(), Compte.de(v[0], v[1]), resultats, reussis,
                        Vues.taux(reussis.total(), resultats.total()), Vues.taux(v[4], v[2]), Vues.taux(v[5], v[3]));
            }).toList();
            List<PeriodeVue> lignesPeriodes = periodes.entrySet().stream().map(e -> {
                int[] v = e.getValue();
                String[] cle = e.getKey().split("\\|");
                int ordre = Integer.parseInt(cle[1]);
                Compte bulletins = Compte.de(v[0], v[1]);
                Compte reussis = Compte.de(v[2], v[3]);
                BigDecimal somme = sommesMoyennes.getOrDefault(e.getKey(), BigDecimal.ZERO);
                BigDecimal moyenne = bulletins.total() == 0 ? null
                        : somme.divide(BigDecimal.valueOf(bulletins.total()), 2, RoundingMode.HALF_UP);
                return new PeriodeVue(cle[0], ordre, libellePeriode(cle[0], ordre), bulletins, moyenne, reussis,
                        Vues.taux(reussis.total(), bulletins.total()));
            }).toList();
            Personnes tous = titulaires.plus(vacataires);
            BigDecimal ratio = tous.total() == 0 ? null
                    : BigDecimal.valueOf(eleves.total()).divide(BigDecimal.valueOf(tous.total()), 1, RoundingMode.HALF_UP);
            return new Indicateurs(etablissements.size(), classes, eleves, redoublants, tous, titulaires, vacataires,
                    ratio, decides, admis, Vues.taux(admis.total(), decides.total()), lignesNiveaux, lignesExamens,
                    lignesPeriodes, new UtilisationVue(comptes, actifs, enseignants, enseignantsActifs, derniereActivite));
        }
    }

    private TableauPilotage compter(Perimetre p, String anneeDemandee) {
        LocalDate fin = LocalDate.ofInstant(horloge.instant(), FUSEAU);
        LocalDate debut = fin.minusDays(JOURS_UTILISATION - 1L);
        Map<UUID, Cumul> parEtablissement = new LinkedHashMap<>();
        p.etablissements().forEach(e -> {
            Cumul c = new Cumul();
            c.etablissements.add(e.id());
            parEtablissement.put(e.id(), c);
        });
        List<String> annees = new ArrayList<>();
        String annee = null;
        if (!parEtablissement.isEmpty()) {
            String tenants = parEtablissement.keySet().stream().map(UUID::toString)
                    .collect(Collectors.joining(",", "{", "}"));
            List<Object[]> lignesAnnees = jdbc.query("select libelle, actives, debut from pilotage_annees(?::uuid[])",
                    (l, n) -> new Object[] { l.getString(1), l.getInt(2), l.getObject(3, LocalDate.class) }, tenants);
            lignesAnnees.sort(Comparator.comparing((Object[] a) -> (LocalDate) a[2]).reversed());
            lignesAnnees.forEach(a -> annees.add((String) a[0]));
            if (anneeDemandee != null && annees.contains(anneeDemandee)) {
                annee = anneeDemandee;
            } else {
                // Année non demandée, ou inconnue dans ce ressort (après un changement de direction) :
                // l'année active du plus grand nombre d'établissements, sinon la plus récente
                annee = lignesAnnees.stream().max(Comparator.comparingInt((Object[] a) -> (int) a[1])
                        .thenComparing(a -> (LocalDate) a[2])).map(a -> (String) a[0]).orElse(null);
            }
            charger(parEtablissement, tenants, annee, debut, fin);
        }

        Cumul total = new Cumul();
        Map<UUID, Cumul> parEnfant = new LinkedHashMap<>();
        p.enfants().forEach(e -> parEnfant.put(e.id(), new Cumul()));
        List<LigneEtablissement> lignes = new ArrayList<>();
        for (Etablissement e : p.etablissements()) {
            Cumul c = parEtablissement.get(e.id());
            total.ajouter(c);
            UUID enfant = p.enfantDeDirection().get(e.directionId());
            if (enfant != null) {
                parEnfant.get(enfant).ajouter(c);
            }
            lignes.add(new LigneEtablissement(e.id(), e.code(), e.nom(), e.statut(), e.directionId(), e.direction(),
                    enfant, c.vue()));
        }
        List<LigneDirection> directions = p.enfants().stream()
                .map(e -> new LigneDirection(e.id(), e.code(), e.nom(), parEnfant.get(e.id()).vue())).toList();
        return new TableauPilotage(p.vue(), p.parent(), annees, annee, debut, fin, total.vue(), p.niveauEnfants(),
                directions, lignes, horloge.instant());
    }

    private void charger(Map<UUID, Cumul> par, String tenants, String annee, LocalDate debut, LocalDate fin) {
        if (annee != null) {
            jdbc.query("select * from pilotage_effectifs(?::uuid[], ?)", (ResultSet l) -> {
                Cumul c = par.get(l.getObject("tenant_id", UUID.class));
                Compte eleves = Compte.de(l.getInt("garcons"), l.getInt("filles"));
                Compte redoublants = Compte.de(l.getInt("redoublants_garcons"), l.getInt("redoublants_filles"));
                c.classes += l.getInt("classes");
                c.eleves = c.eleves.plus(eleves);
                c.redoublants = c.redoublants.plus(redoublants);
                int[] n = c.niveau(l.getString("niveau"));
                n[0] += l.getInt("classes");
                n[1] += eleves.garcons();
                n[2] += eleves.filles();
                n[3] += redoublants.garcons();
                n[4] += redoublants.filles();
            }, tenants, annee);
            jdbc.query("select * from pilotage_resultats(?::uuid[], ?)", (ResultSet l) -> {
                int[] n = par.get(l.getObject("tenant_id", UUID.class)).niveau(l.getString("niveau"));
                n[5] += l.getInt("decides_garcons");
                n[6] += l.getInt("decides_filles");
                n[7] += l.getInt("admis_garcons");
                n[8] += l.getInt("admis_filles");
            }, tenants, annee);
            jdbc.query("select * from pilotage_examens(?::uuid[], ?)", (ResultSet l) -> {
                int[] x = par.get(l.getObject("tenant_id", UUID.class)).examens
                        .computeIfAbsent(l.getString("examen"), k -> new int[6]);
                x[0] += l.getInt("candidats_garcons");
                x[1] += l.getInt("candidats_filles");
                x[2] += l.getInt("resultats_garcons");
                x[3] += l.getInt("resultats_filles");
                x[4] += l.getInt("admis_garcons");
                x[5] += l.getInt("admis_filles");
            }, tenants, annee);
            jdbc.query("select * from pilotage_periodes(?::uuid[], ?)", (ResultSet l) -> {
                Cumul c = par.get(l.getObject("tenant_id", UUID.class));
                String cle = l.getString("decoupage") + "|" + l.getInt("ordre");
                int[] x = c.periodes.computeIfAbsent(cle, k -> new int[4]);
                x[0] += l.getInt("bulletins_garcons");
                x[1] += l.getInt("bulletins_filles");
                x[2] += l.getInt("admis_garcons");
                x[3] += l.getInt("admis_filles");
                BigDecimal somme = l.getBigDecimal("somme_moyennes");
                if (somme != null) {
                    c.sommesMoyennes.merge(cle, somme, BigDecimal::add);
                }
            }, tenants, annee);
        }
        jdbc.query("select * from pilotage_enseignants(?::uuid[])", (ResultSet l) -> {
            Cumul c = par.get(l.getObject("tenant_id", UUID.class));
            c.titulaires = Personnes.de(l.getInt("titulaires_hommes"), l.getInt("titulaires_femmes"));
            c.vacataires = Personnes.de(l.getInt("vacataires_hommes"), l.getInt("vacataires_femmes"));
        }, tenants);
        jdbc.query("select * from adoption_etablissements(?, ?)", (ResultSet l) -> {
            Cumul c = par.get(l.getObject("tenant_id", UUID.class));
            if (c != null) {
                c.comptes = l.getInt("comptes");
                c.actifs = l.getInt("actifs");
                c.enseignants = l.getInt("enseignants");
                c.enseignantsActifs = l.getInt("enseignants_actifs");
                c.derniereActivite = l.getObject("derniere_activite", LocalDate.class);
            }
        }, debut, fin);
    }

    static String libellePeriode(String decoupage, int ordre) {
        return switch (decoupage) {
            case "TRIMESTRE" -> "Trimestre " + ordre;
            case "SEMESTRE" -> "Semestre " + ordre;
            case "MODULE" -> "Module " + ordre;
            default -> decoupage.toLowerCase(Locale.ROOT) + " " + ordre;
        };
    }

    /** Ordre usuel des niveaux (6e … Tle), puis les autres par ordre alphabétique. */
    private static final List<String> ORDRE_NIVEAUX = List.of("6e", "5e", "4e", "3e", "2nde", "1re", "tle");

    static int comparerNiveaux(String a, String b) {
        int ia = ORDRE_NIVEAUX.indexOf(a.toLowerCase(Locale.ROOT));
        int ib = ORDRE_NIVEAUX.indexOf(b.toLowerCase(Locale.ROOT));
        if (ia >= 0 && ib >= 0) {
            return Integer.compare(ia, ib);
        }
        if (ia >= 0 || ib >= 0) {
            return ia >= 0 ? -1 : 1;
        }
        return a.compareToIgnoreCase(b) != 0 ? a.compareToIgnoreCase(b) : a.compareTo(b);
    }
}
