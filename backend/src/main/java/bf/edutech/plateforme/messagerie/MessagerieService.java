package bf.edutech.plateforme.messagerie;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.FamillesDestinataires;
import bf.edutech.plateforme.eleves.FamillesDestinataires.Famille;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembreVue;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Messagerie de l'établissement (v0.46).
 * <ul>
 * <li>Le chef d'établissement écrit à des groupes (tout le personnel, les enseignants, l'administration, tous les
 * parents), à certains rôles, aux parents de certaines classes, ou à des personnes choisies.</li>
 * <li>Chaque destinataire qui a un compte lit le message dans l'application (lu / non lu).</li>
 * <li>Avec l'option SMS, le message part aussi par SMS (file d'envoi) : c'est le seul moyen de joindre un parent
 * sans compte.</li>
 * </ul>
 */
@Service
public class MessagerieService {

    /** Groupes de destinataires. {@code PERSONNEL} = enseignants et administration. */
    public enum Groupe {
        PERSONNEL, ENSEIGNANTS, ADMINISTRATION, PARENTS
    }

    /** Rôles de l'administration de l'établissement. */
    static final Set<Role> ADMINISTRATION = EnumSet.of(Role.ADMIN_ECOLE, Role.CENSEUR, Role.CHEF_TRAVAUX,
            Role.SECRETARIAT, Role.INTENDANT, Role.SURVEILLANT);

    static final int OBJET_MAX = 150;
    static final int CORPS_MAX = 2000;
    static final int DESTINATAIRES_MAX = 5000;

    public record Demande(String objet, String corps, Boolean sms, List<Groupe> groupes, List<Role> roles,
            List<UUID> classes, List<UUID> utilisateurs, List<UUID> responsables) {

        /** Envoi par SMS demandé ; absent (null) : non. */
        public boolean parSms() {
            return Boolean.TRUE.equals(sms);
        }
    }

    /** Avant l'envoi : qui sera joint, et combien de SMS partiront. */
    public record Apercu(int destinataires, int comptes, int smsSeulement, int injoignables, int sms) {
    }

    public record Envoi(UUID id, int destinataires, int comptes, int sms) {
    }

    public record MessageResume(UUID id, String objet, String extrait, String auteur, Instant envoyeLe, boolean lu) {
    }

    public record Boite(List<MessageResume> messages, int total, int nonLus) {
    }

    public record MessageVue(UUID id, String objet, String corps, String auteur, Instant envoyeLe, Instant luLe,
            boolean sms) {
    }

    public record MessageEnvoye(UUID id, String objet, String cibles, String auteur, Instant envoyeLe, boolean sms,
            int destinataires, int comptes, int lus) {
    }

    public record DestinataireVue(String nom, String qualite, boolean compte, boolean sms, Instant luLe) {
    }

    /** Une personne de l'annuaire : un compte, ou un parent sans compte (joint seulement par SMS). */
    public record Personne(UUID utilisateurId, UUID responsableId, String nom, String qualite, String categorie) {
    }

    /** Destinataire résolu. */
    private record Dest(UUID utilisateurId, UUID responsableId, String nom, String qualite, String telephone,
            String langue) {
    }

    private final JdbcTemplate jdbc;
    private final MembresService membres;
    private final FamillesDestinataires familles;
    private final AnneesService annees;
    private final NotificationsService notifications;
    private final AuditService audit;

