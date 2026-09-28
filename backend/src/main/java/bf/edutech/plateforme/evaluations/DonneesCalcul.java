package bf.edutech.plateforme.evaluations;

import java.util.List;

import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.pedagogie.ProfilVue;

/** Tout ce qu'une stratégie de calcul utilise, chargé une seule fois pour la classe et la période. */
record DonneesCalcul(ProfilVue profil, List<MatiereDeClasseVue> matieres, List<InscriptionVue> eleves,
        List<Evaluation> evaluations, List<Note> notes, List<Competence> competences,
        List<ResultatCompetence> resultatsCompetences) {
}
