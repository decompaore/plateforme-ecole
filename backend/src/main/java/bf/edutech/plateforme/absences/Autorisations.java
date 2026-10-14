package bf.edutech.plateforme.absences;

import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Qui peut faire l'appel d'une classe, ou consulter ses absences. */
@Component
class Autorisations {

    /** Vie scolaire (v0.47 : le CPE, rôle SURVEILLANT) : fait l'appel de toute classe et le modifie à tout moment. */
    static final String[] VIE_SCOLAIRE = { "SURVEILLANT" };

    /** Personnel qui consulte les absences de toutes les classes. */
    static final String[] PERSONNEL = { "ADMIN_ECOLE", "CENSEUR", "CHEF_TRAVAUX", "SURVEILLANT", "SECRETARIAT", "INTENDANT" };

    private final EnseignantsService enseignants;

    Autorisations(EnseignantsService enseignants) {
        this.enseignants = enseignants;
    }

    boolean vieScolaire() {
        return UtilisateurConnecte.aUnRole(VIE_SCOLAIRE);
    }

    /** Enseignant affecté à la classe (au moins une matière). */
    boolean enseigneDans(UUID classeId) {
        return enseignants.enseigneDans(UtilisateurConnecte.id(), classeId);
    }

    /** Consultation des appels ou absences d'une classe : personnel, ou enseignant de la classe. */
    void verifierConsultationClasse(UUID classeId) {
        if (!UtilisateurConnecte.aUnRole(PERSONNEL) && !enseigneDans(classeId)) {
            throw new AccesRefuseException("Vous n'enseignez pas dans cette classe");
        }
    }
}
