package bf.edutech.plateforme.eleves;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Objets de lecture exposés par l'API du module Élèves et inscriptions. */
public final class Vues {

    private Vues() {
    }

    public record EleveVue(UUID id, String matricule, String nom, String prenoms, Sexe sexe,
            LocalDate dateNaissance, String lieuNaissance, String telephone, String identifiantNational,
            String adresse) {

        static EleveVue depuis(Eleve e) {
            return new EleveVue(e.getId(), e.getMatricule(), e.getNom(), e.getPrenoms(), e.getSexe(),
                    e.getDateNaissance(), e.getLieuNaissance(), e.getTelephone(), e.getIdentifiantNational(),
                    e.getAdresse());
        }
    }

    /** Responsable vu depuis le dossier d'un élève. */
    public record ResponsableDeEleveVue(UUID responsableId, String nom, String prenoms, String telephone,
            String profession, LangueSms langueSms, LienParente lien, boolean responsableLegal,
            boolean contactPrioritaire, boolean espaceParentOuvert) {

        static ResponsableDeEleveVue depuis(LienResponsableEleve l, Responsable r) {
            return new ResponsableDeEleveVue(r.getId(), r.getNom(), r.getPrenoms(), r.getTelephone(),
                    r.getProfession(), r.getLangueSms(), l.getLien(), l.isResponsableLegal(),
                    l.isContactPrioritaire(), r.getUtilisateurId() != null);
        }
    }

    public record InscriptionVue(UUID id, UUID eleveId, String matricule, String nom, String prenoms, Sexe sexe,
            LocalDate dateNaissance, UUID anneeId, String anneeLibelle, UUID classeId, String classeCode,
            StatutInscription statut, boolean redoublant, StatutBourse statutBourse, LocalDate inscritLe,
            LocalDate dateSortie, String motifSortie) {

        static InscriptionVue depuis(Inscription i, Eleve e, String anneeLibelle, String classeCode) {
            return new InscriptionVue(i.getId(), e.getId(), e.getMatricule(), e.getNom(), e.getPrenoms(),
                    e.getSexe(), e.getDateNaissance(), i.getAnneeId(), anneeLibelle, i.getClasseId(), classeCode,
                    i.getStatut(), i.isRedoublant(), i.getStatutBourse(), i.getInscritLe(), i.getDateSortie(),
                    i.getMotifSortie());
        }
    }

    /** Dossier complet : identité, responsables et parcours (inscriptions, la plus récente en premier). */
    public record DossierEleveVue(EleveVue eleve, List<ResponsableDeEleveVue> responsables,
            List<InscriptionVue> inscriptions) {
    }

    /** Élève non réinscrit, avec la raison. */
    public record NonReinscrit(UUID inscriptionId, String nomComplet, String motif) {
    }

    public record ResultatReinscription(int reinscrits, List<NonReinscrit> nonReinscrits) {
    }

    /**
     * Ouverture de l'espace parent. Le mot de passe temporaire n'est renvoyé
     * qu'une fois, et seulement si le compte vient d'être créé.
     */
    public record EspaceParentVue(UUID responsableId, UUID utilisateurId, String telephone,
            String motDePasseTemporaire) {
    }

    /** Enfant vu depuis l'espace parent, avec sa situation la plus récente. */
    public record EnfantVue(UUID eleveId, String matricule, String nom, String prenoms, Sexe sexe,
            LocalDate dateNaissance, LienParente lien, String anneeLibelle, String classeCode,
            StatutInscription statut) {
    }
}
