package bf.edutech.plateforme.progression;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.progression.Vues.Domaine;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Qui suit et vise les progressions : l'administration partout, le censeur les matières
 * générales, le chef des travaux les matières techniques et pratiques. Sans chef des travaux
 * dans l'établissement (lycée d'enseignement général, petite école), le censeur suit tout.
 */
@Component
class Supervision {

    static final String[] ROLES = { "ADMIN_ECOLE", "CENSEUR", "CHEF_TRAVAUX" };

    private final MembresService membres;

    Supervision(MembresService membres) {
        this.membres = membres;
    }

    Set<Domaine> domaines() {
        Set<Domaine> d = EnumSet.noneOf(Domaine.class);
        if (UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            return EnumSet.allOf(Domaine.class);
        }
        if (UtilisateurConnecte.aUnRole("CHEF_TRAVAUX")) {
            d.add(Domaine.TECHNIQUE);
        }
        if (UtilisateurConnecte.aUnRole("CENSEUR")) {
            d.add(Domaine.GENERAL);
            if (!chefDesTravauxEnFonction()) {
                d.add(Domaine.TECHNIQUE);
            }
        }
        return d;
    }

    private boolean chefDesTravauxEnFonction() {
        return membres.lister().stream().anyMatch(m -> m.actif() && m.role() == Role.CHEF_TRAVAUX);
    }
}
