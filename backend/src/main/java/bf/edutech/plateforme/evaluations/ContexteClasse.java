package bf.edutech.plateforme.evaluations;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;

/**
 * Classe, profil pédagogique, période et matières d'une saisie ou d'un calcul,
 * avec les règles communes : la période appartient à l'année et au profil de
 * la classe ; une saisie exige l'année en cours et une période non verrouillée.
 * <p>
 * Composant sans proxy transactionnel : s'exécute dans la transaction de l'appelant.
 */
@Component
class ContexteClasse {

    record Contexte(ClasseVue classe, ProfilVue profil, PeriodeVue periode, AnneeVue annee,
            List<MatiereDeClasseVue> matieres) {

        MatiereDeClasseVue matiere(UUID matiereId) {
            return matieres.stream().filter(m -> m.matiereId().equals(matiereId)).findFirst()
                    .orElseThrow(() -> new RegleMetierException("MATIERE_HORS_CLASSE",
                            "Cette matière n'est pas enseignée dans la classe " + classe.code()));
        }

        static boolean estModule(MatiereDeClasseVue m) {
            return m.type() == TypeMatiere.MODULE_COMPETENCES;
        }
    }

    private final ClassesService classes;
    private final PeriodesService periodes;
    private final AnneesService annees;
    private final ProfilsService profils;

    ContexteClasse(ClassesService classes, PeriodesService periodes, AnneesService annees, ProfilsService profils) {
        this.classes = classes;
        this.periodes = periodes;
        this.annees = annees;
        this.profils = profils;
    }

    Contexte pourLecture(UUID classeId, UUID periodeId) {
        ClasseVue classe = classes.trouver(classeId);
        PeriodeVue periode = periodes.trouver(periodeId);
        if (!periode.anneeId().equals(classe.anneeId()) || !periode.profilId().equals(classe.profilId())) {
            throw new RegleMetierException("PERIODE_HORS_CLASSE",
                    "La période « " + periode.libelle() + " » ne concerne pas la classe " + classe.code());
        }
        if (classe.profilId() == null) {
            throw new RessourceIntrouvableException("Profil pédagogique de la classe introuvable");
        }
        return new Contexte(classe, profils.trouver(classe.profilId()), periode, annees.trouver(classe.anneeId()),
                classes.matieres(classeId));
    }

    /** Contexte d'une saisie : année en cours, période non verrouillée. */
    Contexte pourSaisie(UUID classeId, UUID periodeId) {
        Contexte c = pourLecture(classeId, periodeId);
        if (c.annee().etat() != EtatAnnee.ACTIVE) {
            throw new RegleMetierException("ANNEE_NON_ACTIVE", "L'année " + c.annee().libelle() + " n'est pas en cours");
        }
        if (c.periode().verrouillee()) {
            throw new RegleMetierException("PERIODE_VERROUILLEE",
                    "La période « " + c.periode().libelle() + " » est verrouillée : les saisies sont closes");
        }
        return c;
    }
}
