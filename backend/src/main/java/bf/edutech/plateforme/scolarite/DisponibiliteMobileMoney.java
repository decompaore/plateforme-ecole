package bf.edutech.plateforme.scolarite;

/**
 * Le paiement Mobile Money par les parents est-il possible dans l'établissement actif (compte
 * marchand configuré et actif) ? Fourni par le module Mobile Money, sans que la scolarité en
 * dépende.
 */
public interface DisponibiliteMobileMoney {

    boolean configure();
}
