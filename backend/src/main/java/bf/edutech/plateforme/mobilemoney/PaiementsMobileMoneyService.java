package bf.edutech.plateforme.mobilemoney;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.mobilemoney.Agregateur.Etat;
import bf.edutech.plateforme.mobilemoney.Agregateur.EtatDistant;
import bf.edutech.plateforme.mobilemoney.ConfigurationMobileMoneyService.Configuration;
import bf.edutech.plateforme.scolarite.EncaissementsService;
import bf.edutech.plateforme.scolarite.EncaissementsService.DonneesPaiement;
import bf.edutech.plateforme.scolarite.Payeur;
import bf.edutech.plateforme.scolarite.SituationsService;
import bf.edutech.plateforme.scolarite.Vues.PaiementVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.config.ParametresPlateforme;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.AuthentificationException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.erreurs.ServiceIndisponibleException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.telephone.NumeroTelephone;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Paiement Mobile Money d'un parent.
 * <ol>
 * <li>Le parent choisit le montant (au plus le reste dû par la famille) : transaction INITIEE.</li>
 * <li>La demande part chez l'agrégateur (hors transaction de base de données) : EN_ATTENTE.</li>
 * <li>Le parent confirme sur son téléphone ; l'agrégateur notifie la plateforme (notification signée).</li>
 * <li>La plateforme vérifie le statut <b>auprès de l'agrégateur</b> (jamais sur la seule notification),
 * puis enregistre le paiement et son reçu (module Scolarité) : CONFIRMEE. SMS du reçu au parent.</li>
 * </ol>
 * Sans confirmation dans le délai (15 minutes par défaut), la tâche planifiée consulte l'agrégateur puis
 * expire la transaction. Toute étape est idempotente : notification reçue deux fois, double clic…
 */
@Service
public class PaiementsMobileMoneyService {

    private static final Logger LOG = LoggerFactory.getLogger(PaiementsMobileMoneyService.class);
    private static final long MONTANT_MIN = 100;
    private static final long MONTANT_MAX = 2_000_000;

    public record TransactionVue(UUID id, UUID inscriptionId, long montant, Operateur operateur, String telephone,
            StatutTransaction statut, String reference, String referenceAgregateur, Long montantRecu,
            UUID paiementId, String message, Instant creeLe, Instant expireLe, Instant termineLe) {
    }

    private record Initiation(TransactionVue transaction, Configuration configuration, boolean nouvelle) {
    }

    private final TransactionRepository transactions;
    private final ConfigurationMobileMoneyService configuration;
    private final EncaissementsService encaissements;
    private final SituationsService situations;
    private final InscriptionsService inscriptions;
    private final EspaceParentService espaceParent;
    private final Compteurs compteurs;
    private final AuditService audit;
    private final ParametresPlateforme plateforme;
    private final MobileMoneyProperties proprietes;
    private final JdbcTemplate jdbc;
    private final Clock horloge;
    private final TransactionTemplate ecriture;
    private final TransactionTemplate lecture;

    PaiementsMobileMoneyService(TransactionRepository transactions, ConfigurationMobileMoneyService configuration,
            EncaissementsService encaissements, SituationsService situations, InscriptionsService inscriptions,
            EspaceParentService espaceParent, Compteurs compteurs, AuditService audit,
            ParametresPlateforme plateforme, MobileMoneyProperties proprietes, JdbcTemplate jdbc, Clock horloge,
            PlatformTransactionManager gestionnaire) {
        this.transactions = transactions;
        this.configuration = configuration;
        this.encaissements = encaissements;
        this.situations = situations;
        this.inscriptions = inscriptions;
        this.espaceParent = espaceParent;
        this.compteurs = compteurs;
        this.audit = audit;
        this.plateforme = plateforme;
        this.proprietes = proprietes;
        this.jdbc = jdbc;
        this.horloge = horloge;
        this.ecriture = new TransactionTemplate(gestionnaire);
        this.lecture = new TransactionTemplate(gestionnaire);
        this.lecture.setReadOnly(true);
    }

    // ------------------------------------------------------------------ parent

