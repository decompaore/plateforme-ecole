package bf.edutech.plateforme.secretariat;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.secretariat.Secretariat.Fichier;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Registre du courrier : arrivée (numéro A-2026-0001) et départ (D-2026-0001), numérotés par année civile. Le
 * secrétariat enregistre, joint la pièce scannée, transmet au service concerné et suit le traitement jusqu'au
 * classement ; le service destinataire voit son courrier et le marque traité.
 */
@Service
public class CourrierService {

    public enum Sens { ARRIVEE, DEPART }

    public enum Categorie {
        DIRECTION_PROVINCIALE, DIRECTION_REGIONALE, MINISTERE, PARENTS, PERSONNEL, PARTENAIRE, NOTE_SERVICE, AUTRE
    }

    public enum Statut { ENREGISTRE, TRANSMIS, TRAITE, CLASSE }

    public record Saisie(String sens, LocalDate dateCourrier, LocalDate dateDocument, String reference,
            String correspondant, String categorie, String objet, String service, String cote, UUID reponseA) {
    }

    public record CourrierVue(UUID id, Sens sens, String numero, LocalDate dateCourrier, LocalDate dateDocument,
            String reference, String correspondant, Categorie categorie, String objet, String service,
            Statut statut, String suivi, String cote, UUID reponseA, String reponseANumero, String fichierNom,
            String enregistrePar, Instant enregistreLe) {
    }

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final Compteurs compteurs;
    private final AuditService audit;
    private final Clock horloge;

    CourrierService(JdbcTemplate jdbc, Compteurs compteurs, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.compteurs = compteurs;
        this.audit = audit;
        this.horloge = horloge;
    }

    /**
     * Courrier enregistré, le plus récent d'abord. Le secrétariat et le chef d'établissement voient tout ; un autre
     * service ne voit que le courrier arrivé qui lui est transmis.
     */
    @Transactional(readOnly = true)
    public List<CourrierVue> lister(String sens, Integer annee, String recherche) {
        UtilisateurConnecte.etablissementActif();
        StringBuilder sql = new StringBuilder(SELECT + " where true");
        List<Object> params = new ArrayList<>();
        if (!Secretariat.gestionnaire()) {
            Secretariat.Service s = Secretariat.Service.duConnecte();
            if (s == null) {
                throw new AccesRefuseException("Courrier réservé au secrétariat et aux services destinataires");
            }
            sql.append(" and c.sens = 'ARRIVEE' and c.service = ? and c.statut <> 'ENREGISTRE'");
            params.add(s.name());
        } else if (sens != null && !sens.isBlank()) {
            sql.append(" and c.sens = ?");
            params.add(Secretariat.valeur(Sens.class, sens, "Sens").name());
        }
        if (annee != null) {
            sql.append(" and extract(year from c.date_courrier) = ?");
            params.add(annee);
        }
        if (recherche != null && !recherche.isBlank()) {
            sql.append(" and (c.numero ilike ? or c.objet ilike ? or c.correspondant ilike ? or c.reference ilike ?)");
            String m = "%" + recherche.strip().replace("%", "\\%") + "%";
            params.add(m);
            params.add(m);
            params.add(m);
            params.add(m);
        }
        sql.append(" order by c.date_courrier desc, c.numero desc limit 500");
        return jdbc.query(sql.toString(), VUE, params.toArray());
    }

    @Transactional(readOnly = true)
    public CourrierVue trouver(UUID id) {
        UtilisateurConnecte.etablissementActif();
        CourrierVue c = charger(id);
        exigerLecture(c);
        return c;
    }

    @Transactional
    public CourrierVue enregistrer(Saisie s) {
        Secretariat.exigerGestionnaire();
        if (s == null) {
            throw new IllegalArgumentException("Courrier vide");
        }
        Sens sens = Secretariat.valeur(Sens.class, s.sens(), "Sens (ARRIVEE ou DEPART)");
        LocalDate aujourdhui = LocalDate.now(horloge);
        LocalDate date = s.dateCourrier() != null ? s.dateCourrier() : aujourdhui;
        if (date.isAfter(aujourdhui)) {
            throw new IllegalArgumentException("La date d'enregistrement ne peut pas être dans le futur");
        }
        String numero = (sens == Sens.ARRIVEE ? "A" : "D") + "-" + date.getYear() + "-"
                + String.format("%04d", compteurs.suivant("courrier:" + sens.name() + ":" + date.getYear()));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into courrier (tenant_id, id, sens, numero, date_courrier, date_document, reference, correspondant,
                                      categorie, objet, service, cote, reponse_a, enregistre_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, sens.name(), numero, Date.valueOf(date), s.dateDocument() == null ? null : Date.valueOf(s.dateDocument()),
                Secretariat.facultatif(s.reference(), "La référence", 80),
                Secretariat.obligatoire(s.correspondant(), sens == Sens.ARRIVEE ? "L'expéditeur" : "Le destinataire", 200),
                Secretariat.valeur(Categorie.class, s.categorie(), "Catégorie").name(),
                Secretariat.obligatoire(s.objet(), "L'objet", 300), service(s.service()),
                Secretariat.facultatif(s.cote(), "La cote", 40), reponse(s.reponseA(), sens),
                UtilisateurConnecte.idSiConnecte().orElse(null));
        audit.enregistrer("COURRIER_ENREGISTRE", numero, Map.of("sens", sens.name()));
        return charger(id);
    }

