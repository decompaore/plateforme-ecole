package bf.edutech.plateforme.professeurprincipal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.messagerie.MessagerieService;
import bf.edutech.plateforme.professeurprincipal.Vues.ClassePrincipale;
import bf.edutech.plateforme.professeurprincipal.Vues.EnseignantClasse;
import bf.edutech.plateforme.professeurprincipal.Vues.EtatVerification;
import bf.edutech.plateforme.professeurprincipal.Vues.StatutVerification;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Désignation du professeur principal de chaque classe (v0.49.1), par le chef d'établissement ou le censeur, parmi
 * les enseignants de la classe. Sert aussi les autres services du module (qui est professeur principal, à qui écrire)
 * et le module des bulletins (vérification des notes attestée avant le conseil et la génération).
 */
@Service
public class ProfesseursPrincipauxService {

    static final String[] DESIGNATION = { "ADMIN_ECOLE", "CENSEUR" };

    private final JdbcTemplate jdbc;
    private final EnseignantsService enseignants;
    private final MessagerieService messagerie;
    private final MembresService membres;
    private final AuditService audit;

    ProfesseursPrincipauxService(JdbcTemplate jdbc, EnseignantsService enseignants, MessagerieService messagerie,
            MembresService membres, AuditService audit) {
        this.jdbc = jdbc;
        this.enseignants = enseignants;
        this.messagerie = messagerie;
        this.membres = membres;
        this.audit = audit;
    }

    // ================================================================== tableau des classes

    /** Classes de l'année, leur professeur principal, leurs enseignants, dossiers ouverts et vérifications. */
    @Transactional(readOnly = true)
    public List<ClassePrincipale> classes(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        return tableau(anneeId, null);
    }

    /** Classes dont l'utilisateur connecté est le professeur principal (toutes années ouvertes confondues). */
    @Transactional(readOnly = true)
    public List<ClassePrincipale> mesClasses(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        return tableau(anneeId, UtilisateurConnecte.id());
    }

    private List<ClassePrincipale> tableau(UUID anneeId, UUID seulementPour) {
        Map<UUID, List<EnseignantClasse>> parClasse = new LinkedHashMap<>();
        Map<UUID, Map<UUID, EnseignantClasse>> index = new LinkedHashMap<>();
        jdbc.query("""
                select cm.classe_id, s.utilisateur_id, s.nom || ' ' || s.prenoms, m.libelle
                from classe_matiere cm
                join classe k on k.tenant_id = cm.tenant_id and k.id = cm.classe_id
                join matiere m on m.tenant_id = cm.tenant_id and m.id = cm.matiere_id
                join engagement_enseignant g on g.tenant_id = cm.tenant_id and g.id = cm.engagement_id
                     and g.statut = 'ACTIF'
                join enseignant s on s.id = g.enseignant_id
                where k.annee_id = ? order by 3, m.libelle""", (org.springframework.jdbc.core.RowCallbackHandler) l -> {
            UUID classe = l.getObject(1, UUID.class);
            UUID u = l.getObject(2, UUID.class);
            String nom = l.getString(3);
            EnseignantClasse e = index.computeIfAbsent(classe, k -> new LinkedHashMap<>()).computeIfAbsent(u, k -> {
                EnseignantClasse n = new EnseignantClasse(u, nom, new ArrayList<>());
                parClasse.computeIfAbsent(classe, x -> new ArrayList<>()).add(n);
                return n;
            });
            e.matieres().add(l.getString(4));
        }, anneeId);
        Map<UUID, List<EtatVerification>> verifs = new LinkedHashMap<>();
        jdbc.query("""
                select v.classe_id, v.periode_id, p.libelle, v.statut,
                       (select count(*) from reclamation_note r where r.tenant_id = v.tenant_id
                          and r.classe_id = v.classe_id and r.periode_id = v.periode_id and r.statut = 'OUVERTE')
                from verification_notes v
                join classe k on k.tenant_id = v.tenant_id and k.id = v.classe_id
                join periode p on p.tenant_id = v.tenant_id and p.id = v.periode_id
                where k.annee_id = ? order by p.ordre""", (org.springframework.jdbc.core.RowCallbackHandler) l ->
                verifs.computeIfAbsent(l.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new EtatVerification(l.getObject(2, UUID.class), l.getString(3),
                                StatutVerification.valueOf(l.getString(4)), l.getInt(5))), anneeId);
        String filtre = seulementPour == null ? "" : " and p.utilisateur_id = ?";
        Object[] args = seulementPour == null ? new Object[] { anneeId } : new Object[] { anneeId, seulementPour };
        return jdbc.query("""
                select k.id, k.code, k.niveau, p.utilisateur_id, u.nom || ' ' || u.prenoms, p.designe_le,
                       (select count(*) from saisine_classe s where s.tenant_id = k.tenant_id and s.classe_id = k.id
                          and s.statut <> 'CLOSE'), f.profil_id
                from classe k
                join filiere f on f.tenant_id = k.tenant_id and f.id = k.filiere_id
                left join professeur_principal p on p.tenant_id = k.tenant_id and p.classe_id = k.id
                left join utilisateur u on u.id = p.utilisateur_id
                where k.annee_id = ?""" + filtre + " order by k.niveau, k.code", (l, n) -> {
            UUID id = l.getObject(1, UUID.class);
            Timestamp t = l.getTimestamp(6);
            return new ClassePrincipale(id, l.getString(2), l.getString(3), l.getObject(8, UUID.class),
                    l.getObject(4, UUID.class), l.getString(5),
                    t == null ? null : t.toInstant(), parClasse.getOrDefault(id, List.of()), l.getInt(7),
                    verifs.getOrDefault(id, List.of()));
        }, args);
    }

