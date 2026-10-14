package bf.edutech.plateforme.secretariat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.messagerie.MessagerieService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;

/**
 * Registre de l'accueil : visites (parents, visiteurs, partenaires, autorités) orientées vers le service compétent,
 * et appels reçus à la permanence téléphonique. Un message pour une personne du personnel lui est transmis dans sa
 * messagerie (« URGENT » en tête de l'objet si besoin).
 */
@Service
public class AccueilService {

    public enum Canal { VISITE, APPEL }

    public enum Qualite { PARENT, VISITEUR, PARTENAIRE, AUTORITE, PERSONNEL, AUTRE }

    public record Saisie(String canal, String nom, String telephone, String qualite, String objet, String service,
            UUID personneId, String message, Boolean urgent, Boolean transmettre) {
    }

    public record PassageVue(UUID id, Canal canal, Instant arrivee, Instant depart, String nom, String telephone,
            Qualite qualite, String objet, String service, UUID personneId, String personne, String message,
            boolean urgent, Instant transmisLe) {
    }

    /** Membre du personnel (pour orienter un visiteur, transmettre un message, rattacher une demande). */
    public record PersonneVue(UUID utilisateurId, String nom, String prenoms, String fonctions) {
    }

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm");

    private final JdbcTemplate jdbc;
    private final MembresService membres;
    private final MessagerieService messagerie;
    private final Clock horloge;

    AccueilService(JdbcTemplate jdbc, MembresService membres, MessagerieService messagerie, Clock horloge) {
        this.jdbc = jdbc;
        this.membres = membres;
        this.messagerie = messagerie;
        this.horloge = horloge;
    }

