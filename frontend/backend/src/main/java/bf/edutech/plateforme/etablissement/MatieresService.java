package bf.edutech.plateforme.etablissement;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.etablissement.Vues.MatiereVue;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Catalogue des matières et modules de l'établissement (commun à toutes les années). */
@Service
public class MatieresService {

    private final MatiereRepository matieres;

    MatieresService(MatiereRepository matieres) {
        this.matieres = matieres;
    }

    @Transactional(readOnly = true)
    public List<MatiereVue> lister() {
        UtilisateurConnecte.etablissementActif();
        return matieres.findAllByOrderByLibelleAsc().stream().map(MatiereVue::depuis).toList();
    }

    @Transactional
    public MatiereVue creer(String codeSaisi, String libelle, TypeMatiere type) {
        UtilisateurConnecte.etablissementActif();
        String code = codeSaisi.trim().toUpperCase(Locale.ROOT);
        if (matieres.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Une matière porte déjà le code " + code);
        }
        return MatiereVue.depuis(matieres.save(new Matiere(code, libelle.trim(), type)));
    }

    /** Une matière n'est jamais supprimée : on la désactive (elle reste dans l'historique). */
    @Transactional
    public MatiereVue modifier(UUID id, String libelle, TypeMatiere type, boolean actif) {
        UtilisateurConnecte.etablissementActif();
        Matiere matiere = matieres.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Matière introuvable"));
        matiere.modifier(libelle.trim(), type, actif);
        return MatiereVue.depuis(matiere);
    }
}
