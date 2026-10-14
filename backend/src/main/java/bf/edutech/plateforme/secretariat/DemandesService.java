package bf.edutech.plateforme.secretariat;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;

/**
 * Actes administratifs du personnel recueillis par le secrétariat : demandes de mutation, d'affectation,
 * d'autorisation d'absence, de congé, de disponibilité, d'attestation de service…
 * <pre>
 *   RECUE → TRANSMISE (au chef) → AVIS_FAVORABLE | AVIS_DEFAVORABLE → TRANSMISE_HIERARCHIE (courrier départ) → CLOTUREE
 * </pre>
 * Le chef d'établissement seul donne l'avis ; la transmission à la hiérarchie enregistre le courrier de départ. Le
 * demandeur suit ses demandes dans « Mes demandes ».
 */
@Service
public class DemandesService {

    public enum Type {
        MUTATION("Demande de mutation"), AFFECTATION("Demande d'affectation"),
        AUTORISATION_ABSENCE("Demande d'autorisation d'absence"), CONGE("Demande de congé"),
        DISPONIBILITE("Demande de mise en disponibilité"), ATTESTATION_SERVICE("Demande d'attestation de service"),
        AUTRE("Demande");

        final String libelle;

        Type(String libelle) {
            this.libelle = libelle;
        }
    }

    public enum Statut { RECUE, TRANSMISE, AVIS_FAVORABLE, AVIS_DEFAVORABLE, TRANSMISE_HIERARCHIE, CLOTUREE }

    public record Saisie(String type, UUID demandeurId, String demandeur, LocalDate recueLe, String objet,
            LocalDate du, LocalDate au) {
    }

    public record DemandeVue(UUID id, String numero, Type type, String typeLibelle, UUID demandeurId,
            String demandeur, LocalDate recueLe, String objet, LocalDate du, LocalDate au, Statut statut,
            String avis, String avisPar, Instant avisLe, String suite, UUID courrierId, String courrierNumero,
            String fichierNom) {
    }

    private final JdbcTemplate jdbc;
    private final Compteurs compteurs;
    private final MembresService membres;
    private final CourrierService courrier;
    private final AuditService audit;
    private final Clock horloge;

    DemandesService(JdbcTemplate jdbc, Compteurs compteurs, MembresService membres, CourrierService courrier,
            AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.compteurs = compteurs;
        this.membres = membres;
        this.courrier = courrier;
        this.audit = audit;
        this.horloge = horloge;
    }

    /** Toutes les demandes (secrétariat, chef d'établissement), les plus récentes d'abord. */
    @Transactional(readOnly = true)
    public List<DemandeVue> lister(String statut) {
        Secretariat.exigerGestionnaire();
        String s = statut == null || statut.isBlank() ? null : Secretariat.valeur(Statut.class, statut, "Statut").name();
        return jdbc.query(SELECT + " where (cast(? as varchar) is null or d.statut = ?) order by d.recue_le desc, d.numero desc"
                + " limit 500", VUE, s, s);
    }

