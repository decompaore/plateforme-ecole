package bf.edutech.plateforme.notifications;

/**
 * Passerelle d'envoi de SMS (fournisseur local ou agrégateur). L'implémentation
 * réelle sera branchée lors du choix du fournisseur ; en attendant,
 * {@link PasserelleSmsJournal} journalise les messages.
 */
public interface PasserelleSms {

    /**
     * Envoie le message et renvoie la référence du fournisseur.
     *
     * @throws RuntimeException si le fournisseur refuse ou ne répond pas (nouvelle tentative plus tard)
     */
    String envoyer(String telephone, String message);
}
