package bf.edutech.plateforme.eleves;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Élève et contact prioritaire (destinataire des SMS) pour des inscriptions :
 * utilisé par les modules qui préviennent les familles (absences, vie scolaire).
 */
@Service
public class ContactsEleves {

    /** L'élève d'une inscription et son contact prioritaire ({@code telephone} null s'il n'en a pas). */
    public record ContactEleve(UUID inscriptionId, UUID eleveId, String nom, String prenoms, Sexe sexe,
            String telephone, String langueSms) {
    }

    private final InscriptionRepository inscriptions;
    private final EleveRepository eleves;
    private final LienResponsableEleveRepository liens;
    private final ResponsableRepository responsables;

    ContactsEleves(InscriptionRepository inscriptions, EleveRepository eleves, LienResponsableEleveRepository liens,
            ResponsableRepository responsables) {
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.liens = liens;
        this.responsables = responsables;
    }

    @Transactional(readOnly = true)
    public Map<UUID, ContactEleve> pourInscriptions(Collection<UUID> inscriptionIds) {
        UtilisateurConnecte.etablissementActif();
        Map<UUID, ContactEleve> contacts = new HashMap<>();
        if (inscriptionIds.isEmpty()) {
            return contacts;
        }
        List<Inscription> liste = inscriptions.findAllById(inscriptionIds);
        Map<UUID, Eleve> parEleve = eleves.findByIdIn(liste.stream().map(Inscription::getEleveId).toList()).stream()
                .collect(Collectors.toMap(Eleve::getId, Function.identity()));
        for (Inscription inscription : liste) {
            Eleve eleve = parEleve.get(inscription.getEleveId());
            Responsable contact = liens.findByEleveId(eleve.getId()).stream()
                    .filter(LienResponsableEleve::isContactPrioritaire)
                    .findFirst()
                    .flatMap(l -> responsables.findById(l.getResponsableId()))
                    .orElse(null);
            contacts.put(inscription.getId(), new ContactEleve(inscription.getId(), eleve.getId(), eleve.getNom(),
                    eleve.getPrenoms(), eleve.getSexe(), contact != null ? contact.getTelephone() : null,
                    contact != null ? contact.getLangueSms().name() : null));
        }
        return contacts;
    }
}
