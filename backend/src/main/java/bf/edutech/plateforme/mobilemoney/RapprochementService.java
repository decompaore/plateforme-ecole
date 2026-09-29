package bf.edutech.plateforme.mobilemoney;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.mobilemoney.Agregateur.LigneReleve;
import bf.edutech.plateforme.mobilemoney.ConfigurationMobileMoneyService.Configuration;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Rapprochement quotidien : le relevé de l'agrégateur (transactions réussies d'une journée)
 * est comparé aux transactions de la plateforme. Chaque différence devient un écart que
 * l'intendance examine puis marque « traité ».
 */
@Service
public class RapprochementService {

    private static final Logger LOG = LoggerFactory.getLogger(RapprochementService.class);

    public record RapprochementVue(UUID id, LocalDate dateReleve, int lignes, int ecarts, String statut,
            String message, Instant executeLe) {
    }

    public record EcartVue(UUID id, String type, String reference, Long montantAttendu, Long montantRecu,
            UUID transactionId, boolean traite, String commentaire) {
    }

    private final RapprochementRepository rapprochements;
    private final EcartRepository ecarts;
    private final TransactionRepository transactions;
    private final ConfigurationMobileMoneyService configuration;
    private final Compteurs compteurs;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock horloge;
    private final TransactionTemplate ecriture;
    private final TransactionTemplate lecture;

    RapprochementService(RapprochementRepository rapprochements, EcartRepository ecarts,
            TransactionRepository transactions, ConfigurationMobileMoneyService configuration, Compteurs compteurs,
            AuditService audit, JdbcTemplate jdbc, Clock horloge, PlatformTransactionManager gestionnaire) {
        this.rapprochements = rapprochements;
        this.ecarts = ecarts;
        this.transactions = transactions;
        this.configuration = configuration;
        this.compteurs = compteurs;
        this.audit = audit;
        this.jdbc = jdbc;
        this.horloge = horloge;
        this.ecriture = new TransactionTemplate(gestionnaire);
        this.lecture = new TransactionTemplate(gestionnaire);
        this.lecture.setReadOnly(true);
    }

    /** Rapproche une journée pour l'établissement actif (refait si aucun écart n'a encore été traité). */
    public RapprochementVue rapprocher(LocalDate jour) {
        UtilisateurConnecte.etablissementActif();
        if (jour == null || !jour.isBefore(LocalDate.now(horloge).plusDays(1))) {
            throw new IllegalArgumentException("Date de relevé invalide (aujourd'hui au plus tard)");
        }
        Configuration conf = lecture.execute(s -> configuration.active());
        List<LigneReleve> releve;
        String echec = null;
        try {
            releve = conf.agregateur().releve(conf.identifiants(), jour);
        } catch (RuntimeException e) {
            releve = List.of();
            echec = "Relevé indisponible : " + e.getMessage();
        }
        List<LigneReleve> lignes = releve;
        String message = echec;
        return ecriture.execute(s -> enregistrer(jour, lignes, message));
    }

