package bf.edutech.plateforme.evaluations;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Qui saisit les notes d'une matière et qui consulte les résultats d'une classe. */
@Component
class AutorisationsEvaluations {

    /** Direction des études : saisit et corrige dans toutes les matières. */
    static final String[] DIRECTION = { "ADMIN_ECOLE", "CENSEUR" };

    /** Consultent les résultats de toutes les classes. */
    static final String[] CONSULTATION = { "ADMIN_ECOLE", "CENSEUR", "SECRETARIAT" };

    private final EnseignantsService enseignants;

    AutorisationsEvaluations(EnseignantsService enseignants) {
        this.enseignants = enseignants;
    }

    /** L'enseignant ne saisit que dans les matières qui lui sont affectées dans la classe. */
    void verifierSaisie(ClasseVue classe, MatiereDeClasseVue matiere) {
        if (UtilisateurConnecte.aUnRole(DIRECTION)) {
            return;
        }
        Optional<UUID> engagement = UtilisateurConnecte.idSiConnecte().flatMap(enseignants::engagementActif);
        if (engagement.isEmpty() || !engagement.get().equals(matiere.engagementId())) {
            throw new AccesRefuseException("Vous n'enseignez pas " + matiere.matiereLibelle() + " en " + classe.code());
        }
    }

    /** Résultats d'une classe : direction, secrétariat, ou enseignant de la classe (conseil de classe). */
    void verifierConsultation(UUID classeId) {
        if (UtilisateurConnecte.aUnRole(CONSULTATION)) {
            return;
        }
        if (UtilisateurConnecte.idSiConnecte().map(id -> enseignants.enseigneDans(id, classeId)).orElse(false)) {
            return;
        }
        throw new AccesRefuseException("Vous n'enseignez pas dans cette classe");
    }
}
