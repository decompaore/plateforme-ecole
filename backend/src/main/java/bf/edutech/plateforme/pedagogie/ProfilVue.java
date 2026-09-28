package bf.edutech.plateforme.pedagogie;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue publique d'un profil pédagogique, utilisée par les autres modules
 * (ils ne manipulent jamais l'entité directement).
 */
public record ProfilVue(
        UUID id,
        String code,
        String libelle,
        OrdreEnseignement ordre,
        CodeModele modele,
        Decoupage decoupage,
        String gabaritDocument,
        BigDecimal seuilAdmission,
        short decimalesMoyenne,
        BigDecimal noteEliminatoire,
        BigDecimal seuilMaitrise,
        Vocabulaire vocabulaire,
        boolean actif) {

    /** Mots employés par l'interface pour ce profil. */
    public record Vocabulaire(String apprenant, String groupe, String matiere, String enseignant) {
    }

    /** Vrai si les matières d'une classe de ce profil doivent être rangées par groupe. */
    public boolean exigeGroupesDeMatieres() {
        return modele == CodeModele.NOTES_PAR_GROUPES;
    }
}
