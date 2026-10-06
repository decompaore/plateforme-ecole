package bf.edutech.plateforme.emploidutemps;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.emploidutemps.Vues.Domaine;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Qui fait quoi dans l'emploi du temps : le censeur place les matières générales, le chef des
 * travaux les matières techniques, pratiques et les modules ; sans chef des travaux en
 * fonction, le censeur place tout. L'administrateur peut tout faire. La grille horaire et la
 * publication reviennent au censeur (et à l'administrateur).
 */
@Component
class Responsabilites {

    static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX','SURVEILLANT')";

    private final MembresService membres;

    Responsabilites(MembresService membres) {
        this.membres = membres;
    }

    Set<Domaine> domaines() {
        if (UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            return EnumSet.allOf(Domaine.class);
        }
        Set<Domaine> d = EnumSet.noneOf(Domaine.class);
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

    boolean grille() {
        return UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "CENSEUR");
    }

    static String responsable(Domaine d) {
        return d == Domaine.GENERAL ? "le censeur" : "le chef des travaux";
    }

    private boolean chefDesTravauxEnFonction() {
        return membres.lister().stream().anyMatch(m -> m.actif() && m.role() == Role.CHEF_TRAVAUX);
    }
}