    // ================================================================== désignation

    @Transactional
    public ClassePrincipale designer(UUID classeId, UUID utilisateurId) {
        UtilisateurConnecte.etablissementActif();
        exigerDesignation();
        Classe c = classe(classeId);
        if (utilisateurId == null) {
            throw new IllegalArgumentException("Enseignant obligatoire");
        }
        if (!enseignants.enseigneDans(utilisateurId, classeId)) {
            throw new RegleMetierException("ENSEIGNANT_HORS_CLASSE",
                    "Le professeur principal est choisi parmi les enseignants de la " + c.code);
        }
        Optional<UUID> avant = professeurDe(classeId);
        jdbc.update("""
                insert into professeur_principal (tenant_id, classe_id, utilisateur_id, designe_par)
                values (tenant_courant(), ?, ?, ?)
                on conflict (tenant_id, classe_id) do update
                set utilisateur_id = excluded.utilisateur_id, designe_par = excluded.designe_par, designe_le = now()""",
                classeId, utilisateurId, UtilisateurConnecte.id());
        // Les dossiers en cours passent au nouveau professeur principal
        jdbc.update("update saisine_classe set professeur_id = ? where classe_id = ? and statut <> 'CLOSE'",
                utilisateurId, classeId);
        if (avant.isEmpty() || !avant.get().equals(utilisateurId)) {
            informer(utilisateurId, "Professeur principal de la " + c.code,
                    "Vous êtes désigné(e) professeur principal de la classe " + c.code + ".\n\n"
                            + "Vous portez la voix des enseignants de la classe auprès de l'administration, vous êtes "
                            + "l'interlocuteur des élèves en cas de souci avec un enseignant, et vous vérifiez avec "
                            + "eux les notes et moyennes avant chaque conseil de classe.\n\n"
                            + "Retrouvez vos classes dans « Professeur principal ».");
        }
        audit.enregistrer("PROFESSEUR_PRINCIPAL_DESIGNE", c.code, Map.of("utilisateur", utilisateurId));
        return tableau(c.anneeId, null).stream().filter(x -> x.classeId().equals(classeId)).findFirst().orElseThrow();
    }