    /** Personnel actif de l'établissement, un par personne, avec ses fonctions. */
    @Transactional(readOnly = true)
    public List<PersonneVue> personnel() {
        Secretariat.exigerGestionnaire();
        java.util.Map<UUID, List<bf.edutech.plateforme.utilisateurs.MembreVue>> parPersonne = membres.lister().stream()
                .filter(m -> m.actif() && m.role() != bf.edutech.plateforme.utilisateurs.Role.PARENT
                        && m.role() != bf.edutech.plateforme.utilisateurs.Role.ELEVE)
                .collect(java.util.stream.Collectors.groupingBy(bf.edutech.plateforme.utilisateurs.MembreVue::utilisateurId,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return parPersonne.values().stream()
                .map(l -> new PersonneVue(l.get(0).utilisateurId(), l.get(0).nom(), l.get(0).prenoms(),
                        String.join(", ", l.stream().map(m -> fonction(m.role())).distinct().toList())))
                .sorted(java.util.Comparator.comparing(PersonneVue::nom).thenComparing(PersonneVue::prenoms))
                .toList();
    }

    private static String fonction(bf.edutech.plateforme.utilisateurs.Role r) {
        return switch (r) {
            case ADMIN_ECOLE -> "Chef d'établissement";
            case CENSEUR -> "Censeur";
            case CHEF_TRAVAUX -> "Chef des travaux";
            case SECRETARIAT -> "Secrétariat";
            case INTENDANT -> "Intendant";
            case SURVEILLANT -> "CPE";
            case ENSEIGNANT -> "Enseignant";
            default -> r.name();
        };
    }

    /** Registre d'une journée (aujourd'hui par défaut), le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<PassageVue> registre(LocalDate jour) {
        Secretariat.exigerGestionnaire();
        LocalDate j = jour != null ? jour : LocalDate.now(horloge.withZone(FUSEAU));
        return jdbc.query(SELECT + " where a.arrivee >= ? and a.arrivee < ? order by a.arrivee desc", VUE,
                Timestamp.from(j.atStartOfDay(FUSEAU).toInstant()), Timestamp.from(j.plusDays(1).atStartOfDay(FUSEAU).toInstant()));
    }

    @Transactional
    public PassageVue enregistrer(Saisie s) {
        Secretariat.exigerGestionnaire();
        if (s == null) {
            throw new IllegalArgumentException("Passage vide");
        }
        Canal canal = Secretariat.valeur(Canal.class, s.canal(), "Canal (VISITE ou APPEL)");
        String telephone = Secretariat.facultatif(s.telephone(), "Le téléphone", 20);
        String personne = null;
        if (s.personneId() != null) {
            personne = membres.lister().stream().filter(m -> m.actif() && m.utilisateurId().equals(s.personneId()))
                    .findFirst().map(m -> m.nom() + " " + m.prenoms())
                    .orElseThrow(() -> new IllegalArgumentException("Cette personne n'est pas membre de l'établissement"));
        }
        String message = Secretariat.facultatif(s.message(), "Le message", 1000);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into registre_accueil (tenant_id, id, canal, arrivee, nom, telephone, qualite, objet, service,
                                              personne_id, personne, message, urgent, saisi_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, canal.name(), Timestamp.from(horloge.instant()), Secretariat.obligatoire(s.nom(), "Le nom", 150),
                telephone, Secretariat.valeur(Qualite.class, s.qualite() == null ? "AUTRE" : s.qualite(), "Qualité").name(),
                Secretariat.obligatoire(s.objet(), "L'objet", 300),
                s.service() == null || s.service().isBlank() ? null
                        : Secretariat.valeur(Secretariat.Service.class, s.service(), "Service").name(),
                s.personneId(), personne, message, Boolean.TRUE.equals(s.urgent()),
                UtilisateurConnecte.idSiConnecte().orElse(null));
        if (Boolean.TRUE.equals(s.transmettre())) {
            return transmettre(id);
        }
        return charger(id);
    }

    /** Transmet le passage (message laissé) à la personne visée, dans sa messagerie. */
    @Transactional
    public PassageVue transmettre(UUID id) {
        Secretariat.exigerGestionnaire();
        PassageVue p = charger(id);
        if (p.personneId() == null) {
            throw new RegleMetierException("SANS_DESTINATAIRE", "Indiquez la personne à qui transmettre le message");
        }
        String quand = p.arrivee().atZone(FUSEAU).format(HEURE);
        String objet = (p.urgent() ? "URGENT : " : "") + (p.canal() == Canal.APPEL ? "Appel de " : "Visite de ") + p.nom();
        String corps = (p.canal() == Canal.APPEL ? "Appel reçu le " : "Passage le ") + quand + ".\n"
                + "Objet : " + p.objet() + "\n"
                + (p.telephone() != null ? "Téléphone : " + p.telephone() + "\n" : "")
                + (p.message() != null ? "Message : " + p.message() : "");
        messagerie.transmettre(p.personneId(), objet, corps, "Transmis par le secrétariat (accueil)");
        jdbc.update("update registre_accueil set transmis_le = now() where id = ?", id);
        return charger(id);
    }

    /** Heure de départ d'un visiteur. */
    @Transactional
    public PassageVue depart(UUID id) {
        Secretariat.exigerGestionnaire();
        PassageVue p = charger(id);
        if (p.canal() != Canal.VISITE) {
            throw new IllegalArgumentException("Seule une visite a une heure de départ");
        }
        jdbc.update("update registre_accueil set depart = ? where id = ? and depart is null",
                Timestamp.from(horloge.instant()), id);
        return charger(id);
    }

    private PassageVue charger(UUID id) {
        return jdbc.query(SELECT + " where a.id = ?", VUE, id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Passage introuvable"));
    }

    private static final String SELECT = """
            select a.id, a.canal, a.arrivee, a.depart, a.nom, a.telephone, a.qualite, a.objet, a.service, a.personne_id,
                   a.personne, a.message, a.urgent, a.transmis_le
              from registre_accueil a""";

    private static final RowMapper<PassageVue> VUE = (r, n) -> new PassageVue(r.getObject(1, UUID.class),
            Canal.valueOf(r.getString(2)), r.getObject(3, OffsetDateTime.class).toInstant(),
            r.getObject(4, OffsetDateTime.class) == null ? null : r.getObject(4, OffsetDateTime.class).toInstant(),
            r.getString(5), r.getString(6), Qualite.valueOf(r.getString(7)), r.getString(8), r.getString(9),
            r.getObject(10, UUID.class), r.getString(11), r.getString(12), r.getBoolean(13),
            r.getObject(14, OffsetDateTime.class) == null ? null : r.getObject(14, OffsetDateTime.class).toInstant());
}
