package bf.edutech.plateforme.etablissement;

import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Chargement des années pour les services du module, avec la règle commune :
 * une année clôturée ou archivée ne se modifie plus.
 * <p>
 * Composant simple (sans proxy transactionnel) : il s'exécute dans la
 * transaction du service appelant.
 */
@Component
class AccesAnnees {

    private final AnneeScolaireRepository annees;

    AccesAnnees(AnneeScolaireRepository annees) {
        this.annees = annees;
    }

    AnneeScolaire charger(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return annees.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Année scolaire introuvable"));
    }

    AnneeScolaire chargerModifiable(UUID id) {
        AnneeScolaire annee = charger(id);
        if (!annee.estModifiable()) {
            throw new RegleMetierException("ANNEE_FIGEE", "L'année " + annee.getLibelle() + " est "
                    + annee.getEtat().name().toLowerCase() + " : elle ne peut plus être modifiée");
        }
        return annee;
    }
}
