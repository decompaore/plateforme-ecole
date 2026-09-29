package bf.edutech.plateforme.scolarite;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.scolarite.RenduRecu.DonneesRecu;
import bf.edutech.plateforme.scolarite.Situations.Situation;
import bf.edutech.plateforme.scolarite.Vues.JournalVue;
import bf.edutech.plateforme.scolarite.Vues.PaiementVue;
import bf.edutech.plateforme.scolarite.Vues.TotalMoyenVue;
import bf.edutech.plateforme.scolarite.Vues.VerificationRecuVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Encaissements au guichet (espèces, chèque, Mobile Money reçu sur le compte de
 * l'école) et versements des organismes. Chaque paiement produit un reçu numéroté
 * non modifiable ; une erreur se corrige par annulation (avec motif) puis nouvel
 * encaissement. Les paiements soldent les tranches les plus anciennes d'abord.
 */
@Service
public class EncaissementsService {

    public record DonneesPaiement(Long montant, MoyenPaiement moyen, Payeur payeur, UUID organismeId,
            String referenceExterne, String deposant, LocalDate datePaiement, String cleIdempotence) {
    }

    private static final String ALPHABET_CODE = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom HASARD = new SecureRandom();

    private final PaiementRepository paiements;
    private final RecuRepository recus;
    private final Situations situations;
    private final OrganismesService organismes;
    private final RenduRecu rendu;
    private final InscriptionsService inscriptions;
    private final EspaceParentService espaceParent;
    private final AnneesService annees;
    private final ContactsEleves contacts;
    private final NotificationsService notifications;
    private final Compteurs compteurs;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock horloge;

    EncaissementsService(PaiementRepository paiements, RecuRepository recus, Situations situations,
            OrganismesService organismes, RenduRecu rendu, InscriptionsService inscriptions,
            EspaceParentService espaceParent, AnneesService annees, ContactsEleves contacts,
            NotificationsService notifications, Compteurs compteurs, AuditService audit, JdbcTemplate jdbc,
            Clock horloge) {
        this.paiements = paiements;
        this.recus = recus;
        this.situations = situations;
        this.organismes = organismes;
        this.rendu = rendu;
        this.inscriptions = inscriptions;
        this.espaceParent = espaceParent;
        this.annees = annees;
        this.contacts = contacts;
        this.notifications = notifications;
        this.compteurs = compteurs;
        this.audit = audit;
        this.jdbc = jdbc;
        this.horloge = horloge;
    }

    /**
     * Encaisse un paiement. Idempotent par {@code cleIdempotence} : un second envoi
     * (double clic, réseau instable) renvoie le paiement déjà enregistré.
     */
    @Transactional
    public PaiementVue encaisser(UUID inscriptionId, DonneesPaiement d) {
        UtilisateurConnecte.etablissementActif();
        String cle = d.cleIdempotence() == null || d.cleIdempotence().isBlank() ? UUID.randomUUID().toString()
                : d.cleIdempotence().trim();
        if (cle.length() > 64) {
            throw new IllegalArgumentException("Clé d'idempotence : 64 caractères au plus");
        }
        compteurs.verrouiller("paiement:" + inscriptionId);
        Optional<Paiement> existant = paiements.findByCleIdempotence(cle);
        if (existant.isPresent()) {
            Paiement p = existant.get();
            boolean identique = p.getInscriptionId().equals(inscriptionId) && d.montant() != null
                    && p.getMontant() == d.montant() && p.getPayeur() == d.payeur() && p.getMoyen() == d.moyen();
            if (!identique || p.estAnnule()) {
                throw new RegleMetierException("CLE_DEJA_UTILISEE", p.estAnnule()
                        ? "Le paiement enregistré avec cette clé a été annulé : encaissez avec une nouvelle clé"
                        : "Cette clé d'idempotence a déjà servi pour un autre paiement");
            }
            return Situations.vue(p, recus.findByPaiementId(p.getId()).orElse(null));
        }
        InscriptionVue inscription = inscriptions.trouver(inscriptionId);
        if (d.montant() == null || d.montant() <= 0 || d.montant() > 100_000_000L) {
            throw new IllegalArgumentException("Le montant est compris entre 1 et 100 000 000 FCFA");
        }
        if (d.moyen() == null || d.payeur() == null) {
            throw new IllegalArgumentException("Indiquez le moyen de paiement et le payeur");
        }
        if (d.moyen() != MoyenPaiement.ESPECES && (d.referenceExterne() == null || d.referenceExterne().isBlank())) {
            throw new IllegalArgumentException("La référence (n° de chèque, de virement ou de transaction) est "
                    + "obligatoire pour un paiement " + d.moyen().libelle());
        }
        if (longueur(d.referenceExterne()) > 60 || longueur(d.deposant()) > 120) {
            throw new IllegalArgumentException("Référence : 60 caractères au plus ; déposant : 120 caractères au plus");
        }
        LocalDate jour = LocalDate.now(horloge);
        LocalDate date = d.datePaiement() != null ? d.datePaiement() : jour;
        if (date.isAfter(jour)) {
            throw new IllegalArgumentException("La date du paiement ne peut pas être dans le futur");
        }
        if (date.isBefore(annees.trouver(inscription.anneeId()).debut().minusMonths(6))) {
            throw new IllegalArgumentException("La date du paiement est antérieure à l'année scolaire");
        }
        Situation situation = situations.calculer(inscription);
        UUID organismeId = null;
        long reste;
        if (d.payeur() == Payeur.ORGANISME) {
            if (situation.priseEnCharge() == null) {
                throw new RegleMetierException("SANS_PRISE_EN_CHARGE", inscription.prenoms() + " "
                        + inscription.nom() + " n'a pas de prise en charge : enregistrez-la d'abord");
            }
            organismeId = d.organismeId() != null ? d.organismeId() : situation.priseEnCharge().getOrganismeId();
            if (!organismeId.equals(situation.priseEnCharge().getOrganismeId())) {
                throw new RegleMetierException("ORGANISME_NON_FINANCEUR",
                        "Cet organisme ne prend pas en charge cet élève");
            }
            if (!organismes.trouver(organismeId).isActif()) {
                throw new RegleMetierException("ORGANISME_INACTIF", "Cet organisme est désactivé");
            }
            reste = situation.resultat().resteOrganisme();
        } else {
            reste = situation.resultat().resteFamille();
        }
        if (reste == 0) {
            throw new RegleMetierException("RIEN_A_PAYER", "Rien n'est dû par " + (d.payeur() == Payeur.FAMILLE
                    ? "la famille" : "l'organisme") + " pour " + inscription.prenoms() + " " + inscription.nom());
        }
        if (d.montant() > reste) {
            throw new RegleMetierException("MONTANT_SUPERIEUR_AU_RESTE", "Le montant dépasse le reste à payer ("
                    + Montants.fcfa(reste) + ")");
        }
        Instant maintenant = horloge.instant();
        Paiement p = paiements.save(new Paiement(inscriptionId, d.montant(), d.moyen(), d.payeur(), organismeId,
                vide(d.referenceExterne()), vide(d.deposant()), date, cle,
                UtilisateurConnecte.idSiConnecte().orElse(null), maintenant));
        String numero = maintenant.atZone(ZoneOffset.UTC).getYear() + "-"
                + String.format("%06d", compteurs.suivant("recu"));
        Recu recu = recus.save(new Recu(p.getId(), numero, nouveauCode(), maintenant));
        if (d.payeur() == Payeur.FAMILLE) {
            ContactEleve contact = contacts.pourInscriptions(List.of(inscriptionId)).get(inscriptionId);
            if (contact != null && contact.telephone() != null) {
                notifications.planifierSms("paiement:" + p.getId(), contact.telephone(), contact.langueSms(),
                        notifications.nomEtablissement() + " : reçu n° " + numero + ", " + Montants.fcfa(d.montant())
                                + " reçus pour " + inscription.prenoms() + " " + inscription.nom() + " ("
                                + inscription.classeCode() + "). Reste à payer : "
                                + Montants.fcfa(reste - d.montant()) + ".");
            }
        }
        audit.enregistrer("PAIEMENT_ENCAISSE", numero + " / " + inscription.matricule(),
                Map.of("montant", d.montant(), "moyen", d.moyen().name(), "payeur", d.payeur().name()));
        return Situations.vue(p, recu);
    }

    /** Annule un paiement (erreur de saisie, chèque impayé…) ; le reçu reste, marqué « annulé ». */
    @Transactional
    public PaiementVue annuler(UUID paiementId, String motif) {
        UtilisateurConnecte.etablissementActif();
        if (motif == null || motif.isBlank() || motif.trim().length() > 200) {
            throw new IllegalArgumentException("Le motif d'annulation est obligatoire (200 caractères au plus)");
        }
        Paiement p = paiement(paiementId);
        p.annuler(motif.trim(), UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant());
        Recu recu = recus.findByPaiementId(paiementId).orElse(null);
        notifications.annuler("paiement:" + paiementId);
        audit.enregistrer("PAIEMENT_ANNULE", recu != null ? recu.getNumero() : paiementId.toString(),
                Map.of("montant", p.getMontant(), "motif", motif.trim()));
        return Situations.vue(p, recu);
    }

    @Transactional(readOnly = true)
    public byte[] recuPdf(UUID paiementId) {
        UtilisateurConnecte.etablissementActif();
        return rendre(paiement(paiementId));
    }

    /** Reçu d'un paiement d'un enfant du parent connecté (404 sinon). */
    @Transactional(readOnly = true)
    public byte[] recuPdfPourParent(UUID paiementId) {
        UtilisateurConnecte.etablissementActif();
        Paiement p = paiement(paiementId);
        if (!espaceParent.estMonEnfant(inscriptions.trouver(p.getInscriptionId()).eleveId())) {
            throw new RessourceIntrouvableException("Paiement introuvable");
        }
        return rendre(p);
    }

    /** Journal de caisse : paiements non annulés sur la période, avec les totaux par moyen. */
    @Transactional(readOnly = true)
    public JournalVue journal(LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        if (du == null || au == null || au.isBefore(du) || du.plusDays(366).isBefore(au)) {
            throw new IllegalArgumentException("Période invalide (366 jours au plus)");
        }
        List<Paiement> liste = paiements.findByDatePaiementBetweenOrderByEnregistreLeAsc(du, au).stream()
                .filter(p -> !p.estAnnule()).toList();
        Map<MoyenPaiement, long[]> totaux = new EnumMap<>(MoyenPaiement.class);
        for (Paiement p : liste) {
            long[] t = totaux.computeIfAbsent(p.getMoyen(), m -> new long[2]);
            t[0] += p.getMontant();
            t[1]++;
        }
        List<TotalMoyenVue> parMoyen = new ArrayList<>();
        totaux.forEach((moyen, t) -> parMoyen.add(new TotalMoyenVue(moyen, t[0], (int) t[1])));
        return new JournalVue(du, au, liste.stream().mapToLong(Paiement::getMontant).sum(), parMoyen,
                situations.paiements(liste));
    }

    /** Vérification publique d'un reçu papier (sans connexion). */
    public VerificationRecuVue verifier(String codeSaisi) {
        String code = codeSaisi == null ? "" : codeSaisi.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (code.length() != 10) {
            throw new RessourceIntrouvableException("Code de vérification inconnu");
        }
        return jdbc.query("""
                select etablissement, numero, eleve, matricule, montant, payeur, date_paiement, emis_le, annule
                from verifier_recu(?)""",
                (l, n) -> new VerificationRecuVue(l.getString(1), l.getString(2), l.getString(3), l.getString(4),
                        l.getLong(5), Payeur.valueOf(l.getString(6)), l.getDate(7).toLocalDate(),
                        l.getTimestamp(8).toInstant(), l.getBoolean(9)),
                code).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Code de vérification inconnu"));
    }

    // ------------------------------------------------------------------

    private byte[] rendre(Paiement p) {
        Recu recu = recus.findByPaiementId(p.getId())
                .orElseThrow(() -> new RessourceIntrouvableException("Reçu introuvable"));
        InscriptionVue i = inscriptions.trouver(p.getInscriptionId());
        Situation s = situations.calculer(i);
        String payeur = p.getPayeur() == Payeur.ORGANISME ? organismes.trouver(p.getOrganismeId()).getNom()
                : "Famille";
        return rendu.rendre(new DonneesRecu(notifications.nomEtablissement(), annees.trouver(i.anneeId()).libelle(),
                recu.getNumero(), recu.getCodeVerification(), recu.getEmisLe(), i.nom() + " " + i.prenoms(),
                i.matricule(), i.classeCode(), p.getMontant(), payeur, p.getMoyen().libelle(), p.getReferenceExterne(),
                p.getDeposant(), p.getDatePaiement(), s.resultat().resteFamille(), situations.aujourdhui(),
                p.estAnnule(), p.getMotifAnnulation(), p.getAnnuleLe()));
    }

    private Paiement paiement(UUID id) {
        return paiements.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Paiement introuvable"));
    }

    private static String nouveauCode() {
        StringBuilder code = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            code.append(ALPHABET_CODE.charAt(HASARD.nextInt(ALPHABET_CODE.length())));
        }
        return code.toString();
    }

    private static int longueur(String valeur) {
        return valeur == null ? 0 : valeur.trim().length();
    }

    private static String vide(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