    MessagerieService(JdbcTemplate jdbc, MembresService membres, FamillesDestinataires familles, AnneesService annees,
            NotificationsService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.membres = membres;
        this.familles = familles;
        this.annees = annees;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ envoi (chef d'établissement)

    @Transactional(readOnly = true)
    public Apercu apercu(Demande d) {
        UtilisateurConnecte.etablissementActif();
        return compter(resoudre(d), d.parSms());
    }

    @Transactional
    public Envoi envoyer(Demande d) {
        UtilisateurConnecte.etablissementActif();
        String objet = d.objet() == null ? "" : d.objet().trim();
        String corps = d.corps() == null ? "" : d.corps().trim();
        if (objet.isEmpty() || objet.length() > OBJET_MAX) {
            throw new IllegalArgumentException("Objet obligatoire (" + OBJET_MAX + " caractères au plus)");
        }
        if (corps.isEmpty() || corps.length() > CORPS_MAX) {
            throw new IllegalArgumentException("Message obligatoire (" + CORPS_MAX + " caractères au plus)");
        }
        List<Dest> dests = resoudre(d);
        Apercu a = compter(dests, d.parSms());
        if (a.comptes() + a.smsSeulement() == 0) {
            throw new RegleMetierException("AUCUN_DESTINATAIRE", a.injoignables() > 0
                    ? "Ces parents n'ont pas de compte : cochez l'envoi par SMS pour les joindre"
                    : "Aucun destinataire : choisissez un groupe, une classe ou des personnes");
        }
        if (dests.size() > DESTINATAIRES_MAX) {
            throw new IllegalArgumentException(DESTINATAIRES_MAX + " destinataires au plus par message");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into message (tenant_id, id, auteur_id, objet, corps, sms, cibles)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?)""",
                id, UtilisateurConnecte.id(), objet, corps, d.parSms(), cibles(d));
        String etablissement = d.parSms() ? notifications.nomEtablissement() : null;
        String texte = d.parSms() ? etablissement + " : " + objet + ". " + corps : null;
        int sms = 0;
        for (Dest x : dests) {
            boolean parSms = d.parSms() && x.telephone() != null && !x.telephone().isBlank();
            if (x.utilisateurId() == null && !parSms) {
                continue;                                   // parent sans compte, sans SMS : injoignable
            }
            UUID ligne = UUID.randomUUID();
            jdbc.update("""
                    insert into message_destinataire (tenant_id, id, message_id, utilisateur_id, responsable_id, nom,
                                                      qualite, telephone, sms)
                    values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?)""",
                    ligne, id, x.utilisateurId(), x.responsableId(), tronquer(x.nom(), 220), tronquer(x.qualite(), 120),
                    parSms ? x.telephone() : null, parSms);
            if (parSms) {
                notifications.planifierSms("message:" + id + ":" + ligne, x.telephone(), x.langue(), texte);
                sms++;
            }
        }
        audit.enregistrer("MESSAGE_ENVOYE", objet, Map.of("comptes", a.comptes(), "sms", sms));
        return new Envoi(id, a.comptes() + a.smsSeulement(), a.comptes(), sms);
    }

    /** Messages envoyés dans l'établissement, avec le nombre de lecteurs. */
    @Transactional(readOnly = true)
    public List<MessageEnvoye> envoyes() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select m.id, m.objet, m.cibles, u.prenoms || ' ' || u.nom, m.envoye_le, m.sms,
                       count(d.id)::int, count(d.utilisateur_id)::int, count(d.lu_le)::int
                  from message m
                  join utilisateur u on u.id = m.auteur_id
                  left join message_destinataire d on d.tenant_id = m.tenant_id and d.message_id = m.id
                 group by m.id, m.objet, m.cibles, u.prenoms, u.nom, m.envoye_le, m.sms
                 order by m.envoye_le desc
                 limit 200""",
                (r, n) -> new MessageEnvoye(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                        r.getObject(5, OffsetDateTime.class).toInstant(), r.getBoolean(6), r.getInt(7), r.getInt(8),
                        r.getInt(9)));
    }

    @Transactional(readOnly = true)
    public List<DestinataireVue> destinataires(UUID messageId) {
        UtilisateurConnecte.etablissementActif();
        exister(messageId);
        return jdbc.query("""
                select nom, qualite, utilisateur_id is not null, sms, lu_le
                  from message_destinataire where message_id = ? order by nom""",
                (r, n) -> new DestinataireVue(r.getString(1), r.getString(2), r.getBoolean(3), r.getBoolean(4),
                        instant(r.getObject(5, OffsetDateTime.class))),
                messageId);
    }

