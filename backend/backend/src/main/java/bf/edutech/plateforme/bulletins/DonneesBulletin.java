package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import bf.edutech.plateforme.bulletins.ParametresBulletinsService.ParametresBulletins;
import bf.edutech.plateforme.pedagogie.CodeModele;

/** Tout ce qu'affiche un bulletin : de quoi produire le PDF sans autre accès aux données. */
record DonneesBulletin(ParametresBulletins parametres, String etablissement, String annee, String periode,
        String classe, CodeModele modele, String libelleApprenant, int effectif,
        String nom, String prenoms, String matricule, LocalDate dateNaissance, boolean redoublant,
        BigDecimal moyenne, Integer rang, boolean admis, BigDecimal tauxMaitrise, List<LigneBulletin> lignes,
        BigDecimal moyenneClasse, BigDecimal plusForte, BigDecimal plusFaible,
        BigDecimal heuresAbsence, BigDecimal heuresNonJustifiees, int retards,
        Distinction distinction, String appreciationGenerale, String codeVerification, Instant genereLe) {
}
