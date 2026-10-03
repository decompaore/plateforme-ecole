package bf.edutech.plateforme.notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.telephone.NumeroTelephone;

/**
 * Passerelle provisoire : écrit les SMS dans le journal (numéro masqué) et garde
 * les derniers messages en mémoire pour les tests. À remplacer par le
 * fournisseur retenu (même interface).
 */
@Component
public class PasserelleSmsJournal implements PasserelleSms {

    private static final Logger LOG = LoggerFactory.getLogger(PasserelleSmsJournal.class);
    private static final int MEMOIRE = 200;

    /** Message envoyé (conservé en mémoire, pour les tests et le développement). */
    public record SmsEnvoye(String telephone, String message) {
    }

    private final List<SmsEnvoye> derniers = new ArrayList<>();

    @Override
    public synchronized String envoyer(String telephone, String message) {
        LOG.info("SMS vers {} : {}", NumeroTelephone.masquer(telephone), message);
        derniers.add(new SmsEnvoye(telephone, message));
        if (derniers.size() > MEMOIRE) {
            derniers.remove(0);
        }
        return "JOURNAL-" + UUID.randomUUID();
    }

    public synchronized List<SmsEnvoye> derniers() {
        return List.copyOf(derniers);
    }
}
