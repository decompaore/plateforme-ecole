package bf.edutech.plateforme.progression;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.progression.Vues.Domaine;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Qui peut quoi sur une matière d'une classe : son enseignant (auteur de la progression et du
 * cahier de textes) et la direction qui supervise son domaine.
 */
@Component
class AccesMatiere {

    record Contexte(ClasseVue classe, MatiereDeClasseVue matiere, Domaine domaine, boolean auteur,
            boolean superviseur) {

        void exigerAuteur(String quoi) {
            if (!auteur) {
                throw new AccesRefuseException("Seul l'enseignant de " + matiere.matiereLibelle() + " en "
                        + classe.code() + " " + quoi);
            }
        }

        void exigerLecture() {
            if (!auteur && !superviseur) {
                throw new AccesRefuseException("Vous n'enseignez pas " + matiere.matiereLibelle() + " en "
                        + classe.code() + " et ne supervisez pas cette matière");
            }
        }
    }

    private final ClassesService classes;
    private final EnseignantsService enseignants;
    private final Supervision supervision;

    AccesMatiere(ClassesService classes, EnseignantsService enseignants, Supervision supervision) {
        this.classes = classes;
        this.enseignants = enseignants;
        this.supervision = supervision;
    }

    Contexte contexte(UUID classeId, UUID matiereId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        MatiereDeClasseVue matiere = classes.matieres(classeId).stream()
                .filter(m -> m.matiereId().equals(matiereId)).findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Matière absente du programme de " + classe.code()));
        Domaine domaine = Domaine.de(matiere.type());
        boolean auteur = matiere.engagementId() != null
                && monEngagement().map(e -> e.equals(matiere.engagementId())).orElse(false);
        return new Contexte(classe, matiere, domaine, auteur, supervision.domaines().contains(domaine));
    }

    Optional<UUID> monEngagement() {
        return UtilisateurConnecte.idSiConnecte().flatMap(enseignants::engagementActif);
    }
}