    @Transactional
    public void retirer(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        exigerDesignation();
        Classe c = classe(classeId);
        jdbc.update("delete from professeur_principal where classe_id = ?", classeId);
        audit.enregistrer("PROFESSEUR_PRINCIPAL_RETIRE", c.code, null);
    }

    // ================================================================== services du module et des bulletins

    /** Professeur principal de la classe, s'il est désigné. */
    @Transactional(readOnly = true)
    public Optional<UUID> professeurDe(UUID classeId) {
        return jdbc.queryForList("select utilisateur_id from professeur_principal where classe_id = ?", UUID.class,
                classeId).stream().findFirst();
    }

    /** L'utilisateur connecté est-il le professeur principal de la classe ? */
    boolean estProfesseurPrincipal(UUID classeId) {
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        return moi != null && professeurDe(classeId).map(moi::equals).orElse(false);
    }

    /**
     * Le conseil de classe et les bulletins d'une classe qui a un professeur principal attendent qu'il ait attesté la
     * vérification des notes de la période avec les élèves.
     */
    @Transactional(readOnly = true)
    public void exigerVerificationValidee(UUID classeId, UUID periodeId) {
        if (professeurDe(classeId).isEmpty()) {
            return;
        }
        List<String> statut = jdbc.queryForList(
                "select statut from verification_notes where classe_id = ? and periode_id = ?", String.class,
                classeId, periodeId);
        if (statut.isEmpty() || !"VALIDEE".equals(statut.get(0))) {
            String code = jdbc.queryForObject("select code from classe where id = ?", String.class, classeId);
            String periode = jdbc.queryForObject("select libelle from periode where id = ?", String.class, periodeId);
            throw new RegleMetierException("VERIFICATION_NOTES_REQUISE", "Le professeur principal de la " + code
                    + " n'a pas encore attesté la vérification des notes « " + periode + " » avec les élèves");
        }
    }

    // ================================================================== outils partagés

    record Classe(UUID id, String code, UUID anneeId) {
    }

    Classe classe(UUID classeId) {
        return jdbc.query("select id, code, annee_id from classe where id = ?",
                (l, n) -> new Classe(l.getObject(1, UUID.class), l.getString(2), l.getObject(3, UUID.class)),
                classeId).stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Classe introuvable"));
    }

    boolean enseigneDans(UUID utilisateurId, UUID classeId) {
        return utilisateurId != null && enseignants.enseigneDans(utilisateurId, classeId);
    }

    String nom(UUID utilisateurId) {
        return jdbc.queryForList("select nom || ' ' || prenoms from utilisateur where id = ?", String.class,
                utilisateurId).stream().findFirst().orElse("");
    }

    /** Membres actifs de l'établissement qui ont l'un de ces rôles. */
    List<UUID> ayantRole(Role... roles) {
        Set<Role> r = Set.of(roles);
        return membres.lister().stream().filter(m -> m.actif() && r.contains(m.role())).map(m -> m.utilisateurId())
                .distinct().toList();
    }

    /** Message dans la messagerie de l'établissement (jamais à soi-même ; un ancien membre est ignoré). */
    void informer(UUID destinataire, String objet, String corps) {
        if (destinataire == null || destinataire.equals(UtilisateurConnecte.idSiConnecte().orElse(null))) {
            return;
        }
        try {
            messagerie.transmettre(destinataire, objet.length() > 150 ? objet.substring(0, 150) : objet, corps,
                    "Professeur principal");
        } catch (IllegalArgumentException e) {
            // Destinataire qui n'est plus membre du personnel : le dossier reste consultable
        }
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static String texte(String s, int max, String quoi) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(quoi + " obligatoire");
        }
        if (s.strip().length() > max) {
            throw new IllegalArgumentException(quoi + " de " + max + " caractères au plus");
        }
        return s.strip();
    }

    private static void exigerDesignation() {
        if (!UtilisateurConnecte.aUnRole(DESIGNATION)) {
            throw new AccesRefuseException("Le chef d'établissement ou le censeur désigne le professeur principal");
        }
    }
}