    /** Le parent demande un paiement ; il le confirme ensuite sur son téléphone. */
    public TransactionVue initier(UUID inscriptionId, Long montant, Operateur operateur, String telephoneSaisi,
            String cleIdempotence) {
        UtilisateurConnecte.etablissementActif();
        String cle = cleIdempotence == null || cleIdempotence.isBlank() ? UUID.randomUUID().toString()
                : cleIdempotence.trim();
        if (cle.length() > 64) {
            throw new IllegalArgumentException("Clé d'idempotence : 64 caractères au plus");
        }
        Initiation init = ecriture.execute(s -> preparer(inscriptionId, montant, operateur, telephoneSaisi, cle));
        if (!init.nouvelle()) {
            return init.transaction();
        }
        TransactionVue t = init.transaction();
        EtatDistant reponse;
        try {
            reponse = init.configuration().agregateur().initier(init.configuration().identifiants(),
                    new Agregateur.Demande(t.reference(), t.montant(), t.operateur(), t.telephone(),
                            "Scolarité – inscription " + t.inscriptionId()));
        } catch (RuntimeException e) {
            LOG.warn("Agrégateur Mobile Money indisponible : {}", e.getMessage());
            ecriture.executeWithoutResult(s -> terminerSiEnCours(t.id(), StatutTransaction.ECHOUEE,
                    "Service Mobile Money indisponible"));
            throw new ServiceIndisponibleException("MOBILE_MONEY_INDISPONIBLE",
                    "Paiement en ligne momentanément indisponible : réessayez plus tard ou payez à l'intendance");
        }
        return ecriture.execute(s -> {
            compteurs.verrouiller("mobile-money:" + t.id());
            TransactionMobileMoney tr = charger(t.id());
            if (reponse.etat() == Etat.ECHOUEE) {
                if (tr.getStatut().enCours()) {
                    tr.terminer(StatutTransaction.ECHOUEE, null, reponse.message(), horloge.instant());
                }
            } else if (tr.getStatut() == StatutTransaction.INITIEE) {
                tr.enAttente(reponse.referenceAgregateur());
            }
            return vue(tr);
        });
    }

    private Initiation preparer(UUID inscriptionId, Long montant, Operateur operateur, String telephoneSaisi,
            String cle) {
        compteurs.verrouiller("mobile-money:" + inscriptionId);
        var existante = transactions.findByCleIdempotence(cle);
        if (existante.isPresent()) {
            TransactionMobileMoney t = existante.get();
            if (!UtilisateurConnecte.idSiConnecte().equals(java.util.Optional.ofNullable(t.getInitieePar()))) {
                throw new RessourceIntrouvableException("Transaction introuvable");
            }
            if (!t.getInscriptionId().equals(inscriptionId) || montant == null || t.getMontant() != montant
                    || t.getOperateur() != operateur) {
                throw new RegleMetierException("CLE_DEJA_UTILISEE",
                        "Cette clé d'idempotence a déjà servi pour une autre demande");
            }
            return new Initiation(vue(t), null, false);
        }
        Configuration conf = configuration.active();
        InscriptionVue inscription = inscriptions.trouver(inscriptionId);
        if (!espaceParent.estMonEnfant(inscription.eleveId())) {
            throw new AccesRefuseException("Cet élève n'est pas rattaché à votre compte");
        }
        if (operateur == null) {
            throw new IllegalArgumentException("Choisissez l'opérateur (ORANGE_MONEY ou MOOV_MONEY)");
        }
        if (montant == null || montant < MONTANT_MIN || montant > MONTANT_MAX) {
            throw new IllegalArgumentException("Le montant est compris entre 100 et 2 000 000 FCFA");
        }
        String telephone = NumeroTelephone.normaliser(telephoneSaisi, plateforme.indicatifTelephone());
        Instant maintenant = horloge.instant();
        boolean enCours = transactions.findByInscriptionIdOrderByCreeLeDesc(inscriptionId).stream()
                .anyMatch(t -> t.getStatut().enCours() && t.getExpireLe().isAfter(maintenant));
        if (enCours) {
            throw new RegleMetierException("TRANSACTION_EN_COURS",
                    "Un paiement attend déjà votre confirmation sur le téléphone : validez-le ou attendez son expiration");
        }
        long reste = situations.situation(inscriptionId).resteFamille();
        if (reste == 0) {
            throw new RegleMetierException("RIEN_A_PAYER", "Rien n'est dû pour cet élève");
        }
        if (montant > reste) {
            throw new RegleMetierException("MONTANT_SUPERIEUR_AU_RESTE",
                    "Le montant dépasse le reste à payer (" + reste + " FCFA)");
        }
        TransactionMobileMoney t = transactions.save(new TransactionMobileMoney(inscriptionId, montant, operateur,
                telephone, cle, UtilisateurConnecte.idSiConnecte().orElse(null), maintenant,
                maintenant.plus(proprietes.dureeValidite())));
        audit.enregistrer("MOBILE_MONEY_INITIE", t.getReference(), Map.of("montant", montant));
        return new Initiation(vue(t), conf, true);
    }

