package bf.edutech.plateforme.territoire;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.territoire.Vues.DirectionChemin;
import bf.edutech.plateforme.territoire.Vues.DirectionVue;
import bf.edutech.plateforme.territoire.Vues.DonneesDirection;
import bf.edutech.plateforme.territoire.Vues.DonneesMinistere;
import bf.edutech.plateforme.territoire.Vues.DonneesPays;
import bf.edutech.plateforme.territoire.Vues.ErreurImport;
import bf.edutech.plateforme.territoire.Vues.MinistereVue;
import bf.edutech.plateforme.territoire.Vues.PaysVue;
import bf.edutech.plateforme.territoire.Vues.RapportImport;

/**
 * Référentiel territorial (super administrateur) : pays, ministères et leurs niveaux, arbre des
 * directions. Règles :
 * <ul>
 * <li>une direction de niveau 1 n'a pas de parent ; une direction de niveau n a pour parent une
 * direction de niveau n-1 du même ministère ;</li>
 * <li>le nombre de niveaux d'un ministère ne change plus dès qu'il a des directions (leurs noms,
 * si) ;</li>
 * <li>rien n'est supprimé : une direction ou un ministère se désactive (les établissements déjà
 * rattachés le restent, mais on ne peut plus en rattacher de nouveaux).</li>
 * </ul>
 */
