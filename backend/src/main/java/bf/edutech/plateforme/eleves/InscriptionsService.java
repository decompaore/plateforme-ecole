package bf.edutech.plateforme.eleves;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.eleves.Vues.NonReinscrit;
import bf.edutech.plateforme.eleves.Vues.ResultatReinscription;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Inscriptions : inscription dans une classe, changement de classe, statut de
 * bourse, sortie (transfert, abandon) et réinscription d'une année sur l'autre.
 */
@Service
public class InscriptionsService {

    private final InscriptionRepository inscriptions;
    private final EleveRepository eleves;
    private final RegistreInscriptions registre;
    private final AuditService audit;

    InscriptionsService(InscriptionRepository inscriptions, EleveRepository eleves, RegistreInscriptions registre,
            AuditService audit) {
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.registre = registre;
        this.audit = audit;
    }

    @Transactional
    public InscriptionVue inscrire(UUID eleveId, UUID classeId, boolean redoublant, StatutBourse statutBourse) {
        Eleve eleve = chargerEleve(eleveId);
        ClasseVue classe = registre.classe(classeId);
        AnneeVue annee = registre.anneeOuverte(classe.anneeId());
        Inscription inscription = registre.inscrire(eleve.getId(), classe, redoublant, statutBourse);
        audit.enregistrer("ELEVE_INSCRIT", eleve.getMatricule(),
                Map.of("annee", annee.libelle(), "classe", classe.code()));
        return InscriptionVue.depuis(inscription, eleve, annee.libelle(), classe.code());
    }

    @Transactional(readOnly = true)
    public InscriptionVue trouver(UUID id) {
        return vue(charger(id));
    }

    /** Liste de la classe par ordre alphabétique ; les élèves sortis sont inclus sur demande. */
    @Transactional(readOnly = true)
    public List<InscriptionVue> listerParClasse(UUID classeId, boolean avecSorties) {
        ClasseVue classe = registre.classe(classeId);
        String annee = registre.libelles().annee(classe.anneeId()).libelle();
        return inscriptions.listerAvecEleves(classeId).stream()
                .filter(ligne -> avecSorties || ((Inscription) ligne[0]).estActive())
                .map(ligne -> InscriptionVue.depuis((Inscription) ligne[0], (Eleve) ligne[1], annee, classe.code()))
                .toList();
    }

    /** Change de classe dans la même année (effectif de la classe d'arrivée contrôlé). */
    @Transactional
    public InscriptionVue changerClasse(UUID id, UUID classeId) {
        Inscription inscription = chargerActive(id);
        ClasseVue cible = registre.classe(classeId);
        if (!cible.anneeId().equals(inscription.getAnneeId())) {
            throw new RegleMetierException("CLASSE_AUTRE_ANNEE", "La classe " + cible.code()
                    + " n'appartient pas à l'année de l'inscription : utilisez la réinscription");
        }
        registre.anneeOuverte(inscription.getAnneeId());
        if (!cible.id().equals(inscription.getClasseId())) {
            registre.verifierPlace(cible);
            String ancienne = registre.classe(inscription.getClasseId()).code();
            inscription.changerClasse(cible.id());
            audit.enregistrer("CHANGEMENT_CLASSE", matricule(inscription), Map.of("de", ancienne, "vers", cible.code()));
        }
        return vue(inscription);
    }

    @Transactional
    public InscriptionVue changerStatutBourse(UUID id, StatutBourse statut) {
        Inscription inscription = charger(id);
        registre.anneeOuverte(inscription.getAnneeId());
        if (inscription.getStatutBourse() != statut) {
            audit.enregistrer("BOURSE_MODIFIEE", matricule(inscription),
                    Map.of("ancien", inscription.getStatutBourse().name(), "nouveau", statut.name()));
            inscription.changerStatutBourse(statut);
        }
        return vue(inscription);
    }