    /** Suivi d'une transaction par le parent qui l'a demandée (écran « confirmez sur votre téléphone »). */
    public TransactionVue suivrePourParent(UUID transactionId) {
        UtilisateurConnecte.etablissementActif();
        return lecture.execute(s -> {
            TransactionMobileMoney t = charger(transactionId);
            if (!espaceParent.estMonEnfant(inscriptions.trouver(t.getInscriptionId()).eleveId())) {
                throw new RessourceIntrouvableException("Transaction introuvable");
            }
            return vue(t);
        });
    }

    // ------------------------------------------------------------------ notification de l'agrégateur

    /** Notification (webhook) : signature vérifiée avec le secret de l'école, puis statut consulté à la source. */
    public void notifier(UUID tenantId, byte[] corps, HttpHeaders entetes) {
        TenantContext.executerPour(tenantId, () -> {
            if (corps == null || corps.length > 16_384) {
                throw new AuthentificationException("Notification refusée");
            }
            Configuration conf;
            try {
                conf = configuration.charger().orElse(null);
            } catch (RuntimeException e) {
                LOG.warn("Configuration Mobile Money illisible pour l'école {} : {}", tenantId, e.getMessage());
                conf = null;
            }
            if (conf == null) {
                throw new AuthentificationException("Notification refusée");
            }
            if (!conf.agregateur().signatureValide(conf.identifiants(), corps, entetes)) {
                LOG.warn("Notification Mobile Money à la signature invalide pour l'école {}", tenantId);
                throw new AuthentificationException("Notification refusée");
            }
            String reference = conf.agregateur().referenceNotifiee(corps);
            if (reference == null) {
                throw new IllegalArgumentException("Référence absente de la notification");
            }
            UUID id = lecture.execute(s -> transactions.findByReference(reference).map(TransactionMobileMoney::getId)
                    .orElseThrow(() -> new RessourceIntrouvableException("Transaction inconnue")));
            rafraichir(conf, id, false);
            return null;
        });
    }

    // ------------------------------------------------------------------ intendance

