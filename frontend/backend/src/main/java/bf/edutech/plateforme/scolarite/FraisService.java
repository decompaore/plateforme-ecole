package bf.edutech.plateforme.scolarite;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.scolarite.Vues.DonneesFrais;
import bf.edutech.plateforme.scolarite.Vues.FraisVue;
import bf.edutech.plateforme.scolarite.Vues.TrancheSaisie;
import bf.edutech.plateforme.scolarite.Vues.TrancheVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Frais d'une année : montant, tranches avec dates limites, portée (toute l'école,
 * des filières, des niveaux ou des classes, au choix de l'établissement),
 * frais facultatifs choisis élève par élève.
 */
@Service
public class FraisService {

    private static final long MONTANT_MAX = 100_000_000L;

    private final FraisRepository frais;
    private final FraisCibleRepository cibles;
    private final TrancheRepository tranches;
    private final SouscriptionRepository souscriptions;
    private final AnneesService annees;
    private final ClassesService classes;
    private final FilieresService filieres;
    private final InscriptionsService inscriptions;
    private final AuditService audit;
    private final Clock horloge;

    FraisService(FraisRepository frais, FraisCibleRepository cibles, TrancheRepository tranches,
            SouscriptionRepository souscriptions, AnneesService annees, ClassesService classes,
            FilieresService filieres, InscriptionsService inscriptions, AuditService audit, Clock horloge) {
        this.frais = frais;
        this.cibles = cibles;
        this.tranches = tranches;
        this.souscriptions = souscriptions;
        this.annees = annees;
        this.classes = classes;
        this.filieres = filieres;
        this.inscriptions = inscriptions;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<FraisVue> lister(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        annees.trouver(anneeId);
        List<FraisScolarite> liste = frais.findByAnneeIdOrderByLibelleAsc(anneeId);
        return vues(liste);
    }

    @Transactional
    public FraisVue creer(UUID anneeId, DonneesFrais d) {
        UtilisateurConnecte.etablissementActif();
        AnneeVue annee = anneeModifiable(anneeId);
        String libelle = libelle(d.libelle());
        if (frais.existsByAnneeIdAndLibelleIgnoreCase(anneeId, libelle)) {
            throw new RegleMetierException("FRAIS_EXISTANT", "Le frais « " + libelle + " » existe déjà pour "
                    + annee.libelle());
        }
        FraisScolarite f = new FraisScolarite(anneeId, horloge.instant());
        definir(f, annee, libelle, d);
        audit.enregistrer("FRAIS_CREE", libelle + " / " + annee.libelle(), Map.of("montant", f.getMontant()));
        return vues(List.of(f)).get(0);
    }

    /**
     * Reprend les frais d'une année dans une autre (passage à l'année suivante) : mêmes montants et portées,
     * dates limites décalées d'autant d'années, classes retrouvées par leur code. Un frais déjà présent (même
     * libellé) ou devenu impossible (plus aucune classe visée, date hors de l'année) n'est pas repris.
     *
     * @return nombre de frais repris
     */
    @Transactional
    public int copier(UUID anneeSourceId, UUID anneeCibleId) {
        UtilisateurConnecte.etablissementActif();
        AnneeVue source = annees.trouver(anneeSourceId);
        AnneeVue cible = anneeModifiable(anneeCibleId);
        long decalage = cible.debut().getYear() - source.debut().getYear();
        Map<String, UUID> classesCible = classes.lister(anneeCibleId).stream()
                .collect(Collectors.toMap(c -> c.code().toLowerCase(), ClasseVue::id, (a, b) -> a));
        int repris = 0;
        for (FraisVue f : vues(frais.findByAnneeIdOrderByLibelleAsc(anneeSourceId))) {
            if (frais.existsByAnneeIdAndLibelleIgnoreCase(anneeCibleId, f.libelle())) {
                continue;
            }
            List<UUID> classesVisees = f.classes().stream()
                    .map(id -> classesCible.get(classes.trouver(id).code().toLowerCase()))
                    .filter(Objects::nonNull).distinct().toList();
            if (f.portee() == Portee.CLASSES && classesVisees.isEmpty()) {
                continue;
            }
            List<TrancheSaisie> nouvelles = f.tranches().stream()
                    .map(t -> new TrancheSaisie(t.dateLimite().plusYears(decalage), t.montant())).toList();
            DonneesFrais donnees = new DonneesFrais(f.libelle(), f.montant(), f.obligatoire(), f.couvertParBourse(),
                    f.portee(), f.filieres(), f.niveaux(), classesVisees, nouvelles);
            try {
                FraisScolarite nouveau = new FraisScolarite(anneeCibleId, horloge.instant());
                definir(nouveau, cible, f.libelle(), donnees);  // valide avant toute écriture
                repris++;
            } catch (IllegalArgumentException | RegleMetierException | RessourceIntrouvableException e) {
                // frais non repris (dates hors de la nouvelle année…) : à recréer à la main
            }
        }
        audit.enregistrer("FRAIS_COPIES", source.libelle() + " → " + cible.libelle(), Map.of("frais", repris));
        return repris;
    }

    @Transactional
    public FraisVue modifier(UUID fraisId, DonneesFrais d) {
        FraisScolarite f = trouver(fraisId);
        AnneeVue annee = anneeModifiable(f.getAnneeId());
        String libelle = libelle(d.libelle());
        if (!libelle.equalsIgnoreCase(f.getLibelle()) && frais.existsByAnneeIdAndLibelleIgnoreCase(annee.id(), libelle)) {
            throw new RegleMetierException("FRAIS_EXISTANT", "Le frais « " + libelle + " » existe déjà pour "
                    + annee.libelle());
        }
        cibles.deleteByFraisId(fraisId);
        tranches.deleteByFraisId(fraisId);
        cibles.flush();
        tranches.flush();
        definir(f, annee, libelle, d);
        audit.enregistrer("FRAIS_MODIFIE", libelle + " / " + annee.libelle(), Map.of("montant", f.getMontant()));
        return vues(List.of(f)).get(0);
    }

    /** Supprime un frais (et ses tranches, souscriptions, exonérations). Les paiements restent acquis. */
    @Transactional
    public void supprimer(UUID fraisId) {
        FraisScolarite f = trouver(fraisId);
        AnneeVue annee = anneeModifiable(f.getAnneeId());
        frais.delete(f);
        audit.enregistrer("FRAIS_SUPPRIME", f.getLibelle() + " / " + annee.libelle(), null);
    }

    /** Un élève choisit un frais facultatif (cantine, transport…). */
    @Transactional
    public void souscrire(UUID inscriptionId, UUID fraisId) {
        FraisScolarite f = trouver(fraisId);
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        if (f.isObligatoire()) {
            throw new RegleMetierException("FRAIS_OBLIGATOIRE", "« " + f.getLibelle()
                    + " » est obligatoire : il s'applique sans souscription");
        }
        if (!f.getAnneeId().equals(i.anneeId())) {
            throw new RegleMetierException("FRAIS_HORS_ANNEE", "Ce frais ne concerne pas l'année de l'inscription");
        }
        if (!Situations.vise(f, cibles.findByFraisIdIn(List.of(fraisId)), classes.trouver(i.classeId()))) {
            throw new RegleMetierException("FRAIS_HORS_CLASSE", "« " + f.getLibelle()
                    + " » ne s'applique pas à la classe " + i.classeCode());
        }
        if (souscriptions.findByInscriptionIdAndFraisId(inscriptionId, fraisId).isEmpty()) {
            souscriptions.save(new SouscriptionFrais(inscriptionId, fraisId, horloge.instant()));
            audit.enregistrer("FRAIS_SOUSCRIT", i.matricule() + " / " + f.getLibelle(), null);
        }
    }

    @Transactional
    public void resilier(UUID inscriptionId, UUID fraisId) {
        UtilisateurConnecte.etablissementActif();
        souscriptions.findByInscriptionIdAndFraisId(inscriptionId, fraisId).ifPresent(s -> {
            souscriptions.delete(s);
            audit.enregistrer("FRAIS_RESILIE", inscriptionId + " / " + fraisId, null);
        });
    }

    FraisScolarite trouver(UUID fraisId) {
        UtilisateurConnecte.etablissementActif();
        return frais.findById(fraisId).orElseThrow(() -> new RessourceIntrouvableException("Frais introuvable"));
    }

    // ------------------------------------------------------------------

    private void definir(FraisScolarite f, AnneeVue annee, String libelle, DonneesFrais d) {
        if (d.montant() == null || d.montant() <= 0 || d.montant() > MONTANT_MAX) {
            throw new IllegalArgumentException("Le montant est compris entre 1 et 100 000 000 FCFA");
        }
        long montant = d.montant();
        List<TrancheSaisie> saisies = d.tranches().isEmpty()
                ? List.of(new TrancheSaisie(annee.debut(), montant)) : d.tranches();
        if (saisies.size() > 12) {
            throw new IllegalArgumentException("Douze tranches au plus");
        }
        long somme = 0;
        LocalDate precedente = null;
        for (TrancheSaisie t : saisies) {
            if (t == null || t.dateLimite() == null || t.montant() == null || t.montant() <= 0) {
                throw new IllegalArgumentException("Chaque tranche a une date limite et un montant positif");
            }
            if (precedente != null && !t.dateLimite().isAfter(precedente)) {
                throw new IllegalArgumentException("Les dates limites des tranches se suivent (une par tranche)");
            }
            if (t.dateLimite().isBefore(annee.debut().minusMonths(6)) || t.dateLimite().isAfter(annee.fin())) {
                throw new IllegalArgumentException("La date limite " + t.dateLimite()
                        + " est en dehors de l'année scolaire " + annee.libelle());
            }
            precedente = t.dateLimite();
            somme += t.montant();
        }
        if (somme != montant) {
            throw new RegleMetierException("TRANCHES_INCOHERENTES", "La somme des tranches (" + Montants.fcfa(somme)
                    + ") doit être égale au montant du frais (" + Montants.fcfa(montant) + ")");
        }
        List<FraisCible> nouvellesCibles = cibles(f.getId(), annee, d);
        f.definir(libelle, montant, d.obligatoire(), d.couvertParBourse(), d.portee());
        frais.save(f);
        cibles.saveAll(nouvellesCibles);
        int numero = 1;
        for (TrancheSaisie t : saisies) {
            tranches.save(new TrancheFrais(f.getId(), numero++, t.dateLimite(), t.montant()));
        }
    }

    private List<FraisCible> cibles(UUID fraisId, AnneeVue annee, DonneesFrais d) {
        List<FraisCible> resultat = new ArrayList<>();
        int autres = switch (d.portee()) {
            case TOUTES -> d.filieres().size() + d.niveaux().size() + d.classes().size();
            case FILIERES -> d.niveaux().size() + d.classes().size();
            case NIVEAUX -> d.filieres().size() + d.classes().size();
            case CLASSES -> d.filieres().size() + d.niveaux().size();
        };
        if (autres > 0) {
            throw new IllegalArgumentException("Les cibles doivent correspondre à la portée " + d.portee()
                    + " (filieres, niveaux ou classes)");
        }
        switch (d.portee()) {
            case TOUTES -> {
            }
            case FILIERES -> {
                Set<UUID> connues = filieres.lister().stream().map(FiliereVue::id).collect(Collectors.toSet());
                for (UUID id : new LinkedHashSet<>(exiger(d.filieres(), "filière"))) {
                    if (!connues.contains(id)) {
                        throw new RessourceIntrouvableException("Filière introuvable : " + id);
                    }
                    resultat.add(new FraisCible(fraisId, id, null, null));
                }
            }
            case NIVEAUX -> {
                Set<String> vus = new HashSet<>();
                for (String niveau : exiger(d.niveaux(), "niveau")) {
                    String propre = niveau == null ? "" : niveau.trim();
                    if (propre.isEmpty() || propre.length() > 30) {
                        throw new IllegalArgumentException("Niveau invalide : « " + niveau + " »");
                    }
                    if (vus.add(propre.toLowerCase())) {
                        resultat.add(new FraisCible(fraisId, null, propre, null));
                    }
                }
            }
            case CLASSES -> {
                for (UUID id : new LinkedHashSet<>(exiger(d.classes(), "classe"))) {
                    ClasseVue c = classes.trouver(id);
                    if (!c.anneeId().equals(annee.id())) {
                        throw new RegleMetierException("CLASSE_HORS_ANNEE", "La classe " + c.code()
                                + " n'appartient pas à l'année " + annee.libelle());
                    }
                    resultat.add(new FraisCible(fraisId, null, null, id));
                }
            }
        }
        return resultat;
    }

    private static <T> List<T> exiger(List<T> valeurs, String nature) {
        if (valeurs.isEmpty() || valeurs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Indiquez au moins une " + nature + " pour cette portée");
        }
        if (valeurs.size() > 200) {
            throw new IllegalArgumentException("Trop de cibles (200 au plus)");
        }
        return valeurs;
    }

    private AnneeVue anneeModifiable(UUID anneeId) {
        AnneeVue annee = annees.trouver(anneeId);
        if (annee.etat() == EtatAnnee.CLOTUREE || annee.etat() == EtatAnnee.ARCHIVEE) {
            throw new RegleMetierException("ANNEE_CLOTUREE", "L'année " + annee.libelle()
                    + " est close : ses frais ne changent plus");
        }
        return annee;
    }

    private static String libelle(String saisi) {
        if (saisi == null || saisi.isBlank() || saisi.trim().length() > 80) {
            throw new IllegalArgumentException("Le libellé du frais est obligatoire (80 caractères au plus)");
        }
        return saisi.trim().replaceAll("\\s+", " ");
    }

    private List<FraisVue> vues(List<FraisScolarite> liste) {
        if (liste.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = liste.stream().map(FraisScolarite::getId).toList();
        Map<UUID, List<FraisCible>> parFrais = cibles.findByFraisIdIn(ids).stream()
                .collect(Collectors.groupingBy(FraisCible::getFraisId));
        Map<UUID, List<TrancheFrais>> tranchesParFrais = tranches.findByFraisIdInOrderByNumeroAsc(ids).stream()
                .collect(Collectors.groupingBy(TrancheFrais::getFraisId));
        return liste.stream().map(f -> {
            List<FraisCible> cs = parFrais.getOrDefault(f.getId(), List.of());
            return new FraisVue(f.getId(), f.getAnneeId(), f.getLibelle(), f.getMontant(), f.isObligatoire(),
                    f.isCouvertParBourse(), f.getPortee(),
                    cs.stream().map(FraisCible::getFiliereId).filter(Objects::nonNull).toList(),
                    cs.stream().map(FraisCible::getNiveau).filter(Objects::nonNull).toList(),
                    cs.stream().map(FraisCible::getClasseId).filter(Objects::nonNull).toList(),
                    tranchesParFrais.getOrDefault(f.getId(), List.of()).stream()
                            .map(t -> new TrancheVue(t.getNumero(), t.getDateLimite(), t.getMontant())).toList());
        }).toList();
    }
}