@Service
public class TerritoireService {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_.-]{1,30}$");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PorteeTerritoire portee;

    TerritoireService(JdbcTemplate jdbc, AuditService audit, PorteeTerritoire portee) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.portee = portee;
    }

    // ================================================================== Pays

    /** Tous les pays (super administrateur) ou le sien (administrateur pays). */
    @Transactional(readOnly = true)
    public List<PaysVue> pays() {
        java.util.Optional<UUID> limite = portee.pays();
        return tousLesPays().stream().filter(p -> limite.isEmpty() || limite.get().equals(p.id())).toList();
    }

    private List<PaysVue> tousLesPays() {
        return jdbc.query("""
                select p.*, (select count(*) from ministere m where m.pays_id = p.id) as ministeres,
                       (select count(*) from tenant t join direction d on d.id = t.direction_id
                          join ministere m on m.id = d.ministere_id where m.pays_id = p.id) as etablissements
                from pays p order by p.nom""", (l, n) -> new PaysVue(l.getObject("id", UUID.class), l.getString("code"),
                l.getString("nom"), l.getString("devise_nationale"), l.getString("indicatif_telephone"),
                l.getInt("longueur_numero"), l.getString("fuseau_horaire"), l.getString("monnaie"), l.getString("langue"),
                l.getInt("ministeres"), l.getInt("etablissements")));
    }

    @Transactional
    public PaysVue creerPays(DonneesPays d) {
        bf.edutech.plateforme.socle.securite.Portee.exigerSuperAdmin();
        verifierFuseau(d.fuseauHoraire());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into pays (id, code, nom, devise_nationale, indicatif_telephone, longueur_numero,
                                      fuseau_horaire, monnaie, langue) values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, d.code().toUpperCase(Locale.ROOT), d.nom().trim(), vide(d.deviseNationale()), d.indicatifTelephone(),
                    d.longueurNumero(), d.fuseauHoraire().trim(), d.monnaie().toUpperCase(Locale.ROOT), langue(d.langue()));
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("PAYS_EXISTANT", "Ce pays existe déjà (code " + d.code().toUpperCase(Locale.ROOT) + ")");
        }
        audit.enregistrer("PAYS_CREE", d.code().toUpperCase(Locale.ROOT), Map.of("nom", d.nom().trim()));
        return unPays(id);
    }

    @Transactional
    public PaysVue modifierPays(UUID id, DonneesPays d) {
        bf.edutech.plateforme.socle.securite.Portee.exigerSuperAdmin();
        unPays(id);
        verifierFuseau(d.fuseauHoraire());
        try {
            jdbc.update("""
                    update pays set code = ?, nom = ?, devise_nationale = ?, indicatif_telephone = ?, longueur_numero = ?,
                                    fuseau_horaire = ?, monnaie = ?, langue = ? where id = ?""",
                    d.code().toUpperCase(Locale.ROOT), d.nom().trim(), vide(d.deviseNationale()), d.indicatifTelephone(),
                    d.longueurNumero(), d.fuseauHoraire().trim(), d.monnaie().toUpperCase(Locale.ROOT), langue(d.langue()), id);
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("PAYS_EXISTANT", "Un autre pays a déjà ce code");
        }
        audit.enregistrer("PAYS_MODIFIE", d.code().toUpperCase(Locale.ROOT), null);
        return unPays(id);
    }

    private PaysVue unPays(UUID id) {
        return tousLesPays().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Pays introuvable"));
    }

    // ================================================================== Ministères

    @Transactional(readOnly = true)
    public List<MinistereVue> ministeres(UUID paysId) {
        portee.verifierPays(paysId);
        Map<UUID, List<String>> niveaux = new HashMap<>();
        jdbc.query("""
                select n.ministere_id, n.libelle from niveau_direction n join ministere m on m.id = n.ministere_id
                where m.pays_id = ? order by n.ministere_id, n.rang""", (java.sql.ResultSet l) -> {
            niveaux.computeIfAbsent(l.getObject(1, UUID.class), k -> new ArrayList<>()).add(l.getString(2));
        }, paysId);
        return jdbc.query("""
                select m.id, m.pays_id, m.sigle, m.nom, m.actif,
                       (select count(*) from direction d where d.ministere_id = m.id) as directions,
                       (select count(*) from tenant t join direction d on d.id = t.direction_id where d.ministere_id = m.id)
                         as etablissements
                from ministere m where m.pays_id = ? order by m.sigle""", (l, n) -> {
            UUID id = l.getObject("id", UUID.class);
            return new MinistereVue(id, l.getObject("pays_id", UUID.class), l.getString("sigle"), l.getString("nom"),
                    l.getBoolean("actif"), niveaux.getOrDefault(id, List.of()), l.getInt("directions"),
                    l.getInt("etablissements"));
        }, paysId);
    }

    @Transactional
    public MinistereVue creerMinistere(UUID paysId, DonneesMinistere d) {
        portee.verifierPays(paysId);
        unPays(paysId);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into ministere (id, pays_id, sigle, nom, actif) values (?, ?, ?, ?, ?)", id, paysId,
                    d.sigle().trim(), d.nom().trim(), d.actif() == null || d.actif());
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("MINISTERE_EXISTANT", "Ce pays a déjà un ministère de sigle « " + d.sigle().trim() + " »");
        }
        ecrireNiveaux(id, d.niveaux());
        audit.enregistrer("MINISTERE_CREE", d.sigle().trim(), Map.of("niveaux", d.niveaux().size()));
        return unMinistere(id);
    }

    @Transactional
    public MinistereVue modifierMinistere(UUID id, DonneesMinistere d) {
        portee.verifierMinistere(id);
        MinistereVue avant = unMinistere(id);
        if (avant.directions() > 0 && d.niveaux().size() != avant.niveaux().size()) {
            throw new RegleMetierException("NIVEAUX_FIGES", "Ce ministère a déjà des directions : le nombre de niveaux ("
                    + avant.niveaux().size() + ") ne peut plus changer, seulement leurs noms");
        }
        try {
            jdbc.update("update ministere set sigle = ?, nom = ?, actif = ? where id = ?", d.sigle().trim(), d.nom().trim(),
                    d.actif() == null || d.actif(), id);
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("MINISTERE_EXISTANT", "Ce pays a déjà un ministère de sigle « " + d.sigle().trim() + " »");
        }
        jdbc.update("delete from niveau_direction where ministere_id = ?", id);
        ecrireNiveaux(id, d.niveaux());
        audit.enregistrer("MINISTERE_MODIFIE", d.sigle().trim(), null);
        return unMinistere(id);
    }

    private void ecrireNiveaux(UUID ministereId, List<String> niveaux) {
        for (int i = 0; i < niveaux.size(); i++) {
            jdbc.update("insert into niveau_direction (ministere_id, rang, libelle) values (?, ?, ?)", ministereId, i + 1,
                    niveaux.get(i).trim());
        }
    }

    private MinistereVue unMinistere(UUID id) {
        UUID pays = jdbc.query("select pays_id from ministere where id = ?", (l, n) -> l.getObject(1, UUID.class), id)
                .stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Ministère introuvable"));
        return ministeres(pays).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    // ================================================================== Directions

    /** Toutes les directions d'un ministère (arbre à plat, trié par niveau puis par nom). */
    @Transactional(readOnly = true)
    public List<DirectionVue> directions(UUID ministereId) {
        portee.verifierMinistere(ministereId);
        unMinistere(ministereId);
        return jdbc.query("""
                select d.id, d.parent_id, d.rang, d.code, d.nom, d.actif,
                       (select count(*) from tenant t where t.direction_id = d.id) as etablissements
                from direction d where d.ministere_id = ? order by d.rang, d.nom""",
                (l, n) -> new DirectionVue(l.getObject("id", UUID.class), l.getObject("parent_id", UUID.class),
                        l.getInt("rang"), l.getString("code"), l.getString("nom"), l.getBoolean("actif"),
                        l.getInt("etablissements")),
                ministereId);
    }

    @Transactional
    public DirectionVue creerDirection(UUID ministereId, DonneesDirection d) {
        portee.verifierMinistere(ministereId);
        MinistereVue m = unMinistere(ministereId);
        int rang = rangSous(m, d.parentId());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into direction (id, ministere_id, parent_id, rang, code, nom, actif) values (?, ?, ?, ?, ?, ?, ?)",
                    id, ministereId, d.parentId(), rang, d.code().trim(), d.nom().trim(), d.actif() == null || d.actif());
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("DIRECTION_EXISTANTE", "Le code « " + d.code().trim() + " » est déjà utilisé dans ce ministère");
        }
        audit.enregistrer("DIRECTION_CREEE", d.code().trim(), Map.of("ministere", m.sigle(), "niveau", rang));
        return uneDirection(ministereId, id);
    }

    @Transactional
    public DirectionVue modifierDirection(UUID id, DonneesDirection d) {
        portee.verifierDirection(id);
        UUID ministereId = ministereDe(id);
        MinistereVue m = unMinistere(ministereId);
        DirectionVue avant = uneDirection(ministereId, id);
        int rang = rangSous(m, d.parentId());
        if (rang != avant.rang()) {
            throw new RegleMetierException("NIVEAU_DIRECTION", "Une direction ne change pas de niveau : choisissez un parent du niveau "
                    + (avant.rang() - 1));
        }
        try {
            jdbc.update("update direction set parent_id = ?, code = ?, nom = ?, actif = ? where id = ?", d.parentId(),
                    d.code().trim(), d.nom().trim(), d.actif() == null || d.actif(), id);
        } catch (DuplicateKeyException e) {
            throw new RegleMetierException("DIRECTION_EXISTANTE", "Le code « " + d.code().trim() + " » est déjà utilisé dans ce ministère");
        }
        audit.enregistrer("DIRECTION_MODIFIEE", d.code().trim(), null);
        return uneDirection(ministereId, id);
    }

    /** Niveau d'une nouvelle direction placée sous {@code parentId} (1 sans parent). */
    private int rangSous(MinistereVue m, UUID parentId) {
        int rang = 1;
        if (parentId != null) {
            List<Object[]> parent = jdbc.query("select ministere_id, rang from direction where id = ?",
                    (l, n) -> new Object[] { l.getObject(1, UUID.class), l.getInt(2) }, parentId);
            if (parent.isEmpty() || !parent.get(0)[0].equals(m.id())) {
                throw new IllegalArgumentException("Direction parente introuvable dans ce ministère");
            }
            rang = (int) parent.get(0)[1] + 1;
        }
        if (rang > m.niveaux().size()) {
            throw new RegleMetierException("NIVEAU_DIRECTION", "Le ministère n'a que " + m.niveaux().size()
                    + " niveau(x) de directions : impossible de créer une direction sous le dernier niveau");
        }
        return rang;
    }

    private UUID ministereDe(UUID directionId) {
        return jdbc.query("select ministere_id from direction where id = ?", (l, n) -> l.getObject(1, UUID.class), directionId)
                .stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Direction introuvable"));
    }

    private DirectionVue uneDirection(UUID ministereId, UUID id) {
        return directions(ministereId).stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Direction introuvable"));
    }

    // ================================================================== Rattachement des établissements

    /** Toutes les directions de tous les ministères, avec leur chemin complet, triées par chemin. */
    @Transactional(readOnly = true)
    public List<DirectionChemin> directionsAvecChemin() {
        java.util.Optional<UUID> limite = portee.pays();
        return toutesLesDirections().stream().filter(d -> limite.isEmpty() || limite.get().equals(d.paysId())).toList();
    }

    private List<DirectionChemin> toutesLesDirections() {
        return jdbc.query("""
                select d.id, m.pays_id, m.id as ministere_id, d.rang,
                       d.rang = (select max(n.rang) from niveau_direction n where n.ministere_id = m.id) as terminale,
                       chemin_direction(d.id) as chemin, d.actif and m.actif as actif
                from direction d join ministere m on m.id = d.ministere_id
                order by chemin""", (l, n) -> new DirectionChemin(l.getObject("id", UUID.class),
                l.getObject("pays_id", UUID.class), l.getObject("ministere_id", UUID.class), l.getInt("rang"),
                l.getBoolean("terminale"), l.getString("chemin"), l.getBoolean("actif")));
    }

    /** Directions du dernier niveau (actives ou non), auxquelles un établissement peut être rattaché. */
    @Transactional(readOnly = true)
    public List<DirectionChemin> directionsTerminales() {
        return directionsAvecChemin().stream().filter(DirectionChemin::terminale).toList();
    }

    /**
     * Vérifie qu'un établissement peut être rattaché à cette direction : existante, active, du
     * dernier niveau de son ministère (lui-même actif).
     */
    @Transactional(readOnly = true)
    public void verifierRattachement(UUID directionId) {
        DirectionChemin d = directionsTerminales().stream().filter(x -> x.id().equals(directionId)).findFirst()
                .orElseThrow(() -> new RegleMetierException("RATTACHEMENT_INVALIDE",
                        "Un établissement se rattache à une direction du dernier niveau (ex. direction provinciale)"));
        if (!d.actif()) {
            throw new RegleMetierException("RATTACHEMENT_INVALIDE", "Cette direction ou son ministère est désactivé");
        }
    }

    /** Une direction et toutes celles qui en dépendent (à tous les niveaux). */
    @Transactional(readOnly = true)
    public List<UUID> descendantes(UUID directionId) {
        return jdbc.queryForList("select directions_descendantes(?)", UUID.class, directionId);
    }

    /** Chemin complet d'une direction : « Burkina Faso · MESFPT · DR … · DP … ». */
    @Transactional(readOnly = true)
    public Optional<String> chemin(UUID directionId) {
        return directionId == null ? Optional.empty()
                : Optional.ofNullable(jdbc.queryForObject("select chemin_direction(?)", String.class, directionId));
    }

    // ================================================================== Import

    private record LigneImport(int numero, String code, String nom, String parent) {
    }

    private record Existante(UUID id, UUID parentId, int rang, String nom) {
    }

    /**
     * Importe les directions d'un ministère depuis un texte CSV (Excel : « Enregistrer sous CSV ») :
     * {@code code;nom;code_parent}, une direction par ligne, code_parent vide pour le niveau 1.
     * Séparateur point-virgule, virgule ou tabulation ; première ligne d'en-têtes facultative.
     * Une direction existante (même code) est mise à jour. Tout ou rien : à la moindre erreur, rien
     * n'est enregistré. En simulation, rien n'est enregistré non plus.
     */
    @Transactional
    public RapportImport importer(UUID ministereId, String contenu, boolean simulation) {
        portee.verifierMinistere(ministereId);
        MinistereVue m = unMinistere(ministereId);
        List<ErreurImport> erreurs = new ArrayList<>();
        List<LigneImport> lignes = lire(contenu, erreurs);

        Map<String, Existante> existantes = new HashMap<>();
        Map<UUID, String> codesParId = new HashMap<>();
        for (DirectionVue d : directions(ministereId)) {
            existantes.put(d.code(), new Existante(d.id(), d.parentId(), d.rang(), d.nom()));
            codesParId.put(d.id(), d.code());
        }
        Map<String, LigneImport> parCode = new LinkedHashMap<>();
        for (LigneImport l : lignes) {
            if (!CODE.matcher(l.code()).matches()) {
                erreurs.add(new ErreurImport(l.numero(), "Code « " + l.code() + " » invalide (lettres, chiffres, - _ ., 30 au plus)"));
            } else if (parCode.containsKey(l.code())) {
                erreurs.add(new ErreurImport(l.numero(), "Code « " + l.code() + " » en double dans le fichier"));
            } else if (l.nom().isBlank() || l.nom().length() > 200) {
                erreurs.add(new ErreurImport(l.numero(), "Nom obligatoire (200 caractères au plus)"));
            } else {
                parCode.put(l.code(), l);
            }
        }
        // Niveau de chaque ligne d'après ses parents (dans le fichier ou déjà enregistrés)
        Map<String, Integer> rangs = new HashMap<>();
        for (LigneImport l : parCode.values()) {
            Integer r = rang(l.code(), parCode, existantes, codesParId, rangs, new HashSet<>());
            if (r == null) {
                erreurs.add(new ErreurImport(l.numero(), "Parent « " + l.parent() + " » introuvable (ni dans le fichier, ni déjà enregistré) ou boucle"));
            } else if (r > m.niveaux().size()) {
                erreurs.add(new ErreurImport(l.numero(), "Niveau " + r + " : le ministère n'a que " + m.niveaux().size() + " niveau(x)"));
            } else if (existantes.containsKey(l.code()) && existantes.get(l.code()).rang() != r) {
                erreurs.add(new ErreurImport(l.numero(), "« " + l.code() + " » existe déjà au niveau " + existantes.get(l.code()).rang()
                        + " : une direction ne change pas de niveau"));
            }
        }
        int creees = 0;
        int modifiees = 0;
        int inchangees = 0;
        for (LigneImport l : parCode.values()) {
            Existante e = existantes.get(l.code());
            if (e == null) {
                creees++;
            } else if (e.nom().equals(l.nom()) && Objects.equals(codesParId.get(e.parentId()), vide(l.parent()))) {
                inchangees++;
            } else {
                modifiees++;
            }
        }
        erreurs.sort(Comparator.comparingInt(ErreurImport::ligne));
        if (!erreurs.isEmpty() || simulation) {
            return new RapportImport(simulation, lignes.size(), creees, modifiees, inchangees, erreurs);
        }
        // Enregistrement, parents d'abord
        Map<String, UUID> ids = new HashMap<>();
        existantes.forEach((code, e) -> ids.put(code, e.id()));
        List<LigneImport> ordre = new ArrayList<>(parCode.values());
        ordre.sort(Comparator.comparingInt(l -> rangs.get(l.code())));
        for (LigneImport l : ordre) {
            UUID parent = vide(l.parent()) == null ? null : ids.get(l.parent());
            Existante e = existantes.get(l.code());
            if (e == null) {
                UUID id = UUID.randomUUID();
                jdbc.update("insert into direction (id, ministere_id, parent_id, rang, code, nom) values (?, ?, ?, ?, ?, ?)",
                        id, ministereId, parent, rangs.get(l.code()), l.code(), l.nom());
                ids.put(l.code(), id);
            } else {
                jdbc.update("update direction set nom = ?, parent_id = ? where id = ?", l.nom(), parent, e.id());
            }
        }
        audit.enregistrer("DIRECTIONS_IMPORTEES", m.sigle(), Map.of("creees", creees, "modifiees", modifiees));
        return new RapportImport(false, lignes.size(), creees, modifiees, inchangees, List.of());
    }

    private static Integer rang(String code, Map<String, LigneImport> fichier, Map<String, Existante> existantes,
            Map<UUID, String> codesParId, Map<String, Integer> connus, Set<String> enCours) {
        if (connus.containsKey(code)) {
            return connus.get(code);
        }
        if (!enCours.add(code)) {
            return null; // boucle
        }
        Integer r;
        LigneImport l = fichier.get(code);
        String parent = l != null ? vide(l.parent()) : null;
        if (l == null) {
            Existante e = existantes.get(code);
            r = e == null ? null : e.rang();
        } else if (parent == null) {
            r = 1;
        } else if (!fichier.containsKey(parent) && !existantes.containsKey(parent)) {
            r = null;
        } else {
            Integer p = rang(parent, fichier, existantes, codesParId, connus, enCours);
            r = p == null ? null : p + 1;
        }
        if (r != null) {
            connus.put(code, r);
        }
        return r;
    }

    /** Lignes du CSV (séparateur deviné sur la première ligne, en-tête facultatif). */
    static List<LigneImport> lire(String contenu, List<ErreurImport> erreurs) {
        List<LigneImport> lignes = new ArrayList<>();
        String[] brutes = contenu.replace("﻿", "").split("\r?\n");
        String separateur = null;
        for (int i = 0; i < brutes.length; i++) {
            String brute = brutes[i];
            if (brute.isBlank()) {
                continue;
            }
            if (separateur == null) {
                separateur = brute.contains(";") ? ";" : brute.contains("\t") ? "\t" : ",";
                if (brute.toLowerCase(Locale.ROOT).replace("\"", "").startsWith("code")) {
                    continue; // en-têtes
                }
            }
            String[] c = brute.split(Pattern.quote(separateur), -1);
            if (c.length < 2) {
                erreurs.add(new ErreurImport(i + 1, "Ligne incomplète : code" + separateur + "nom" + separateur + "code_parent attendus"));
                continue;
            }
            lignes.add(new LigneImport(i + 1, nettoyer(c[0]), nettoyer(c[1]), c.length > 2 ? nettoyer(c[2]) : ""));
        }
        return lignes;
    }

    private static String nettoyer(String v) {
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s.trim();
    }

    // ================================================================== Outils

    private static void verifierFuseau(String fuseau) {
        try {
            ZoneId.of(fuseau.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Fuseau horaire inconnu : utilisez un nom du type Africa/Ouagadougou");
        }
    }

    private static String langue(String langue) {
        return langue == null || langue.isBlank() ? "fr" : langue.trim().toLowerCase(Locale.ROOT);
    }

    private static String vide(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