    /** Demandes du compte connecté (enseignant, personnel). */
    @Transactional(readOnly = true)
    public List<DemandeVue> miennes() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query(SELECT + " where d.demandeur_id = ? order by d.recue_le desc", VUE, UtilisateurConnecte.id());
    }

    @Transactional
    public DemandeVue recevoir(Saisie s) {
        Secretariat.exigerGestionnaire();
        if (s == null) {
            throw new IllegalArgumentException("Demande vide");
        }
        Type type = Secretariat.valeur(Type.class, s.type(), "Type de demande");
        LocalDate aujourdhui = LocalDate.now(horloge);
        LocalDate recue = s.recueLe() != null ? s.recueLe() : aujourdhui;
        if (recue.isAfter(aujourdhui)) {
            throw new IllegalArgumentException("La date de réception ne peut pas être dans le futur");
        }
        String demandeur = demandeur(s.demandeurId(), s.demandeur());
        if (s.au() != null && (s.du() == null || s.au().isBefore(s.du()))) {
            throw new IllegalArgumentException("La période demandée : « du » avant « au »");
        }
        if (type == Type.AUTORISATION_ABSENCE && s.du() == null) {
            throw new IllegalArgumentException("Indiquez la période d'absence demandée");
        }
        String numero = "R-" + recue.getYear() + "-" + String.format("%04d", compteurs.suivant("demande:" + recue.getYear()));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into demande_administrative (tenant_id, id, numero, type, demandeur_id, demandeur, recue_le, objet,
                                                    du, au, saisie_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, numero, type.name(), s.demandeurId(), demandeur, Date.valueOf(recue),
                Secretariat.obligatoire(s.objet(), "L'objet de la demande", 300),
                s.du() == null ? null : Date.valueOf(s.du()), s.au() == null ? null : Date.valueOf(s.au()),
                UtilisateurConnecte.idSiConnecte().orElse(null));
        audit.enregistrer("DEMANDE_RECUE", numero, Map.of("type", type.name()));
        return charger(id);
    }

    /** Le secrétariat transmet la demande au chef d'établissement pour avis. */
    @Transactional
    public DemandeVue transmettre(UUID id) {
        Secretariat.exigerGestionnaire();
        DemandeVue d = charger(id);
        exigerStatut(d, Statut.RECUE);
        changer(id, Statut.TRANSMISE);
        return charger(id);
    }

    /** Avis du chef d'établissement (favorable ou défavorable), motivé. */
    @Transactional
    public DemandeVue aviser(UUID id, Boolean favorable, String avis) {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Seul le chef d'établissement donne son avis");
        }
        DemandeVue d = charger(id);
        if (d.statut() != Statut.RECUE && d.statut() != Statut.TRANSMISE) {
            throw new RegleMetierException("AVIS_DEJA_DONNE", "L'avis sur la demande " + d.numero() + " est déjà donné");
        }
        if (favorable == null) {
            throw new IllegalArgumentException("Avis favorable ou défavorable ?");
        }
        String texte = favorable ? Secretariat.facultatif(avis, "L'avis", 500)
                : Secretariat.obligatoire(avis, "Le motif d'un avis défavorable", 500);
        jdbc.update("""
                update demande_administrative set statut = ?, avis = ?, avis_par = ?, avis_le = ? where id = ?""",
                favorable ? Statut.AVIS_FAVORABLE.name() : Statut.AVIS_DEFAVORABLE.name(), texte,
                UtilisateurConnecte.id(), java.sql.Timestamp.from(horloge.instant()), id);
        audit.enregistrer("DEMANDE_AVIS", d.numero(), Map.of("favorable", favorable));
        return charger(id);
    }

    /**
     * Transmission à la hiérarchie (direction provinciale, régionale) après l'avis : le courrier de départ est
     * enregistré au registre et lié à la demande.
     */
    @Transactional
    public DemandeVue transmettreHierarchie(UUID id, String destinataire, String categorie) {
        Secretariat.exigerGestionnaire();
        DemandeVue d = charger(id);
        if (d.statut() != Statut.AVIS_FAVORABLE && d.statut() != Statut.AVIS_DEFAVORABLE) {
            throw new RegleMetierException("AVIS_ATTENDU", "La demande " + d.numero()
                    + " part avec l'avis du chef d'établissement : donnez-le d'abord");
        }
        CourrierService.Categorie c = categorie == null || categorie.isBlank()
                ? CourrierService.Categorie.DIRECTION_PROVINCIALE
                : Secretariat.valeur(CourrierService.Categorie.class, categorie, "Catégorie");
        UUID depart = courrier.depart(Secretariat.obligatoire(destinataire, "Le destinataire", 200), c,
                "Transmission : " + d.typeLibelle().toLowerCase() + " de " + d.demandeur()
                        + " (avis " + (d.statut() == Statut.AVIS_FAVORABLE ? "favorable" : "défavorable") + ")",
                d.numero());
        jdbc.update("update demande_administrative set statut = 'TRANSMISE_HIERARCHIE', courrier_id = ? where id = ?",
                depart, id);
        return charger(id);
    }

    /** Clôture avec la suite donnée (décision reçue, autorisation accordée…). */
    @Transactional
    public DemandeVue cloturer(UUID id, String suite) {
        Secretariat.exigerGestionnaire();
        DemandeVue d = charger(id);
        if (d.statut() == Statut.RECUE || d.statut() == Statut.TRANSMISE) {
            throw new RegleMetierException("AVIS_ATTENDU", "La demande " + d.numero() + " attend l'avis du chef d'établissement");
        }
        if (d.statut() == Statut.CLOTUREE) {
            throw new RegleMetierException("DEMANDE_CLOTUREE", "La demande " + d.numero() + " est déjà close");
        }
        jdbc.update("update demande_administrative set statut = 'CLOTUREE', suite = ? where id = ?",
                Secretariat.obligatoire(suite, "La suite donnée", 500), id);
        return charger(id);
    }

    @Transactional
    public DemandeVue joindre(UUID id, byte[] contenu, String nom) {
        Secretariat.exigerGestionnaire();
        charger(id);
        Fichier f = Secretariat.fichier(contenu, nom);
        jdbc.update("update demande_administrative set fichier = ?, fichier_type = ?, fichier_nom = ? where id = ?",
                f.contenu(), f.type(), f.nom(), id);
        return charger(id);
    }

    @Transactional(readOnly = true)
    public Fichier fichier(UUID id) {
        Secretariat.exigerGestionnaire();
        return jdbc.query("select fichier, fichier_type, fichier_nom from demande_administrative where id = ? and fichier is not null",
                (r, n) -> new Fichier(r.getBytes(1), r.getString(2), r.getString(3)), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune pièce jointe à cette demande"));
    }

    // ------------------------------------------------------------------

    private String demandeur(UUID demandeurId, String nom) {
        if (demandeurId == null) {
            return Secretariat.obligatoire(nom, "Le demandeur", 200);
        }
        return membres.lister().stream().filter(m -> m.utilisateurId().equals(demandeurId)).findFirst()
                .map(m -> m.nom() + " " + m.prenoms())
                .orElseThrow(() -> new IllegalArgumentException("Le demandeur n'est pas membre de l'établissement"));
    }

    private void changer(UUID id, Statut s) {
        jdbc.update("update demande_administrative set statut = ? where id = ?", s.name(), id);
    }

    private static void exigerStatut(DemandeVue d, Statut attendu) {
        if (d.statut() != attendu) {
            throw new RegleMetierException("STATUT_DEMANDE", "La demande " + d.numero() + " est déjà "
                    + d.statut().name().toLowerCase().replace('_', ' '));
        }
    }

    private DemandeVue charger(UUID id) {
        return jdbc.query(SELECT + " where d.id = ?", VUE, id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Demande introuvable"));
    }

    private static final String SELECT = """
            select d.id, d.numero, d.type, d.demandeur_id, d.demandeur, d.recue_le, d.objet, d.du, d.au, d.statut,
                   d.avis, u.prenoms || ' ' || u.nom, d.avis_le, d.suite, d.courrier_id, c.numero, d.fichier_nom
              from demande_administrative d
              left join utilisateur u on u.id = d.avis_par
              left join courrier c on c.tenant_id = d.tenant_id and c.id = d.courrier_id""";

    private static final RowMapper<DemandeVue> VUE = (r, n) -> {
        Type t = Type.valueOf(r.getString(3));
        return new DemandeVue(r.getObject(1, UUID.class), r.getString(2), t, t.libelle, r.getObject(4, UUID.class),
                r.getString(5), r.getDate(6).toLocalDate(), r.getString(7),
                r.getDate(8) == null ? null : r.getDate(8).toLocalDate(),
                r.getDate(9) == null ? null : r.getDate(9).toLocalDate(), Statut.valueOf(r.getString(10)),
                r.getString(11), r.getString(12),
                r.getObject(13, OffsetDateTime.class) == null ? null : r.getObject(13, OffsetDateTime.class).toInstant(),
                r.getString(14), r.getObject(15, UUID.class), r.getString(16), r.getString(17));
    };
}