    /** Correction d'un enregistrement (le numéro et le sens ne changent pas). */
    @Transactional
    public CourrierVue modifier(UUID id, Saisie s) {
        Secretariat.exigerGestionnaire();
        CourrierVue c = charger(id);
        LocalDate date = s.dateCourrier() != null ? s.dateCourrier() : c.dateCourrier();
        if (date.getYear() != c.dateCourrier().getYear()) {
            throw new RegleMetierException("COURRIER_AUTRE_ANNEE", "Le numéro " + c.numero()
                    + " appartient au registre " + c.dateCourrier().getYear() + " : la date reste dans cette année");
        }
        jdbc.update("""
                update courrier set date_courrier = ?, date_document = ?, reference = ?, correspondant = ?, categorie = ?,
                       objet = ?, service = ?, cote = ?, reponse_a = ?, modifie_le = now()
                 where id = ?""",
                Date.valueOf(date), s.dateDocument() == null ? null : Date.valueOf(s.dateDocument()),
                Secretariat.facultatif(s.reference(), "La référence", 80),
                Secretariat.obligatoire(s.correspondant(), "Le correspondant", 200),
                Secretariat.valeur(Categorie.class, s.categorie(), "Catégorie").name(),
                Secretariat.obligatoire(s.objet(), "L'objet", 300), service(s.service()),
                Secretariat.facultatif(s.cote(), "La cote", 40), reponse(s.reponseA(), c.sens()), id);
        return charger(id);
    }

    /**
     * Suivi : transmis au service (TRANSMIS), traité, classé. Le service destinataire peut seulement marquer
     * « traité » le courrier qui lui est transmis.
     */
    @Transactional
    public CourrierVue suivre(UUID id, String statut, String service, String suivi) {
        UtilisateurConnecte.etablissementActif();
        CourrierVue c = charger(id);
        Statut nouveau = Secretariat.valeur(Statut.class, statut, "Statut");
        String note = Secretariat.facultatif(suivi, "Le suivi", 500);
        if (!Secretariat.gestionnaire()) {
            Secretariat.Service s = Secretariat.Service.duConnecte();
            if (s == null || !s.name().equals(c.service()) || nouveau != Statut.TRAITE || c.statut() != Statut.TRANSMIS) {
                throw new AccesRefuseException("Le service destinataire marque seulement « traité » le courrier transmis");
            }
        }
        String svc = c.service();
        if (nouveau == Statut.TRANSMIS) {
            svc = service(service != null ? service : c.service());
            if (svc == null) {
                throw new IllegalArgumentException("Indiquez le service auquel le courrier est transmis");
            }
        }
        jdbc.update("update courrier set statut = ?, service = ?, suivi = coalesce(?, suivi), modifie_le = now() where id = ?",
                nouveau.name(), svc, note, id);
        audit.enregistrer("COURRIER_SUIVI", c.numero(), Map.of("statut", nouveau.name()));
        return charger(id);
    }

    @Transactional
    public CourrierVue joindre(UUID id, byte[] contenu, String nom) {
        Secretariat.exigerGestionnaire();
        charger(id);
        Fichier f = Secretariat.fichier(contenu, nom);
        jdbc.update("update courrier set fichier = ?, fichier_type = ?, fichier_nom = ?, modifie_le = now() where id = ?",
                f.contenu(), f.type(), f.nom(), id);
        return charger(id);
    }

