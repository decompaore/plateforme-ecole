package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.utilisateurs.MembreVue;
import bf.edutech.plateforme.utilisateurs.MembresService;

/** « NOM Prénoms » des comptes et des enseignants cités dans les vues. */
@Component
class Noms {

    private final MembresService membres;
    private final EnseignantsService enseignants;

    Noms(MembresService membres, EnseignantsService enseignants) {
        this.membres = membres;
        this.enseignants = enseignants;
    }

    Map<UUID, String> utilisateurs() {
        Map<UUID, String> noms = new HashMap<>();
        for (MembreVue m : membres.lister()) {
            noms.putIfAbsent(m.utilisateurId(), m.nom() + " " + m.prenoms());
        }
        return noms;
    }

    Map<UUID, String> engagements(Collection<UUID> engagementIds) {
        return enseignants.nomsParEngagement(engagementIds);
    }
}
