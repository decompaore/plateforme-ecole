package bf.edutech.plateforme.scolarite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.scolarite.EncaissementsService.DonneesPaiement;
import bf.edutech.plateforme.scolarite.Vues.PaiementVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Virements bancaires déclarés par les parents (v0.38).
 * <ol>
 * <li>Le parent fait le virement depuis sa banque vers un compte de l'établissement, puis le
 * déclare : compte, montant, date, référence de l'opération, donneur d'ordre et, s'il l'a, une
 * preuve (photo ou PDF de l'avis de virement). Statut EN_ATTENTE.</li>
 * <li>L'intendance le retrouve sur le relevé du compte et le valide avec le montant réellement
 * reçu : le paiement est enregistré (moyen VIREMENT) avec son reçu, envoyé par SMS au parent.</li>
 * <li>Sinon elle le refuse avec un motif, transmis au parent par SMS.</li>
 * </ol>
 * Un même virement (compte et référence) ne peut être déclaré qu'une fois tant qu'il n'est pas
 * refusé ; les déclarations en attente comptent dans le reste à payer qu'un parent peut déclarer.
 */
@Service
public class VirementsService {

    static final long PREUVE_MAX = 2L * 1024 * 1024;
    private static final long MONTANT_MAX = 100_000_000L;

    public enum StatutVirement {
        EN_ATTENTE,
        VALIDE,
        REFUSE
    }

    public record DonneesVirement(UUID compteId, Long montant, LocalDate dateVirement, String reference,
            String donneurOrdre, String cleIdempotence) {
    }

    /** Pièce jointe : contenu et type MIME. */
    public record Preuve(byte[] contenu, String type) {
    }

    public record VirementVue(UUID id, UUID inscriptionId, String eleve, String matricule, String classe,
            UUID compteId, String compte, long montant, LocalDate dateVirement, String reference, String donneurOrdre,
            boolean preuve, StatutVirement statut, Long montantValide, String motifRefus, UUID paiementId,
            String recuNumero, Instant declareLe, Instant traiteLe) {
    }

    private final JdbcTemplate jdbc;
    private final PaiementRepository paiements;
    private final MoyensPaiementService moyens;
    private final EncaissementsService encaissements;
    private final SituationsService situations;
    private final InscriptionsService inscriptions;
    private final EspaceParentService espaceParent;
    private final ContactsEleves contacts;
    private final NotificationsService notifications;
    private final Compteurs compteurs;
    private final AuditService audit;
    private final Clock horloge;

    VirementsService(JdbcTemplate jdbc, PaiementRepository paiements, MoyensPaiementService moyens, EncaissementsService encaissements,
            SituationsService situations, InscriptionsService inscriptions, EspaceParentService espaceParent,
            ContactsEleves contacts, NotificationsService notifications, Compteurs compteurs, AuditService audit,
            Clock horloge) {
        this.jdbc = jdbc;
        this.paiements = paiements;
        this.moyens = moyens;
        this.encaissements = encaissements;
        this.situations = situations;
        this.inscriptions = inscriptions;
        this.espaceParent = espaceParent;
        this.contacts = contacts;
        this.notifications = notifications;
        this.compteurs = compteurs;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ parent

    /** Déclaration par le parent ; une même clé renvoie la déclaration déjà enregistrée. */
    @Transactional
    public VirementVue declarer(UUID inscriptionId, DonneesVirement d, Preuve preuve) {
        UtilisateurConnecte.etablissementActif();
        InscriptionVue inscription = monEnfant(inscriptionId);
        String cle = d.cleIdempotence() == null || d.cleIdempotence().isBlank() ? UUID.randomUUID().toString()
                : d.cleIdempotence().trim();
        if (cle.length() > 64) {
            throw new IllegalArgumentException("Clé d'idempotence : 64 caractères au plus");
        }
        compteurs.verrouiller("virement:" + inscriptionId);
        List<UUID> existant = jdbc.queryForList(
                "select id from virement_declare where tenant_id = tenant_courant() and cle_idempotence = ?", UUID.class,
                cle);
        if (!existant.isEmpty()) {
            VirementVue v = trouver(existant.get(0));
            if (!v.inscriptionId().equals(inscriptionId) || d.montant() == null || v.montant() != d.montant()) {
                throw new RegleMetierException("CLE_DEJA_UTILISEE", "Cette clé d'idempotence a déjà servi pour une autre déclaration");
            }
            return v;
        }
        if (d.compteId() == null) {
            throw new IllegalArgumentException("Choisissez le compte de l'établissement crédité");
        }
        moyens.verifierVirementParent(d.compteId());
        if (d.montant() == null || d.montant() <= 0 || d.montant() > MONTANT_MAX) {
            throw new IllegalArgumentException("Le montant est compris entre 1 et 100 000 000");
        }
        LocalDate jour = LocalDate.now(horloge);
        if (d.dateVirement() == null || d.dateVirement().isAfter(jour) || d.dateVirement().isBefore(jour.minusDays(365))) {
            throw new IllegalArgumentException("La date du virement est celle de l'opération : ni future, ni de plus d'un an");
        }
        String reference = d.reference() == null ? "" : d.reference().trim();
        if (reference.isEmpty() || reference.length() > 60) {
            throw new IllegalArgumentException("La référence du virement (figurant sur l'avis de la banque) est obligatoire, "
                    + "60 caractères au plus");
        }
        String donneur = d.donneurOrdre() == null || d.donneurOrdre().isBlank() ? null : d.donneurOrdre().trim();
        if (donneur != null && donneur.length() > 120) {
            throw new IllegalArgumentException("Donneur d'ordre : 120 caractères au plus");
        }
        Preuve p = verifierPreuve(preuve);
        Integer deja = jdbc.queryForObject("""
                select count(*) from virement_declare where tenant_id = tenant_courant() and compte_id = ?
                and upper(reference) = upper(?) and statut <> 'REFUSE'""", Integer.class, d.compteId(), reference);
        if (deja != null && deja > 0) {
            throw new RegleMetierException("VIREMENT_DEJA_DECLARE", "Ce virement (même compte, même référence) est déjà déclaré");
        }
        long reste = situations.situation(inscriptionId).resteFamille();
        Long enAttente = jdbc.queryForObject("""
                select coalesce(sum(montant), 0) from virement_declare where tenant_id = tenant_courant()
                and inscription_id = ? and statut = 'EN_ATTENTE'""", Long.class, inscriptionId);
        long disponible = reste - (enAttente == null ? 0 : enAttente);
        if (disponible <= 0) {
            throw new RegleMetierException("RIEN_A_PAYER", enAttente != null && enAttente > 0
                    ? "Les virements déjà déclarés couvrent le reste à payer : attendez leur vérification par l'intendance"
                    : "Rien n'est dû pour cet élève");
        }
        if (d.montant() > disponible) {
            throw new RegleMetierException("MONTANT_SUPERIEUR_AU_RESTE", "Le montant dépasse le reste à payer ("
                    + Montants.fcfa(disponible) + (enAttente != null && enAttente > 0 ? ", virements en attente déduits)" : ")"));
        }
        UUID id = UUID.randomUUID();
        try {
            inserer(id, inscriptionId, d, reference, donneur, p, cle);
        } catch (DuplicateKeyException e) {
            // Même virement déclaré au même instant pour un autre enfant (index unique compte + référence)
            throw new RegleMetierException("VIREMENT_DEJA_DECLARE", "Ce virement (même compte, même référence) est déjà déclaré");
        }
        audit.enregistrer("VIREMENT_DECLARE", inscription.matricule(), Map.of("montant", d.montant(), "reference", reference));
        return trouver(id);
    }

    private void inserer(UUID id, UUID inscriptionId, DonneesVirement d, String reference, String donneur, Preuve p, String cle) {
        jdbc.update("""
                insert into virement_declare (tenant_id, id, inscription_id, compte_id, montant, date_virement, reference,
                       donneur_ordre, preuve, preuve_type, cle_idempotence, declare_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (select id from utilisateur where id = ?))""",
                id, inscriptionId, d.compteId(), d.montant(), d.dateVirement(), reference, donneur,
                p == null ? null : p.contenu(), p == null ? null : p.type(), cle,
                UtilisateurConnecte.idSiConnecte().orElse(null));
    }

    /** Déclarations du parent pour une inscription de son enfant (les plus récentes d'abord). */
    @Transactional(readOnly = true)
    public List<VirementVue> mesVirements(UUID inscriptionId) {
        UtilisateurConnecte.etablissementActif();
        monEnfant(inscriptionId);
        return jdbc.query(SELECTION + " where v.tenant_id = tenant_courant() and v.inscription_id = ? order by v.declare_le desc",
                (l, n) -> vue(l), inscriptionId);
    }

    // ------------------------------------------------------------------ intendance

    @Transactional(readOnly = true)
    public List<VirementVue> lister(StatutVirement statut) {
        UtilisateurConnecte.etablissementActif();
        if (statut == null) {
            return jdbc.query(SELECTION + " where v.tenant_id = tenant_courant() order by v.declare_le desc limit 500",
                    (l, n) -> vue(l));
        }
        return jdbc.query(SELECTION + " where v.tenant_id = tenant_courant() and v.statut = ? order by v.declare_le "
                + (statut == StatutVirement.EN_ATTENTE ? "asc" : "desc limit 500"), (l, n) -> vue(l), statut.name());
    }

    @Transactional(readOnly = true)
    public Preuve preuve(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select preuve, preuve_type from virement_declare where tenant_id = tenant_courant() and id = ?
                and preuve is not null""", (l, n) -> new Preuve(l.getBytes(1), l.getString(2)), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune preuve jointe à ce virement"));
    }

    /**
     * Valide un virement retrouvé sur le relevé : paiement (moyen VIREMENT) et reçu, avec le montant
     * réellement reçu ({@code montantRecu}, par défaut le montant déclaré).
     */
    @Transactional
    public VirementVue valider(UUID id, Long montantRecu) {
        UtilisateurConnecte.etablissementActif();
        compteurs.verrouiller("virement-traitement:" + id);
        VirementVue v = trouver(id);
        exigerEnAttente(v);
        long montant = montantRecu != null ? montantRecu : v.montant();
        if (montant <= 0) {
            throw new IllegalArgumentException("Le montant reçu est positif");
        }
        PaiementVue p = encaissements.encaisser(v.inscriptionId(), new DonneesPaiement(montant, MoyenPaiement.VIREMENT,
                Payeur.FAMILLE, null, v.reference(), v.donneurOrdre(), v.dateVirement(), "virement:" + id));
        // Paiement et reçu (JPA) écrits en base avant la mise à jour JDBC qui les référence
        paiements.flush();
        jdbc.update("""
                update virement_declare set statut = 'VALIDE', montant_valide = ?, paiement_id = ?,
                       traite_par = (select id from utilisateur where id = ?), traite_le = now()
                where tenant_id = tenant_courant() and id = ?""",
                montant, p.id(), UtilisateurConnecte.idSiConnecte().orElse(null), id);
        audit.enregistrer("VIREMENT_VALIDE", v.matricule(), Map.of("montant", montant, "recu", String.valueOf(p.recuNumero())));
        return trouver(id);
    }

    /** Refuse un virement introuvable sur le relevé (ou erroné) ; le parent est prévenu par SMS. */
    @Transactional
    public VirementVue refuser(UUID id, String motif) {
        UtilisateurConnecte.etablissementActif();
        if (motif == null || motif.isBlank() || motif.trim().length() > 300) {
            throw new IllegalArgumentException("Le motif du refus est obligatoire (300 caractères au plus)");
        }
        compteurs.verrouiller("virement-traitement:" + id);
        VirementVue v = trouver(id);
        exigerEnAttente(v);
        jdbc.update("""
                update virement_declare set statut = 'REFUSE', motif_refus = ?,
                       traite_par = (select id from utilisateur where id = ?), traite_le = now()
                where tenant_id = tenant_courant() and id = ?""",
                motif.trim(), UtilisateurConnecte.idSiConnecte().orElse(null), id);
        ContactEleve contact = contacts.pourInscriptions(List.of(v.inscriptionId())).get(v.inscriptionId());
        if (contact != null && contact.telephone() != null) {
            notifications.planifierSms("virement-refuse:" + id, contact.telephone(), contact.langueSms(),
                    notifications.nomEtablissement() + " : votre virement de " + Montants.fcfa(v.montant()) + " (réf. "
                            + v.reference() + ") pour " + v.eleve() + " n'a pas été retenu : " + motif.trim() + ".");
        }
        audit.enregistrer("VIREMENT_REFUSE", v.matricule(), Map.of("montant", v.montant(), "motif", motif.trim()));
        return trouver(id);
    }

    // ------------------------------------------------------------------ outils

    private static final String SELECTION = """
            select v.id, v.inscription_id, e.prenoms || ' ' || e.nom as eleve, e.matricule, c.code as classe,
                   v.compte_id, b.banque || ' · ' || b.numero as compte, v.montant, v.date_virement, v.reference,
                   v.donneur_ordre, v.preuve is not null as preuve, v.statut, v.montant_valide, v.motif_refus,
                   v.paiement_id, r.numero as recu, v.declare_le, v.traite_le
            from virement_declare v
            join inscription i on i.tenant_id = v.tenant_id and i.id = v.inscription_id
            join eleve e on e.tenant_id = i.tenant_id and e.id = i.eleve_id
            join classe c on c.tenant_id = i.tenant_id and c.id = i.classe_id
            join compte_bancaire b on b.tenant_id = v.tenant_id and b.id = v.compte_id
            left join recu r on r.tenant_id = v.tenant_id and r.paiement_id = v.paiement_id""";

    private VirementVue trouver(UUID id) {
        return jdbc.query(SELECTION + " where v.tenant_id = tenant_courant() and v.id = ?", (l, n) -> vue(l), id).stream()
                .findFirst().orElseThrow(() -> new RessourceIntrouvableException("Virement introuvable"));
    }

    private static VirementVue vue(ResultSet l) throws SQLException {
        long valide = l.getLong("montant_valide");
        Long montantValide = l.wasNull() ? null : valide;
        OffsetDateTime traite = l.getObject("traite_le", OffsetDateTime.class);
        return new VirementVue(l.getObject("id", UUID.class), l.getObject("inscription_id", UUID.class),
                l.getString("eleve"), l.getString("matricule"), l.getString("classe"), l.getObject("compte_id", UUID.class),
                l.getString("compte"), l.getLong("montant"), l.getObject("date_virement", LocalDate.class),
                l.getString("reference"), l.getString("donneur_ordre"), l.getBoolean("preuve"),
                StatutVirement.valueOf(l.getString("statut")), montantValide, l.getString("motif_refus"),
                l.getObject("paiement_id", UUID.class), l.getString("recu"),
                l.getObject("declare_le", OffsetDateTime.class).toInstant(), traite == null ? null : traite.toInstant());
    }

    private InscriptionVue monEnfant(UUID inscriptionId) {
        InscriptionVue inscription = inscriptions.trouver(inscriptionId);
        if (!espaceParent.estMonEnfant(inscription.eleveId())) {
            throw new AccesRefuseException("Cet élève n'est pas rattaché à votre compte");
        }
        return inscription;
    }

    private static void exigerEnAttente(VirementVue v) {
        if (v.statut() != StatutVirement.EN_ATTENTE) {
            throw new RegleMetierException("VIREMENT_DEJA_TRAITE", v.statut() == StatutVirement.VALIDE
                    ? "Ce virement est déjà validé (reçu " + v.recuNumero() + ")" : "Ce virement a déjà été refusé");
        }
    }

    /** PNG, JPEG ou PDF de 2 Mo au plus, reconnus à leurs premiers octets (pas à l'extension). */
    static Preuve verifierPreuve(Preuve p) {
        if (p == null || p.contenu() == null || p.contenu().length == 0) {
            return null;
        }
        byte[] c = p.contenu();
        if (c.length > PREUVE_MAX) {
            throw new IllegalArgumentException("La preuve dépasse 2 Mo : prenez une photo moins lourde");
        }
        String type;
        if (commencePar(c, 0x89, 'P', 'N', 'G')) {
            type = "image/png";
        } else if (commencePar(c, 0xFF, 0xD8, 0xFF)) {
            type = "image/jpeg";
        } else if (commencePar(c, '%', 'P', 'D', 'F')) {
            type = "application/pdf";
        } else {
            throw new IllegalArgumentException("La preuve est une photo (PNG ou JPEG) ou un PDF");
        }
        return new Preuve(c, type);
    }

    private static boolean commencePar(byte[] c, int... octets) {
        if (c.length < octets.length) {
            return false;
        }
        for (int i = 0; i < octets.length; i++) {
            if ((c[i] & 0xFF) != octets[i]) {
                return false;
            }
        }
        return true;
    }

    static String nomFichierPreuve(UUID id, String type) {
        String ext = switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            default -> "pdf";
        };
        return "preuve-virement-" + id + "." + ext;
    }
}