    private RapprochementVue enregistrer(LocalDate jour, List<LigneReleve> releve, String echec) {
        compteurs.verrouiller("rapprochement:" + UtilisateurConnecte.etablissementActif() + ":" + jour);
        var existant = rapprochements.findByDateReleve(jour);
        if (echec != null && existant.isPresent()) {
            LOG.warn("Relevé du {} indisponible : le rapprochement déjà fait est conservé ({})", jour, echec);
            return vue(existant.get());
        }
        existant.ifPresent(ancien -> {
            if (ecarts.findByRapprochementIdOrderByTypeAscReferenceAsc(ancien.getId()).stream()
                    .anyMatch(EcartRapprochement::isTraite)) {
                throw new RegleMetierException("RAPPROCHEMENT_TRAITE",
                        "Des écarts de ce jour sont déjà traités : le rapprochement ne peut plus être refait");
            }
            rapprochements.delete(ancien);
            rapprochements.flush();
        });
        Instant maintenant = horloge.instant();
        if (echec != null) {
            Rapprochement r = rapprochements.save(new Rapprochement(jour, 0, 0, "ECHEC", echec, maintenant));
            return vue(r);
        }
        record Ecart(String type, String reference, Long attendu, Long recu, UUID transactionId) {
        }
        List<Ecart> trouves = new ArrayList<>();
        Map<String, LigneReleve> parReference = new HashMap<>();
        for (LigneReleve l : releve) {
            if (parReference.putIfAbsent(l.reference(), l) != null) {
                // Même transaction deux fois dans le relevé : le parent a peut-être été débité deux fois
                trouves.add(new Ecart("DOUBLON", l.reference(), null, l.montant(),
                        transactions.findByReference(l.reference()).map(TransactionMobileMoney::getId).orElse(null)));
                continue;
            }
            var t = transactions.findByReference(l.reference());
            if (t.isEmpty()) {
                trouves.add(new Ecart("NON_ENREGISTRE", Objects.requireNonNullElse(l.referenceAgregateur(),
                        l.reference()), null, l.montant(), null));
                continue;
            }
            TransactionMobileMoney tr = t.get();
            switch (tr.getStatut()) {
                case CONFIRMEE -> {
                    if (tr.getMontantRecu() == null || tr.getMontantRecu() != l.montant()) {
                        trouves.add(new Ecart("MONTANT_DIFFERENT", tr.getReference(), tr.getMontantRecu(),
                                l.montant(), tr.getId()));
                    }
                }
                case REGULARISEE -> {
                    // déjà examinée et réglée par l'intendance
                }
                default -> trouves.add(new Ecart(tr.getMontant() != l.montant() ? "MONTANT_DIFFERENT"
                        : "NON_ENREGISTRE", tr.getReference(), tr.getMontant(), l.montant(), tr.getId()));
            }
        }
        Instant debut = jour.atStartOfDay().toInstant(ZoneOffset.UTC);
        for (TransactionMobileMoney tr : transactions.findByTermineLeBetween(debut, debut.plusSeconds(86_400))) {
            if (tr.getStatut() == StatutTransaction.CONFIRMEE && !parReference.containsKey(tr.getReference())) {
                trouves.add(new Ecart("ABSENT_DU_RELEVE", tr.getReference(), tr.getMontantRecu(), null, tr.getId()));
            }
        }
        Rapprochement r = rapprochements.save(new Rapprochement(jour, releve.size(), trouves.size(),
                trouves.isEmpty() ? "OK" : "ECARTS", null, maintenant));
        trouves.forEach(e -> ecarts.save(new EcartRapprochement(r.getId(), e.type(), e.reference(), e.attendu(),
                e.recu(), e.transactionId())));
        audit.enregistrer("RAPPROCHEMENT_MOBILE_MONEY", jour.toString(),
                Map.of("lignes", releve.size(), "ecarts", trouves.size()));
        return vue(r);
    }

    public List<RapprochementVue> lister(LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        if (du == null || au == null || au.isBefore(du)) {
            throw new IllegalArgumentException("Période invalide");
        }
        return lecture.execute(s -> rapprochements.findByDateReleveBetweenOrderByDateReleveDesc(du, au).stream()
                .map(RapprochementService::vue).toList());
    }

    public List<EcartVue> ecarts(UUID rapprochementId) {
        UtilisateurConnecte.etablissementActif();
        return lecture.execute(s -> {
            rapprochements.findById(rapprochementId)
                    .orElseThrow(() -> new RessourceIntrouvableException("Rapprochement introuvable"));
            return ecarts.findByRapprochementIdOrderByTypeAscReferenceAsc(rapprochementId).stream()
                    .map(RapprochementService::vue).toList();
        });
    }

    public EcartVue traiter(UUID ecartId, String commentaire) {
        UtilisateurConnecte.etablissementActif();
        if (commentaire == null || commentaire.isBlank() || commentaire.trim().length() > 300) {
            throw new IllegalArgumentException("Le commentaire est obligatoire (300 caractères au plus)");
        }
        return ecriture.execute(s -> {
            EcartRapprochement e = ecarts.findById(ecartId)
                    .orElseThrow(() -> new RessourceIntrouvableException("Écart introuvable"));
            if (e.isTraite()) {
                throw new RegleMetierException("ECART_DEJA_TRAITE", "Cet écart est déjà traité");
            }
            e.traiter(commentaire.trim(), UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant());
            audit.enregistrer("ECART_TRAITE", e.getReference(), Map.of("commentaire", commentaire.trim()));
            return vue(e);
        });
    }

    /** Tâche de nuit : rapproche la veille pour toutes les écoles qui utilisent Mobile Money. */
    public int rapprocherToutes(LocalDate jour) {
        List<UUID> ecoles = jdbc.queryForList("select tenant_id from ecoles_mobile_money()", UUID.class);
        int faites = 0;
        for (UUID ecole : ecoles) {
            try {
                TenantContext.executerPour(ecole, () -> rapprocher(jour));
                faites++;
            } catch (RuntimeException e) {
                LOG.warn("Rapprochement Mobile Money du {} impossible pour l'école {} : {}", jour, ecole,
                        e.getMessage());
            }
        }
        return faites;
    }

    private static RapprochementVue vue(Rapprochement r) {
        return new RapprochementVue(r.getId(), r.getDateReleve(), r.getLignes(), r.getEcarts(), r.getStatut(),
                r.getMessage(), r.getExecuteLe());
    }

    private static EcartVue vue(EcartRapprochement e) {
        return new EcartVue(e.getId(), e.getType(), e.getReference(), e.getMontantAttendu(), e.getMontantRecu(),
                e.getTransactionId(), e.isTraite(), e.getCommentaire());
    }
}
