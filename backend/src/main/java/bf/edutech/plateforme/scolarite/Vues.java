package bf.edutech.plateforme.scolarite;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import bf.edutech.plateforme.eleves.StatutBourse;

/** Objets échangés par l'API du module Scolarité (montants en FCFA). */
public final class Vues {

    private Vues() {
    }

    public record OrganismeVue(UUID id, String nom, TypeOrganisme type, String telephone, boolean actif) {
    }

    public record TrancheSaisie(LocalDate dateLimite, Long montant) {
    }

    public record TrancheVue(int numero, LocalDate dateLimite, long montant) {
    }

    /** Frais saisi : les cibles correspondent à la portée (aucune pour TOUTES). */
    public record DonneesFrais(String libelle, Long montant, Boolean obligatoire, Boolean couvertParBourse,
            Portee portee, List<UUID> filieres, List<String> niveaux, List<UUID> classes,
            List<TrancheSaisie> tranches) {

        public DonneesFrais {
            obligatoire = obligatoire == null || obligatoire;
            couvertParBourse = couvertParBourse == null || couvertParBourse;
            portee = portee == null ? Portee.TOUTES : portee;
            filieres = filieres == null ? List.of() : filieres;
            niveaux = niveaux == null ? List.of() : niveaux;
            classes = classes == null ? List.of() : classes;
            tranches = tranches == null ? List.of() : tranches;
        }
    }

    public record FraisVue(UUID id, UUID anneeId, String libelle, long montant, boolean obligatoire,
            boolean couvertParBourse, Portee portee, List<UUID> filieres, List<String> niveaux, List<UUID> classes,
            List<TrancheVue> tranches) {
    }

    public record PriseEnChargeVue(UUID inscriptionId, UUID organismeId, String organisme, BigDecimal taux,
            String referenceDecision, LocalDate dateDecision) {
    }

    public record ExonerationVue(UUID fraisId, String frais, long montant, String motif) {
    }

    public record EcheanceVue(UUID fraisId, String libelle, int numero, int nombreTranches, LocalDate dateLimite,
            long montant, long exoneration, long partOrganisme, long partFamille, long payeFamille,
            long payeOrganisme, long resteFamille, long resteOrganisme, boolean enRetard) {
    }

    public record PaiementVue(UUID id, UUID inscriptionId, long montant, MoyenPaiement moyen, Payeur payeur,
            UUID organismeId, String referenceExterne, String deposant, LocalDate datePaiement,
            Instant enregistreLe, String recuNumero, String recuCode, boolean annule, String motifAnnulation) {
    }

    /** Situation financière complète d'un élève pour une année. */
    public record SituationVue(UUID inscriptionId, UUID eleveId, String matricule, String nom, String prenoms,
            String classeCode, String annee, StatutBourse statutBourse, BigDecimal tauxPriseEnCharge,
            String organisme, long total, long exonere, long totalFamille, long totalOrganisme, long payeFamille,
            long payeOrganisme, long resteFamille, long resteOrganisme, long retardFamille, long retardOrganisme,
            long avanceFamille, LocalDate prochaineEcheance, List<EcheanceVue> echeances,
            List<ExonerationVue> exonerations, List<PaiementVue> paiements) {
    }

    /** Une ligne de l'état d'une classe. */
    public record SituationResumeVue(UUID inscriptionId, String matricule, String nom, String prenoms,
            StatutBourse statutBourse, long totalFamille, long payeFamille, long resteFamille, long retardFamille,
            long totalOrganisme, long payeOrganisme, long resteOrganisme, Instant derniereRelance) {
    }

    public record EtatClasseVue(UUID classeId, String classeCode, List<SituationResumeVue> eleves,
            long totalFamille, long payeFamille, long retardFamille, long totalOrganisme, long payeOrganisme,
            BigDecimal tauxRecouvrementFamille, BigDecimal tauxRecouvrementOrganisme) {
    }

    public record ResultatRelancesVue(int envoyees, int sansContact, int dejaRelancees) {
    }

    public record TotalMoyenVue(MoyenPaiement moyen, long montant, int nombre) {
    }

    /** Élève d'un paiement du journal, pour savoir qui a payé sans ouvrir chaque fiche. */
    public record EleveJournalVue(String matricule, String nom, String prenoms, String classeCode) {
    }

    /** Journal de caisse sur une période (paiements non annulés) ; {@code eleves} par inscription. */
    public record JournalVue(LocalDate du, LocalDate au, long total, List<TotalMoyenVue> parMoyen,
            List<PaiementVue> paiements, Map<UUID, EleveJournalVue> eleves) {
    }

    /** Réponse de la vérification publique : ce qui figure déjà sur le reçu papier. */
    public record VerificationRecuVue(String etablissement, String numero, String eleve, String matricule,
            long montant, Payeur payeur, LocalDate datePaiement, Instant emisLe, boolean annule) {
    }
}
