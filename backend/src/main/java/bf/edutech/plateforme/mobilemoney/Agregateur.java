package bf.edutech.plateforme.mobilemoney;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpHeaders;

/**
 * Contrat d'un agrégateur Mobile Money (CinetPay, Ligdicash, PayDunya… ou le simulateur).
 * Chaque école utilise son propre compte marchand : les identifiants sont passés à chaque appel.
 * Les appels réseau peuvent échouer : {@link AgregateurIndisponibleException}.
 */
interface Agregateur {

    record Identifiants(String identifiantMarchand, String cleApi, String secretWebhook) {
    }

    record Demande(String reference, long montant, Operateur operateur, String telephone, String description) {
    }

    enum Etat {
        EN_ATTENTE,
        CONFIRMEE,
        ECHOUEE
    }

    /** État d'une transaction chez l'agrégateur ; {@code montant} : montant réellement reçu si confirmée. */
    record EtatDistant(Etat etat, Long montant, String referenceAgregateur, String message) {
    }

    /** Ligne du relevé quotidien (transactions réussies). */
    record LigneReleve(String reference, String referenceAgregateur, long montant) {
    }

    /** Code enregistré dans la configuration de l'école (ex. SIMULATEUR, CINETPAY). */
    String code();

    /** Demande le paiement ; le parent confirme ensuite sur son téléphone. */
    EtatDistant initier(Identifiants identifiants, Demande demande);

    /** Statut réel d'une transaction (on ne se fie jamais au seul contenu d'une notification). */
    EtatDistant consulter(Identifiants identifiants, String reference);

    /** Transactions réussies d'une journée. */
    List<LigneReleve> releve(Identifiants identifiants, LocalDate jour);

    /** La notification reçue vient-elle bien de l'agrégateur ? */
    boolean signatureValide(Identifiants identifiants, byte[] corps, HttpHeaders entetes);

    /** Notre référence de transaction, lue dans la notification. */
    String referenceNotifiee(byte[] corps);
}
