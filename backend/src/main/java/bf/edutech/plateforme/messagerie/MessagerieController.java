package bf.edutech.plateforme.messagerie;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.messagerie.MessagerieService.Apercu;
import bf.edutech.plateforme.messagerie.MessagerieService.Boite;
import bf.edutech.plateforme.messagerie.MessagerieService.Demande;
import bf.edutech.plateforme.messagerie.MessagerieService.DestinataireVue;
import bf.edutech.plateforme.messagerie.MessagerieService.Envoi;
import bf.edutech.plateforme.messagerie.MessagerieService.MessageEnvoye;
import bf.edutech.plateforme.messagerie.MessagerieService.MessageVue;
import bf.edutech.plateforme.messagerie.MessagerieService.Personne;

/** Messagerie de l'établissement (v0.46). */
@RestController
public class MessagerieController {

    private static final String CHEF = "hasRole('ADMIN_ECOLE')";

    private final MessagerieService messagerie;

    MessagerieController(MessagerieService messagerie) {
        this.messagerie = messagerie;
    }

    // ---------------- Boîte de réception (tout compte de l'établissement)

    @GetMapping("/api/v1/messages")
    public Boite boite(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int taille) {
        return messagerie.boite(page, taille);
    }

    @GetMapping("/api/v1/messages/non-lus")
    public Map<String, Integer> nonLus() {
        return Map.of("nombre", messagerie.nonLus());
    }

    @GetMapping("/api/v1/messages/{id}")
    public MessageVue lire(@PathVariable UUID id) {
        return messagerie.lire(id);
    }

    // ---------------- Envoi (chef d'établissement)

    @GetMapping("/api/v1/messages/annuaire")
    @PreAuthorize(CHEF)
    public List<Personne> annuaire() {
        return messagerie.annuaire();
    }

    /** Combien de personnes seront jointes, et combien de SMS partiront, sans rien envoyer. */
    @PostMapping("/api/v1/messages/apercu")
    @PreAuthorize(CHEF)
    public Apercu apercu(@RequestBody Demande d) {
        return messagerie.apercu(d);
    }

    /**
     * {"objet":"Réunion","corps":"…","sms":true,"groupes":["ENSEIGNANTS","PARENTS"],"roles":["SURVEILLANT"],
     * "classes":["…"],"utilisateurs":["…"],"responsables":["…"]}.
     */
    @PostMapping("/api/v1/messages")
    @PreAuthorize(CHEF)
    public Envoi envoyer(@RequestBody Demande d) {
        return messagerie.envoyer(d);
    }

    @GetMapping("/api/v1/messages/envoyes")
    @PreAuthorize(CHEF)
    public List<MessageEnvoye> envoyes() {
        return messagerie.envoyes();
    }

    @GetMapping("/api/v1/messages/{id}/destinataires")
    @PreAuthorize(CHEF)
    public List<DestinataireVue> destinataires(@PathVariable UUID id) {
        return messagerie.destinataires(id);
    }
}
