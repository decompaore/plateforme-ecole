package bf.edutech.plateforme.moyens;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * État annuel des besoins de l'établissement (v0.48). Le censeur exprime les besoins du domaine général, le chef des
 * travaux ceux du domaine technique ; le proviseur complète, corrige et transmet l'état à sa direction. Une fois
 * transmis, l'état n'est plus modifiable, sauf si la direction le renvoie pour correction.
 */
@Service
public class EtatBesoinsService {

    public enum StatutEtat {
        BROUILLON, TRANSMIS, RENVOYE
    }

    public record DonneesLigne(Categorie categorie, Domaine domaine, String designation, String unite,
            BigDecimal quantite, Integer priorite, String justification) {
    }

    public record DemandeTransmission(String observations) {
    }

    public record AnneeVue(UUID id, String libelle, boolean active) {
    }

    public record LigneVue(UUID id, Categorie categorie, Domaine domaine, String designation, String unite,
            BigDecimal quantite, int priorite, String justification, String proposePar, Instant modifieeLe,
            boolean peutModifier) {
    }

    public record EtatVue(UUID id, UUID anneeId, String annee, List<AnneeVue> annees, StatutEtat statut,
            Instant transmisLe, String transmisPar, String direction, Instant renvoyeLe, String motifRenvoi,
            String observations, boolean modifiable, boolean peutTransmettre, Set<Domaine> domaines,
            List<LigneVue> lignes) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    EtatBesoinsService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public EtatVue etat(UUID anneeId) {
        List<AnneeVue> annees = jdbc.query("""
                select id, libelle, etat = 'ACTIVE' from annee_scolaire
                order by (etat = 'ACTIVE') desc, debut desc""",
                (l, n) -> new AnneeVue(l.getObject(1, UUID.class), l.getString(2), l.getBoolean(3)));
        if (annees.isEmpty()) {
            throw new RegleMetierException("AUCUNE_ANNEE", "Créez d'abord l'année scolaire");
        }
        AnneeVue annee = anneeId == null ? annees.get(0)
                : annees.stream().filter(a -> a.id().equals(anneeId)).findFirst()
                        .orElseThrow(() -> new RessourceIntrouvableException("Année scolaire introuvable"));
        UUID etat = etatId(annee.id());
        Remontee r = remontee(annee.libelle());
        StatutEtat statut = statut(r);
        boolean modifiable = statut != StatutEtat.TRANSMIS;
        Set<Domaine> domaines = domainesModifiables();
        String direction = jdbc.queryForList("""
                select d.nom from tenant t join direction d on d.id = t.direction_id where t.id = tenant_courant()""",
                String.class).stream().findFirst().orElse(null);
        List<LigneVue> lignes = etat == null ? List.of() : jdbc.query("""
                select l.id, l.categorie, l.domaine, l.designation, l.unite, l.quantite, l.priorite, l.justification,
                       u.nom || ' ' || u.prenoms, l.modifiee_le
                from ligne_etat_besoins l left join utilisateur u on u.id = l.propose_par
                where l.etat_id = ? order by l.categorie, l.priorite, l.designation""", (l, n) -> {
            Domaine d = Domaine.valueOf(l.getString(3));
            return new LigneVue(l.getObject(1, UUID.class), Categorie.valueOf(l.getString(2)), d, l.getString(4),
                    l.getString(5), l.getBigDecimal(6), l.getInt(7), l.getString(8), l.getString(9),
                    l.getTimestamp(10).toInstant(), modifiable && domaines.contains(d));
        }, etat);
        String observations = etat == null ? null
                : jdbc.queryForObject("select observations from etat_besoins where id = ?", String.class, etat);
        return new EtatVue(etat, annee.id(), annee.libelle(), annees, statut, r == null ? null : r.transmiseLe,
                r == null ? null : r.transmisePar, direction, r == null ? null : r.renvoyeeLe,
                r == null ? null : r.motif, observations, modifiable,
                modifiable && UtilisateurConnecte.aUnRole("ADMIN_ECOLE") && !lignes.isEmpty() && direction != null,
                modifiable ? domaines : Set.of(), lignes);
    }

    // ------------------------------------------------------------------ saisie