    public List<TransactionVue> lister(LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        if (du == null || au == null || au.isBefore(du) || du.plusDays(366).isBefore(au)) {
            throw new IllegalArgumentException("Période invalide (366 jours au plus)");
        }
        return lecture.execute(s -> transactions.findByCreeLeBetweenOrderByCreeLeDesc(
                du.atStartOfDay().toInstant(ZoneOffset.UTC), au.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
                .stream().map(PaiementsMobileMoneyService::vue).toList());
    }

    /** Transactions qui demandent l'attention de l'intendance. */
    public List<TransactionVue> aVerifier() {
        UtilisateurConnecte.etablissementActif();
        return lecture.execute(s -> transactions.findByStatutInOrderByCreeLeDesc(List.of(StatutTransaction.A_VERIFIER))
                .stream().map(PaiementsMobileMoneyService::vue).toList());
    }

    /** Consulte à nouveau l'agrégateur (notification perdue, doute du parent…). */
    public TransactionVue verifier(UUID transactionId) {
        UtilisateurConnecte.etablissementActif();
        Configuration conf = lecture.execute(s -> configuration.charger()).orElseThrow(() ->
                new RegleMetierException("MOBILE_MONEY_INACTIF", "Le paiement Mobile Money n'est pas configuré"));
        rafraichir(conf, transactionId, false);
        return lecture.execute(s -> vue(charger(transactionId)));
    }

    /** Clôt une transaction à vérifier, une fois la situation réglée (paiement saisi, remboursement…). */
    public TransactionVue regulariser(UUID transactionId, String motif) {
        UtilisateurConnecte.etablissementActif();
        if (motif == null || motif.isBlank() || motif.trim().length() > 300) {
            throw new IllegalArgumentException("Le motif est obligatoire (300 caractères au plus)");
        }
        return ecriture.execute(s -> {
            compteurs.verrouiller("mobile-money:" + transactionId);
            TransactionMobileMoney t = charger(transactionId);
            if (t.getStatut() != StatutTransaction.A_VERIFIER) {
                throw new RegleMetierException("TRANSACTION_NON_A_VERIFIER",
                        "Seule une transaction « à vérifier » se régularise");
            }
            t.terminer(StatutTransaction.REGULARISEE, t.getMontantRecu(), motif.trim(), horloge.instant());
            audit.enregistrer("MOBILE_MONEY_REGULARISE", t.getReference(), Map.of("motif", motif.trim()));
            return vue(t);
        });
    }

    // ------------------------------------------------------------------ tâche planifiée

    /** Transactions échues de toutes les écoles : statut consulté, puis confirmées ou expirées. */
    public int traiterEchues() {
        return traiterEchues(horloge.instant());
    }

    int traiterEchues(Instant maintenant) {
        record Echue(UUID tenantId, UUID id) {
        }
        List<Echue> echues = jdbc.query("select tenant_id, id from transactions_mobile_money_echues(?, ?)",
                (l, n) -> new Echue(l.getObject(1, UUID.class), l.getObject(2, UUID.class)),
                java.sql.Timestamp.from(maintenant), 200);
        int traitees = 0;
        for (Echue e : echues) {
            try {
                TenantContext.executerPour(e.tenantId(), () -> {
                    Configuration conf = lecture.execute(s -> configuration.charger()).orElse(null);
                    if (conf == null) {
                        ecriture.executeWithoutResult(s -> terminerSiEnCours(e.id(), StatutTransaction.EXPIREE,
                                "Mobile Money désactivé"));
                    } else {
                        rafraichir(conf, e.id(), true);
                    }
                    return null;
                });
                traitees++;
            } catch (RuntimeException ex) {
                LOG.warn("Transaction Mobile Money {} non traitée : {}", e.id(), ex.getMessage());
                noterEchec(e.tenantId(), e.id(), maintenant);
            }
        }
        return traitees;
    }

    // ------------------------------------------------------------------ cœur

    /** Consulte l'agrégateur (hors transaction de base), puis applique le résultat. */
    private void rafraichir(Configuration conf, UUID transactionId, boolean expirationDepassee) {
        TransactionMobileMoney lue = lecture.execute(s -> charger(transactionId));
        boolean close = lue.getStatut() == StatutTransaction.EXPIREE || lue.getStatut() == StatutTransaction.ECHOUEE;
        if (!lue.getStatut().enCours() && !close) {
            return;                                          // déjà traitée : idempotence
        }
        EtatDistant etat;
        try {
            etat = conf.agregateur().consulter(conf.identifiants(), lue.getReference());
        } catch (RuntimeException e) {
            throw new ServiceIndisponibleException("MOBILE_MONEY_INDISPONIBLE",
                    "L'agrégateur Mobile Money ne répond pas : nouvelle tentative plus tard");
        }
        if (close) {
            // Confirmée chez l'agrégateur après expiration ou échec apparent : le parent a été débité
            if (etat.etat() == Etat.CONFIRMEE) {
                ecriture.executeWithoutResult(s -> {
                    compteurs.verrouiller("mobile-money:" + transactionId);
                    TransactionMobileMoney t = charger(transactionId);
                    if (t.getStatut() == StatutTransaction.EXPIREE || t.getStatut() == StatutTransaction.ECHOUEE) {
                        aVerifier(t, etat.montant() != null ? etat.montant() : t.getMontant(),
                                "Confirmée par l'agrégateur après la clôture (" + t.getStatut().name()
                                        .toLowerCase() + ") : à enregistrer par l'intendance");
                    }
                });
            }
            return;
        }
        try {
            ecriture.executeWithoutResult(s -> appliquer(transactionId, etat, expirationDepassee));
        } catch (RuntimeException e) {
            if (etat.etat() != Etat.CONFIRMEE) {
                throw e;                                     // rien n'a été reçu : nouvelle tentative plus tard
            }
            // L'argent est reçu mais le paiement n'a pas pu être enregistré : à l'intendance de régulariser
            LOG.warn("Paiement Mobile Money {} à vérifier : {}", lue.getReference(), e.getMessage());
            ecriture.executeWithoutResult(s -> {
                compteurs.verrouiller("mobile-money:" + transactionId);
                TransactionMobileMoney t = charger(transactionId);
                if (t.getStatut().enCours()) {
                    t.terminer(StatutTransaction.A_VERIFIER, etat.montant(),
                            "Enregistrement impossible : " + e.getMessage(), horloge.instant());
                    audit.enregistrer("MOBILE_MONEY_A_VERIFIER", t.getReference(), null);
                }
            });
        }
    }

    private void appliquer(UUID transactionId, EtatDistant etat, boolean expirationDepassee) {
        compteurs.verrouiller("mobile-money:" + transactionId);
        TransactionMobileMoney t = charger(transactionId);
        if (!t.getStatut().enCours()) {
            return;
        }
        Instant maintenant = horloge.instant();
        switch (etat.etat()) {
            case CONFIRMEE -> {
                long recu = etat.montant() != null ? etat.montant() : t.getMontant();
                long reste = situations.situation(t.getInscriptionId()).resteFamille();
                if (recu != t.getMontant()) {
                    aVerifier(t, recu, "Montant reçu (" + recu + " FCFA) différent du montant demandé ("
                            + t.getMontant() + " FCFA)");
                } else if (recu > reste) {
                    aVerifier(t, recu, "Montant supérieur au reste dû (" + reste + " FCFA) : paiement déjà réglé "
                            + "par un autre moyen ?");
                } else {
                    String reference = etat.referenceAgregateur() != null ? etat.referenceAgregateur()
                            : t.getReference();
                    PaiementVue paiement = encaissements.encaisser(t.getInscriptionId(), new DonneesPaiement(recu,
                            t.getOperateur().moyen(), Payeur.FAMILLE, null,
                            reference.length() > 60 ? reference.substring(0, 60) : reference,
                            "Mobile Money " + t.getTelephone(), LocalDate.now(horloge), "mm:" + t.getId()));
                    t.confirmer(paiement.id(), recu, etat.referenceAgregateur(), maintenant);
                    audit.enregistrer("MOBILE_MONEY_CONFIRME", t.getReference(),
                            Map.of("montant", recu, "recu", String.valueOf(paiement.recuNumero())));
                }
            }
            case ECHOUEE -> t.terminer(StatutTransaction.ECHOUEE, null, etat.message(), maintenant);
            case EN_ATTENTE -> {
                if (expirationDepassee) {
                    t.terminer(StatutTransaction.EXPIREE, null, "Délai de confirmation dépassé", maintenant);
                }
            }
        }
    }

    private void aVerifier(TransactionMobileMoney t, long recu, String message) {
        t.terminer(StatutTransaction.A_VERIFIER, recu, message, horloge.instant());
        audit.enregistrer("MOBILE_MONEY_A_VERIFIER", t.getReference(), Map.of("montantRecu", recu));
    }

    /** Vérification impossible : nouvelle tentative plus tard ; abandon au bout de 3 jours (le rapprochement veille). */
    private void noterEchec(UUID tenantId, UUID id, Instant maintenant) {
        try {
            TenantContext.executerPour(tenantId, () -> {
                ecriture.executeWithoutResult(s -> {
                    compteurs.verrouiller("mobile-money:" + id);
                    TransactionMobileMoney t = charger(id);
                    if (!t.getStatut().enCours()) {
                        return;
                    }
                    if (t.getExpireLe().isBefore(maintenant.minus(java.time.Duration.ofDays(3)))) {
                        t.terminer(StatutTransaction.EXPIREE, null, "Sans réponse de l'agrégateur pendant 3 jours : "
                                + "le rapprochement signalera tout encaissement", maintenant);
                    } else {
                        t.noterTentative(maintenant);
                    }
                });
                return null;
            });
        } catch (RuntimeException ex) {
            LOG.warn("Tentative non enregistrée pour la transaction {} : {}", id, ex.getMessage());
        }
    }

    private void terminerSiEnCours(UUID id, StatutTransaction statut, String message) {
        compteurs.verrouiller("mobile-money:" + id);
        TransactionMobileMoney t = charger(id);
        if (t.getStatut().enCours()) {
            t.terminer(statut, null, message, horloge.instant());
        }
    }

    private TransactionMobileMoney charger(UUID id) {
        return transactions.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Transaction introuvable"));
    }

    static TransactionVue vue(TransactionMobileMoney t) {
        return new TransactionVue(t.getId(), t.getInscriptionId(), t.getMontant(), t.getOperateur(), t.getTelephone(),
                t.getStatut(), t.getReference(), t.getReferenceAgregateur(), t.getMontantRecu(), t.getPaiementId(),
                t.getMessage(), t.getCreeLe(), t.getExpireLe(), t.getTermineLe());
    }
}
