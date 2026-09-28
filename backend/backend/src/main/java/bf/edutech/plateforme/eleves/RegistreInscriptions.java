package bf.edutech.plateforme.eleves;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;

/**
 * Règles d'inscription partagées : année ouverte aux inscriptions, une seule
 * inscription par élève et par année, effectif maximal de la classe.
 * <p>
 * Composant sans proxy transactionnel : il s'exécute dans la transaction du
 * service appelant.
 */
@Component
class RegistreInscriptions {

    private final InscriptionRepository inscriptions;
    private final AnneesService annees;
    private final ClassesService classes;
    private final Compteurs compteurs;
    private final Clock horloge;

    RegistreInscriptions(InscriptionRepository inscriptions, AnneesService annees, ClassesService classes,
            Compteurs compteurs, Clock horloge) {
        this.inscriptions = inscriptions;
        this.annees = annees;
        this.classes = classes;
        this.compteurs = compteurs;
        this.horloge = horloge;
    }

    Libelles libelles() {
        return new Libelles(annees, classes);
    }

    ClasseVue classe(UUID classeId) {
        return classes.trouver(classeId);
    }

    /** Les inscriptions ne sont possibles que pendant la préparation ou le déroulement de l'année. */
    AnneeVue anneeOuverte(UUID anneeId) {
        AnneeVue annee = annees.trouver(anneeId);
        if (annee.etat() != EtatAnnee.PREPARATION && annee.etat() != EtatAnnee.ACTIVE) {
            throw new RegleMetierException("ANNEE_FIGEE", "L'année " + annee.libelle() + " est "
                    + annee.etat().name().toLowerCase() + " : les inscriptions n'y sont plus modifiables");
        }
        return annee;
    }

    /**
     * Places restantes dans la classe. Pose un verrou jusqu'à la fin de la
     * transaction : deux inscriptions simultanées ne peuvent pas dépasser l'effectif.
     */
    long placesRestantes(ClasseVue classe) {
        if (classe.effectifMax() == null) {
            return Long.MAX_VALUE;
        }
        compteurs.verrouiller("classe:" + classe.id());
        return classe.effectifMax() - inscriptions.countByClasseIdAndStatut(classe.id(), StatutInscription.ACTIVE);
    }

    void verifierPlace(ClasseVue classe) {
        if (placesRestantes(classe) <= 0) {
            throw new RegleMetierException("CLASSE_COMPLETE",
                    "La classe " + classe.code() + " est complète (" + classe.effectifMax() + " places)");
        }
    }

    /** Inscription avec contrôles (doublon sur l'année, effectif). */
    Inscription inscrire(UUID eleveId, ClasseVue classe, boolean redoublant, StatutBourse bourse) {
        if (inscriptions.existsByEleveIdAndAnneeId(eleveId, classe.anneeId())) {
            throw new RegleMetierException("DEJA_INSCRIT", "L'élève est déjà inscrit pour cette année scolaire");
        }
        verifierPlace(classe);
        return creer(eleveId, classe, redoublant, bourse, null);
    }

    /** Création sans contrôle : l'appelant a déjà vérifié les règles. */
    Inscription creer(UUID eleveId, ClasseVue classe, boolean redoublant, StatutBourse bourse, UUID precedente) {
        return inscriptions.save(new Inscription(eleveId, classe.anneeId(), classe.id(), redoublant, bourse,
                LocalDate.now(horloge), precedente));
    }
}