    /** Annuaire pour choisir des personnes : personnel actif et parents des élèves de l'année en cours. */
    @Transactional(readOnly = true)
    public List<Personne> annuaire() {
        UtilisateurConnecte.etablissementActif();
        List<Personne> r = new ArrayList<>();
        Map<UUID, List<MembreVue>> personnel = membres.lister().stream()
                .filter(MembreVue::actif)
                .filter(m -> m.role() != Role.PARENT && m.role() != Role.ELEVE)
                .collect(Collectors.groupingBy(MembreVue::utilisateurId, LinkedHashMap::new, Collectors.toList()));
        personnel.forEach((id, roles) -> {
            MembreVue m = roles.get(0);
            boolean enseignant = roles.stream().anyMatch(x -> x.role() == Role.ENSEIGNANT);
            r.add(new Personne(id, null, m.nom() + " " + m.prenoms(), qualite(roles),
                    enseignant ? "ENSEIGNANT" : "ADMINISTRATION"));
        });
        for (Famille f : parentsAnneeActive(List.of())) {
            if (f.utilisateurId() != null && personnel.containsKey(f.utilisateurId())) {
                continue;
            }
            r.add(new Personne(f.utilisateurId(), f.utilisateurId() == null ? f.responsableId() : null,
                    f.nom() + " " + f.prenoms(), "Parent de " + String.join(", ", f.enfants()),
                    f.utilisateurId() == null ? "PARENT_SANS_COMPTE" : "PARENT"));
        }
        r.sort(Comparator.comparing(Personne::categorie).thenComparing(Personne::nom, String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    // ------------------------------------------------------------------ boîte de réception (tout compte)

    @Transactional(readOnly = true)
    public Boite boite(int page, int taille) {
        UtilisateurConnecte.etablissementActif();
        UUID moi = UtilisateurConnecte.id();
        int t = Math.max(1, Math.min(taille, 100));
        List<MessageResume> messages = jdbc.query("""
                select m.id, m.objet, left(m.corps, 160), u.prenoms || ' ' || u.nom, m.envoye_le, d.lu_le is not null
                  from message_destinataire d
                  join message m on m.tenant_id = d.tenant_id and m.id = d.message_id
                  join utilisateur u on u.id = m.auteur_id
                 where d.utilisateur_id = ?
                 order by m.envoye_le desc
                 limit ? offset ?""",
                (r, n) -> new MessageResume(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                        r.getObject(5, OffsetDateTime.class).toInstant(), r.getBoolean(6)),
                moi, t, Math.max(0, page) * t);
        Integer total = jdbc.queryForObject("select count(*) from message_destinataire where utilisateur_id = ?",
                Integer.class, moi);
        return new Boite(messages, total == null ? 0 : total, nonLus());
    }

    @Transactional(readOnly = true)
    public int nonLus() {
        UtilisateurConnecte.etablissementActif();
        Integer n = jdbc.queryForObject(
                "select count(*) from message_destinataire where utilisateur_id = ? and lu_le is null", Integer.class,
                UtilisateurConnecte.id());
        return n == null ? 0 : n;
    }

    /** Lire un message reçu (il passe à « lu ») ; le chef d'établissement lit aussi les messages envoyés. */
    @Transactional
    public MessageVue lire(UUID messageId) {
        UtilisateurConnecte.etablissementActif();
        UUID moi = UtilisateurConnecte.id();
        boolean destinataire = jdbc.update(
                "update message_destinataire set lu_le = coalesce(lu_le, now()) where message_id = ? and utilisateur_id = ?",
                messageId, moi) > 0;
        if (!destinataire && !UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new RessourceIntrouvableException("Message introuvable");
        }
        List<MessageVue> r = jdbc.query("""
                select m.id, m.objet, m.corps, u.prenoms || ' ' || u.nom, m.envoye_le,
                       (select lu_le from message_destinataire d where d.message_id = m.id and d.utilisateur_id = ?), m.sms
                  from message m join utilisateur u on u.id = m.auteur_id
                 where m.id = ?""",
                (x, n) -> new MessageVue(x.getObject(1, UUID.class), x.getString(2), x.getString(3), x.getString(4),
                        x.getObject(5, OffsetDateTime.class).toInstant(), instant(x.getObject(6, OffsetDateTime.class)),
                        x.getBoolean(7)),
                moi, messageId);
        if (r.isEmpty()) {
            throw new RessourceIntrouvableException("Message introuvable");
        }
        return r.get(0);
    }

    // ------------------------------------------------------------------ résolution des destinataires

    private List<Dest> resoudre(Demande d) {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Réservé au chef d'établissement");
        }
        Set<Groupe> groupes = d.groupes() == null || d.groupes().isEmpty() ? EnumSet.noneOf(Groupe.class)
                : EnumSet.copyOf(d.groupes());
        Set<Role> roles = d.roles() == null || d.roles().isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(d.roles());
        if (groupes.contains(Groupe.PERSONNEL) || groupes.contains(Groupe.ENSEIGNANTS)) {
            roles.add(Role.ENSEIGNANT);
        }
        if (groupes.contains(Groupe.PERSONNEL) || groupes.contains(Groupe.ADMINISTRATION)) {
            roles.addAll(ADMINISTRATION);
        }
        roles.remove(Role.PARENT);
        roles.remove(Role.ELEVE);
        Set<UUID> individus = d.utilisateurs() == null ? Set.of() : Set.copyOf(d.utilisateurs());
        Set<UUID> responsables = d.responsables() == null ? Set.of() : Set.copyOf(d.responsables());
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);

        Map<UUID, Dest> parCompte = new LinkedHashMap<>();
        Map<UUID, Dest> parResponsable = new LinkedHashMap<>();
        Map<UUID, List<MembreVue>> membresActifs = membres.lister().stream().filter(MembreVue::actif)
                .filter(m -> m.role() != Role.PARENT && m.role() != Role.ELEVE)
                .collect(Collectors.groupingBy(MembreVue::utilisateurId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<UUID, List<MembreVue>> e : membresActifs.entrySet()) {
            List<MembreVue> sesRoles = e.getValue();
            boolean vise = individus.contains(e.getKey()) || sesRoles.stream().anyMatch(m -> roles.contains(m.role()));
            if (vise && !e.getKey().equals(moi)) {
                MembreVue m = sesRoles.get(0);
                parCompte.put(e.getKey(), new Dest(e.getKey(), null, m.nom() + " " + m.prenoms(), qualite(sesRoles),
                        m.telephone(), "FR"));
            }
        }
        // Parents : tous, ceux de certaines classes, ou choisis un par un
        boolean tousParents = groupes.contains(Groupe.PARENTS);
        List<UUID> classes = d.classes() == null ? List.of() : d.classes();
        List<Famille> liste = new ArrayList<>();
        if (tousParents || !classes.isEmpty()) {
            liste.addAll(parentsAnneeActive(tousParents ? List.of() : classes));
        }
        if (!responsables.isEmpty() || !individus.isEmpty()) {
            parentsAnneeActive(List.of()).stream()
                    .filter(f -> responsables.contains(f.responsableId())
                            || f.utilisateurId() != null && individus.contains(f.utilisateurId()))
                    .forEach(liste::add);
        }
        for (Famille f : liste) {
            String qualite = "Parent de " + String.join(", ", f.enfants());
            if (f.utilisateurId() != null) {
                if (!f.utilisateurId().equals(moi)) {
                    parCompte.putIfAbsent(f.utilisateurId(), new Dest(f.utilisateurId(), f.responsableId(),
                            f.nom() + " " + f.prenoms(), qualite, f.telephone(), f.langueSms()));
                }
            } else {
                parResponsable.putIfAbsent(f.responsableId(), new Dest(null, f.responsableId(),
                        f.nom() + " " + f.prenoms(), qualite, f.telephone(), f.langueSms()));
            }
        }
        List<Dest> r = new ArrayList<>(parCompte.values());
        r.addAll(parResponsable.values());
        return r;
    }

    private List<Famille> parentsAnneeActive(List<UUID> classes) {
        return annees.activeSiExiste().map(AnneeVue::id).map(a -> familles.parents(a, classes)).orElse(List.of());
    }

    private static Apercu compter(List<Dest> dests, boolean sms) {
        int comptes = 0;
        int smsSeulement = 0;
        int injoignables = 0;
        int nbSms = 0;
        for (Dest x : dests) {
            boolean tel = x.telephone() != null && !x.telephone().isBlank();
            if (x.utilisateurId() != null) {
                comptes++;
            } else if (sms && tel) {
                smsSeulement++;
            } else {
                injoignables++;
            }
            if (sms && tel) {
                nbSms++;
            }
        }
        return new Apercu(comptes + smsSeulement, comptes, smsSeulement, injoignables, nbSms);
    }

    /** Résumé lisible des cibles, gardé avec le message. */
    private static String cibles(Demande d) {
        List<String> r = new ArrayList<>();
        if (d.groupes() != null) {
            for (Groupe g : d.groupes()) {
                r.add(switch (g) {
                    case PERSONNEL -> "Tout le personnel";
                    case ENSEIGNANTS -> "Enseignants";
                    case ADMINISTRATION -> "Administration";
                    case PARENTS -> "Tous les parents";
                });
            }
        }
        if (d.roles() != null && !d.roles().isEmpty()) {
            r.add("Rôles : " + d.roles().stream().map(Enum::name).map(String::toLowerCase).collect(Collectors.joining(", ")));
        }
        if (d.classes() != null && !d.classes().isEmpty()) {
            r.add("Parents de " + d.classes().size() + " classe(s)");
        }
        int individus = (d.utilisateurs() == null ? 0 : d.utilisateurs().size())
                + (d.responsables() == null ? 0 : d.responsables().size());
        if (individus > 0) {
            r.add(individus + " personne(s) choisie(s)");
        }
        return tronquer(String.join(" · ", r), 300);
    }

    private static String qualite(List<MembreVue> roles) {
        return roles.stream().map(m -> switch (m.role()) {
            case ADMIN_ECOLE -> "Chef d'établissement";
            case CENSEUR -> "Censeur";
            case CHEF_TRAVAUX -> "Chef des travaux";
            case SECRETARIAT -> "Secrétariat";
            case INTENDANT -> "Intendant";
            case SURVEILLANT -> "Surveillant";
            case ENSEIGNANT -> "Enseignant";
            default -> m.role().name().toLowerCase();
        }).distinct().collect(Collectors.joining(", "));
    }

    private void exister(UUID messageId) {
        Integer n = jdbc.queryForObject("select count(*) from message where id = ?", Integer.class, messageId);
        if (n == null || n == 0) {
            throw new RessourceIntrouvableException("Message introuvable");
        }
    }

    private static String tronquer(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