    @Transactional(readOnly = true)
    public Fichier fichier(UUID id) {
        UtilisateurConnecte.etablissementActif();
        exigerLecture(charger(id));
        return jdbc.query("select fichier, fichier_type, fichier_nom from courrier where id = ? and fichier is not null",
                (r, n) -> new Fichier(r.getBytes(1), r.getString(2), r.getString(3)), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune pièce jointe à ce courrier"));
    }

    /** Registre imprimable de l'année : arrivée puis départ. */
    @Transactional(readOnly = true)
    public Tableau[] registre(int annee) {
        Secretariat.exigerGestionnaire();
        List<Tableau> t = new ArrayList<>();
        for (Sens sens : Sens.values()) {
            boolean arrivee = sens == Sens.ARRIVEE;
            Tableau r = new Tableau(arrivee ? "Arrivée" : "Départ",
                    "Registre du courrier " + (arrivee ? "arrivé" : "départ") + " — " + annee)
                    .colonnes(Colonne.texte("N°", 0.9f), Colonne.texte("Date", 0.8f), Colonne.texte("Réf.", 0.9f),
                            Colonne.texte(arrivee ? "Expéditeur" : "Destinataire", 1.6f), Colonne.texte("Objet", 2.6f),
                            Colonne.texte(arrivee ? "Service" : "Catégorie", 1f), Colonne.texte("Suite", 1.3f));
            for (CourrierVue c : jdbc.query(SELECT + " where c.sens = ? and extract(year from c.date_courrier) = ? "
                    + "order by c.numero", VUE, sens.name(), annee)) {
                r.ligne(c.numero(), c.dateCourrier().format(JOUR), c.reference() == null ? "" : c.reference(),
                        c.correspondant(), c.objet(),
                        arrivee ? (c.service() == null ? "" : Secretariat.Service.valueOf(c.service()).libelle)
                                : c.categorie().name().replace('_', ' ').toLowerCase(),
                        c.statut().name().toLowerCase() + (c.suivi() == null ? "" : " : " + c.suivi()));
            }
            t.add(r);
        }
        return t.toArray(new Tableau[0]);
    }

    // ------------------------------------------------------------------

    /** Courrier de départ enregistré par un autre service du module (transmission d'une demande…). */
    UUID depart(String correspondant, Categorie categorie, String objet, String reference) {
        CourrierVue c = enregistrer(new Saisie("DEPART", null, null, reference, correspondant, categorie.name(), objet,
                null, null, null));
        return c.id();
    }

    CourrierVue charger(UUID id) {
        return jdbc.query(SELECT + " where c.id = ?", VUE, id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Courrier introuvable"));
    }

    private void exigerLecture(CourrierVue c) {
        if (Secretariat.gestionnaire()) {
            return;
        }
        Secretariat.Service s = Secretariat.Service.duConnecte();
        if (s == null || c.sens() != Sens.ARRIVEE || !s.name().equals(c.service()) || c.statut() == Statut.ENREGISTRE) {
            throw new AccesRefuseException("Ce courrier n'est pas transmis à votre service");
        }
    }

    private static String service(String s) {
        return s == null || s.isBlank() ? null : Secretariat.valeur(Secretariat.Service.class, s, "Service").name();
    }

    private UUID reponse(UUID reponseA, Sens sens) {
        if (reponseA == null) {
            return null;
        }
        CourrierVue r = charger(reponseA);
        if (sens != Sens.DEPART || r.sens() != Sens.ARRIVEE) {
            throw new IllegalArgumentException("Un courrier de départ répond à un courrier arrivé");
        }
        return reponseA;
    }

    private static final String SELECT = """
            select c.id, c.sens, c.numero, c.date_courrier, c.date_document, c.reference, c.correspondant, c.categorie,
                   c.objet, c.service, c.statut, c.suivi, c.cote, c.reponse_a, r.numero, c.fichier_nom,
                   u.nom || ' ' || u.prenoms, c.enregistre_le
              from courrier c
              left join courrier r on r.tenant_id = c.tenant_id and r.id = c.reponse_a
              left join utilisateur u on u.id = c.enregistre_par""";

    private static final RowMapper<CourrierVue> VUE = (r, n) -> new CourrierVue(r.getObject(1, UUID.class),
            Sens.valueOf(r.getString(2)), r.getString(3), r.getDate(4).toLocalDate(),
            r.getDate(5) == null ? null : r.getDate(5).toLocalDate(), r.getString(6), r.getString(7),
            Categorie.valueOf(r.getString(8)), r.getString(9), r.getString(10), Statut.valueOf(r.getString(11)),
            r.getString(12), r.getString(13), r.getObject(14, UUID.class), r.getString(15), r.getString(16),
            r.getString(17), r.getObject(18, OffsetDateTime.class).toInstant());
}