    @Transactional
    public EtatVue ajouter(UUID anneeId, DonneesLigne d) {
        valider(d);
        String libelle = libelleAnnee(anneeId);
        exigerModifiable(libelle);
        exigerDomaine(d.domaine());
        UUID etat = etatId(anneeId);
        if (etat == null) {
            etat = UUID.randomUUID();
            jdbc.update("insert into etat_besoins (tenant_id, id, annee_id) values (tenant_courant(), ?, ?)", etat,
                    anneeId);
        }
        jdbc.update("""
                insert into ligne_etat_besoins (tenant_id, id, etat_id, categorie, domaine, designation, unite, quantite,
                    priorite, justification, propose_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), etat,
                d.categorie().name(), d.domaine().name(), texte(d.designation()), texte(d.unite()), d.quantite(),
                priorite(d), vide(d.justification()), UtilisateurConnecte.id());
        return etat(anneeId);
    }

    @Transactional
    public EtatVue modifier(UUID ligneId, DonneesLigne d) {
        valider(d);
        Ligne l = ligne(ligneId);
        exigerModifiable(l.annee);
        exigerDomaine(l.domaine);
        exigerDomaine(d.domaine());
        jdbc.update("""
                update ligne_etat_besoins set categorie = ?, domaine = ?, designation = ?, unite = ?, quantite = ?,
                    priorite = ?, justification = ?, propose_par = ?, modifiee_le = now()
                where id = ?""", d.categorie().name(), d.domaine().name(), texte(d.designation()), texte(d.unite()),
                d.quantite(), priorite(d), vide(d.justification()), UtilisateurConnecte.id(), ligneId);
        return etat(l.anneeId);
    }

    @Transactional
    public EtatVue supprimer(UUID ligneId) {
        Ligne l = ligne(ligneId);
        exigerModifiable(l.annee);
        exigerDomaine(l.domaine);
        jdbc.update("delete from ligne_etat_besoins where id = ?", ligneId);
        return etat(l.anneeId);
    }

    // ------------------------------------------------------------------ transmission

    /** Le proviseur transmet l'état à la direction de rattachement de l'établissement. */
    @Transactional
    public EtatVue transmettre(UUID anneeId, DemandeTransmission demande) {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Seul le chef d'établissement transmet l'état des besoins");
        }
        String libelle = libelleAnnee(anneeId);
        exigerModifiable(libelle);
        UUID etat = etatId(anneeId);
        Integer nombre = etat == null ? 0
                : jdbc.queryForObject("select count(*) from ligne_etat_besoins where etat_id = ?", Integer.class, etat);
        if (nombre == null || nombre == 0) {
            throw new RegleMetierException("ETAT_VIDE", "L'état des besoins ne contient aucune ligne");
        }
        UUID tenant = UtilisateurConnecte.etablissementActif();
        UUID direction = jdbc.queryForObject("select direction_id from tenant where id = ?", UUID.class, tenant);
        if (direction == null) {
            throw new RegleMetierException("SANS_DIRECTION",
                    "L'établissement n'est rattaché à aucune direction : contactez l'administrateur du pays");
        }
        String observations = demande == null ? null : vide(demande.observations());
        jdbc.update("update etat_besoins set observations = ? where id = ?", observations, etat);
        UUID utilisateur = UtilisateurConnecte.id();
        Remontee existante = remontee(libelle);
        UUID remontee;
        if (existante == null) {
            remontee = UUID.randomUUID();
            jdbc.update("""
                    insert into remontee_besoins (id, tenant_id, etat_id, annee, direction_id, observations, transmise_par)
                    values (?, ?, ?, ?, ?, ?, ?)""", remontee, tenant, etat, libelle, direction, observations, utilisateur);
        } else {
            remontee = existante.id;
            jdbc.update("delete from ligne_remontee where remontee_id = ?", remontee);
            jdbc.update("""
                    update remontee_besoins set statut = 'RECUE', etat_id = ?, direction_id = ?, observations = ?,
                        transmise_le = now(), transmise_par = ?, renvoyee_le = null, renvoyee_par = null,
                        motif_renvoi = null
                    where id = ?""", etat, direction, observations, utilisateur, remontee);
        }
        List<Object[]> lignes = new ArrayList<>();
        jdbc.query("""
                select id, categorie, domaine, designation, unite, quantite, priorite, justification
                from ligne_etat_besoins where etat_id = ? order by categorie, priorite, designation""", l -> {
            lignes.add(new Object[] { UUID.randomUUID(), remontee, l.getObject(1, UUID.class), lignes.size() + 1,
                    l.getString(2), l.getString(3), l.getString(4), l.getString(5), l.getBigDecimal(6), l.getInt(7),
                    l.getString(8) });
        }, etat);
        jdbc.batchUpdate("""
                insert into ligne_remontee (id, remontee_id, ligne_origine_id, ordre, categorie, domaine, designation,
                    unite, quantite, priorite, justification) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", lignes);
        audit.enregistrer("BESOINS_TRANSMIS", libelle, Map.of("lignes", lignes.size()));
        return etat(anneeId);
    }

    // ------------------------------------------------------------------ outils

    private record Remontee(UUID id, String statut, Instant transmiseLe, String transmisePar, Instant renvoyeeLe,
            String motif) {
    }

    private record Ligne(UUID anneeId, String annee, Domaine domaine) {
    }

    private Remontee remontee(String annee) {
        UUID tenant = UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select r.id, r.statut, r.transmise_le, u.nom || ' ' || u.prenoms, r.renvoyee_le, r.motif_renvoi
                from remontee_besoins r left join utilisateur u on u.id = r.transmise_par
                where r.tenant_id = ? and r.annee = ?""",
                (l, n) -> new Remontee(l.getObject(1, UUID.class), l.getString(2), instant(l.getTimestamp(3)),
                        l.getString(4), instant(l.getTimestamp(5)), l.getString(6)),
                tenant, annee).stream().findFirst().orElse(null);
    }

    private static StatutEtat statut(Remontee r) {
        if (r == null) {
            return StatutEtat.BROUILLON;
        }
        return "RENVOYEE".equals(r.statut) ? StatutEtat.RENVOYE : StatutEtat.TRANSMIS;
    }

    private void exigerModifiable(String annee) {
        if (statut(remontee(annee)) == StatutEtat.TRANSMIS) {
            throw new RegleMetierException("ETAT_TRANSMIS",
                    "L'état des besoins a été transmis à la direction : il n'est plus modifiable");
        }
    }

    /** Domaines que la personne connectée peut renseigner. */
    static Set<Domaine> domainesModifiables() {
        if (UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            return EnumSet.allOf(Domaine.class);
        }
        Set<Domaine> r = EnumSet.noneOf(Domaine.class);
        if (UtilisateurConnecte.aUnRole("CENSEUR")) {
            r.add(Domaine.GENERAL);
        }
        if (UtilisateurConnecte.aUnRole("CHEF_TRAVAUX")) {
            r.add(Domaine.TECHNIQUE);
        }
        return r;
    }

    private static void exigerDomaine(Domaine d) {
        if (!domainesModifiables().contains(d)) {
            throw new AccesRefuseException(d == Domaine.TECHNIQUE
                    ? "Les besoins du domaine technique relèvent du chef des travaux"
                    : "Les besoins du domaine général relèvent du censeur");
        }
    }

    private UUID etatId(UUID anneeId) {
        return jdbc.queryForList("select id from etat_besoins where annee_id = ?", UUID.class, anneeId).stream()
                .findFirst().orElse(null);
    }

    private String libelleAnnee(UUID anneeId) {
        return jdbc.queryForList("select libelle from annee_scolaire where id = ?", String.class, anneeId).stream()
                .findFirst().orElseThrow(() -> new RessourceIntrouvableException("Année scolaire introuvable"));
    }

    private Ligne ligne(UUID id) {
        return jdbc.query("""
                select e.annee_id, a.libelle, l.domaine from ligne_etat_besoins l
                join etat_besoins e on e.tenant_id = l.tenant_id and e.id = l.etat_id
                join annee_scolaire a on a.tenant_id = e.tenant_id and a.id = e.annee_id
                where l.id = ?""", (l, n) -> new Ligne(l.getObject(1, UUID.class), l.getString(2),
                Domaine.valueOf(l.getString(3))), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Ligne introuvable"));
    }

    private static void valider(DonneesLigne d) {
        if (d == null || d.categorie() == null || d.domaine() == null) {
            throw new IllegalArgumentException("Catégorie et domaine obligatoires");
        }
        if (d.designation() == null || d.designation().isBlank() || d.designation().strip().length() > 200) {
            throw new IllegalArgumentException("Désignation obligatoire (200 caractères au plus)");
        }
        if (d.unite() == null || d.unite().isBlank() || d.unite().strip().length() > 30) {
            throw new IllegalArgumentException("Unité obligatoire (30 caractères au plus)");
        }
        if (d.quantite() == null || d.quantite().signum() <= 0) {
            throw new IllegalArgumentException("La quantité doit être positive");
        }
        if (d.priorite() != null && (d.priorite() < 1 || d.priorite() > 3)) {
            throw new IllegalArgumentException("Priorité de 1 (urgente) à 3");
        }
        if (d.justification() != null && d.justification().length() > 500) {
            throw new IllegalArgumentException("Justification de 500 caractères au plus");
        }
    }

    private static int priorite(DonneesLigne d) {
        return d.priorite() == null ? 2 : d.priorite();
    }

    static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Sans espaces superflus : les consolidations rapprochent les désignations. */
    static String texte(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