    /** Sortie de l'élève en cours d'année : transfert vers un autre établissement ou abandon. */
    @Transactional
    public InscriptionVue sortir(UUID id, StatutInscription statut, LocalDate date, String motif) {
        Inscription inscription = chargerActive(id);
        registre.anneeOuverte(inscription.getAnneeId());
        inscription.sortir(statut, date, RegistreEleves.facultatif(motif, "Le motif", 200));
        audit.enregistrer("ELEVE_SORTI", matricule(inscription), Map.of("statut", statut.name(), "date", date));
        return vue(inscription);
    }

    /**
     * Réinscrit dans une classe de l'année suivante des élèves inscrits une année
     * précédente. Les élèves qui ne peuvent pas l'être sont signalés un par un,
     * sans bloquer les autres. Le statut de bourse est remis à « non boursier »
     * sauf demande contraire (il se renouvelle chaque année).
     */
    @Transactional
    public ResultatReinscription reinscrire(UUID classeCibleId, List<UUID> inscriptionIds, boolean redoublant,
            boolean conserverStatutBourse) {
        ClasseVue cible = registre.classe(classeCibleId);
        AnneeVue anneeCible = registre.anneeOuverte(cible.anneeId());
        Libelles libelles = registre.libelles();
        long places = registre.placesRestantes(cible);
        int reinscrits = 0;
        List<NonReinscrit> nonReinscrits = new ArrayList<>();

        for (UUID sourceId : new LinkedHashSet<>(inscriptionIds)) {
            Inscription source = inscriptions.findById(sourceId).orElse(null);
            if (source == null) {
                nonReinscrits.add(new NonReinscrit(sourceId, null, "Inscription introuvable"));
                continue;
            }
            Eleve eleve = eleves.findById(source.getEleveId()).orElseThrow();
            String motif = null;
            if (!libelles.annee(source.getAnneeId()).debut().isBefore(anneeCible.debut())) {
                motif = "L'inscription d'origine doit concerner une année antérieure à " + anneeCible.libelle();
            } else if (!source.estActive()) {
                motif = "Élève sorti de l'établissement (" + source.getStatut().name().toLowerCase() + ")";
            } else if (inscriptions.existsByEleveIdAndAnneeId(eleve.getId(), cible.anneeId())) {
                motif = "Déjà inscrit pour " + anneeCible.libelle();
            } else if (places <= 0) {
                motif = "Classe " + cible.code() + " complète";
            }
            if (motif != null) {
                nonReinscrits.add(new NonReinscrit(sourceId, eleve.nomComplet(), motif));
                continue;
            }
            registre.creer(eleve.getId(), cible, redoublant,
                    conserverStatutBourse ? source.getStatutBourse() : StatutBourse.NON_BOURSIER, source.getId());
            places--;
            reinscrits++;
        }
        audit.enregistrer("REINSCRIPTIONS", anneeCible.libelle() + " / " + cible.code(),
                Map.of("reinscrits", reinscrits, "nonReinscrits", nonReinscrits.size()));
        return new ResultatReinscription(reinscrits, nonReinscrits);
    }

    private Inscription charger(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return inscriptions.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Inscription introuvable"));
    }

    private Inscription chargerActive(UUID id) {
        Inscription inscription = charger(id);
        if (!inscription.estActive()) {
            throw new RegleMetierException("INSCRIPTION_TERMINEE", "L'élève a quitté l'établissement ("
                    + inscription.getStatut().name().toLowerCase() + ") : inscription non modifiable");
        }
        return inscription;
    }

    private Eleve chargerEleve(UUID id) {
        UtilisateurConnecte.etablissementActif();
        return eleves.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Élève introuvable"));
    }

    private String matricule(Inscription inscription) {
        return eleves.findById(inscription.getEleveId()).map(Eleve::getMatricule).orElse("?");
    }

    private InscriptionVue vue(Inscription inscription) {
        Libelles libelles = registre.libelles();
        Eleve eleve = eleves.findById(inscription.getEleveId()).orElseThrow();
        return InscriptionVue.depuis(inscription, eleve, libelles.annee(inscription.getAnneeId()).libelle(),
                libelles.classe(inscription.getClasseId()).code());
    }
}
