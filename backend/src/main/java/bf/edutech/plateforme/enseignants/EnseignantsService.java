package bf.edutech.plateforme.enseignants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.enseignants.Vues.EnseignantVue;
import bf.edutech.plateforme.enseignants.Vues.FicheEnseignantVue;
import bf.edutech.plateforme.enseignants.Vues.ResultatEngagement;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.Vues.AffectationVue;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.config.ParametresPlateforme;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.telephone.NumeroTelephone;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.MembresService.ResultatAjout;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Enseignants de l'établissement : engagement (création ou invitation), fin
 * d'engagement, identité, affectation aux matières des classes, charge horaire.
 * <p>
 * Règle : titulaire dans un seul établissement à la fois, vacataire dans autant
 * d'établissements que nécessaire. Garantie par la base (contraintes
 * d'exclusion) ; vérifiée ici au préalable pour renvoyer un message clair et
 * neutre, qui ne révèle jamais où l'enseignant travaille ailleurs.
 */
@Service
public class EnseignantsService {

    /** Données d'un nouvel engagement. L'identité n'est utilisée que si l'enseignant est inconnu. */
    public record DonneesEngagement(String telephone, String matriculeFp, String nom, String prenoms, Sexe sexe,
            String specialite, TypeEngagement type, LocalDate debut, LocalDate fin, BigDecimal tauxHoraire) {
    }

    static final EnumSet<StatutEngagement> EN_COURS = EnumSet.of(StatutEngagement.INVITE, StatutEngagement.ACTIF);

    private final EnseignantRepository enseignants;
    private final EngagementRepository engagements;
    private final IdentitesPlateforme plateforme;
    private final MembresService membres;
    private final ClassesService classes;
    private final AnneesService annees;
    private final ParametresPlateforme parametres;
    private final AuditService audit;

    EnseignantsService(EnseignantRepository enseignants, EngagementRepository engagements,
            IdentitesPlateforme plateforme, MembresService membres, ClassesService classes, AnneesService annees,
            ParametresPlateforme parametres, AuditService audit) {
        this.enseignants = enseignants;
        this.engagements = engagements;
        this.plateforme = plateforme;
        this.membres = membres;
        this.classes = classes;
        this.annees = annees;
        this.parametres = parametres;
        this.audit = audit;
    }

    /** Enseignants de l'établissement (tous les statuts si {@code statut} est null), par ordre alphabétique. */
    @Transactional(readOnly = true)
    public List<EnseignantVue> lister(StatutEngagement statut) {
        UtilisateurConnecte.etablissementActif();
        List<Engagement> liste = statut == null ? engagements.findAll() : engagements.findByStatutIn(List.of(statut));
        if (liste.isEmpty()) {
            return List.of();
        }
        Map<UUID, Enseignant> parId = enseignants
                .findByIdIn(liste.stream().map(Engagement::getEnseignantId).distinct().toList()).stream()
                .collect(Collectors.toMap(Enseignant::getId, Function.identity()));
        return liste.stream()
                .map(e -> EnseignantVue.depuis(e, parId.get(e.getEnseignantId())))
                .sorted(Comparator.comparing(EnseignantVue::statut)
                        .thenComparing(v -> v.nom() == null ? "" : v.nom())
                        .thenComparing(v -> v.prenoms() == null ? "" : v.prenoms()))
                .toList();
    }

    /** L'enseignant existe-t-il déjà sur la plateforme ? Réponse volontairement limitée à oui / non. */
    @Transactional(readOnly = true)
    public boolean existe(String telephoneSaisi, String matriculeFp) {
        UtilisateurConnecte.etablissementActif();
        String telephone = vide(telephoneSaisi) ? null : telephone(telephoneSaisi);
        String matricule = vide(matriculeFp) ? null : matriculeFp.trim().toUpperCase(Locale.ROOT);
        if (telephone == null && matricule == null) {
            throw new IllegalArgumentException("Indiquez un téléphone ou un matricule");
        }
        return plateforme.rechercher(telephone, matricule).isPresent();
    }

    /**
     * Engage un enseignant. S'il est déjà connu de la plateforme (autre école),
     * l'engagement est une INVITATION qu'il doit accepter ; sinon son identité et
     * son compte sont créés et l'engagement est ACTIF immédiatement.
     */
    @Transactional
    public ResultatEngagement engager(DonneesEngagement d) {
        UtilisateurConnecte.etablissementActif();
        String telephone = telephone(d.telephone());
        String matricule = vide(d.matriculeFp()) ? null : d.matriculeFp().trim().toUpperCase(Locale.ROOT);
        verifierConditions(d.type(), d.debut(), d.fin(), d.tauxHoraire());

        Optional<UUID> existant = plateforme.rechercher(telephone, matricule);
        if (existant.isPresent()) {
            UUID enseignantId = existant.get();
            if (engagements.existsByEnseignantIdAndStatutIn(enseignantId, EN_COURS)) {
                throw new RegleMetierException("DEJA_ENGAGE",
                        "Cet enseignant est déjà engagé ou invité dans l'établissement");
            }
            if (d.type() == TypeEngagement.TITULAIRE && plateforme.posteTitulaireOccupe(enseignantId, d.debut(), d.fin())) {
                throw new RegleMetierException("POSTE_TITULAIRE_OCCUPE", "Cet enseignant occupe déjà un poste de "
                        + "titulaire sur cette période : il ne peut être engagé ici que comme vacataire");
            }
            // Écrit tout de suite : la Row-Level Security ne montre l'identité qu'une fois l'engagement en base
            Engagement invitation = engagements.saveAndFlush(new Engagement(enseignantId, d.type(),
                    StatutEngagement.INVITE, d.debut(), d.fin(), d.tauxHoraire()));
            Enseignant enseignant = enseignants.findById(enseignantId).orElseThrow();
            audit.enregistrer("ENSEIGNANT_INVITE", d.type().name(), Map.of("engagement", invitation.getId()));
            return new ResultatEngagement(EnseignantVue.depuis(invitation, enseignant), true, null);
        }

        // Enseignant inconnu : création de l'identité, du compte et de l'engagement actif
        if (d.sexe() == null) {
            throw new IllegalArgumentException("Le sexe est obligatoire pour un nouvel enseignant");
        }
        String nom = obligatoire(d.nom(), "Le nom", 80).toUpperCase(Locale.ROOT);
        String prenoms = obligatoire(d.prenoms(), "Les prénoms", 120);
        ResultatAjout compte = membres.garantirRole(telephone, nom, prenoms, Role.ENSEIGNANT);
        Enseignant enseignant = enseignants.save(new Enseignant(compte.membre().utilisateurId(), telephone, matricule,
                nom, prenoms, d.sexe(), facultatif(d.specialite(), "La spécialité", 80)));
        Engagement engagement = engagements.save(new Engagement(enseignant.getId(), d.type(), StatutEngagement.ACTIF,
                d.debut(), d.fin(), d.tauxHoraire()));
        audit.enregistrer("ENSEIGNANT_ENGAGE", d.type().name(), Map.of("engagement", engagement.getId()));
        return new ResultatEngagement(EnseignantVue.depuis(engagement, enseignant), false,
                compte.motDePasseTemporaire());
    }

    /** Fiche : engagement, matières assurées pendant l'année (l'année active par défaut) et charge horaire. */
    @Transactional(readOnly = true)
    public FicheEnseignantVue fiche(UUID engagementId, UUID anneeId) {
        Engagement engagement = charger(engagementId);
        Enseignant enseignant = enseignants.findById(engagement.getEnseignantId()).orElseThrow();
        return fiche(engagement, enseignant, anneeId);
    }

    /** Correction de l'identité : réservée à l'établissement où l'enseignant est titulaire. */
    @Transactional
    public EnseignantVue modifierIdentite(UUID engagementId, String matriculeFp, String nomSaisi, String prenomsSaisis,
            Sexe sexe, String specialite) {
        Engagement engagement = charger(engagementId);
        if (engagement.getType() != TypeEngagement.TITULAIRE || engagement.getStatut() != StatutEngagement.ACTIF) {
            throw new RegleMetierException("IDENTITE_NON_MODIFIABLE",
                    "Seul l'établissement où l'enseignant est titulaire peut corriger son identité");
        }
        if (sexe == null) {
            throw new IllegalArgumentException("Le sexe est obligatoire");
        }
        Enseignant enseignant = enseignants.findById(engagement.getEnseignantId()).orElseThrow();
        String matricule = vide(matriculeFp) ? null : matriculeFp.trim().toUpperCase(Locale.ROOT);
        if (matricule != null && !matricule.equals(enseignant.getMatriculeFp())) {
            plateforme.rechercher(null, matricule).filter(id -> !id.equals(enseignant.getId())).ifPresent(id -> {
                throw new RegleMetierException("MATRICULE_EXISTANT", "Ce matricule est déjà attribué à un autre enseignant");
            });
        }
        enseignant.modifier(matricule, obligatoire(nomSaisi, "Le nom", 80).toUpperCase(Locale.ROOT),
                obligatoire(prenomsSaisis, "Les prénoms", 120), sexe, facultatif(specialite, "La spécialité", 80));
        audit.enregistrer("ENSEIGNANT_MODIFIE", engagementId.toString(), null);
        return EnseignantVue.depuis(engagement, enseignant);
    }

    /**
     * Fin d'engagement (mutation, départ, fin de vacation) : l'enseignant perd
     * l'accès à l'établissement et est retiré des classes des années en cours.
     */
    @Transactional
    public EnseignantVue terminer(UUID engagementId, LocalDate date, String motif) {
        Engagement engagement = charger(engagementId);
        Enseignant enseignant = enseignants.findById(engagement.getEnseignantId()).orElseThrow();
        engagement.terminer(date, facultatif(motif, "Le motif", 200));
        membres.retirerRole(enseignant.getUtilisateurId(), Role.ENSEIGNANT);
        int liberees = classes.libererEnseignant(engagementId);
        audit.enregistrer("ENGAGEMENT_TERMINE", engagementId.toString(),
                Map.of("date", date, "matieresLiberees", liberees));
        return EnseignantVue.depuis(engagement, enseignant);
    }

    /** Annule une invitation qui n'a pas encore reçu de réponse. */
    @Transactional
    public void annulerInvitation(UUID engagementId) {
        Engagement engagement = charger(engagementId);
        if (engagement.getStatut() != StatutEngagement.INVITE) {
            throw new RegleMetierException("STATUT_ENGAGEMENT",
                    "Seule une invitation en attente peut être annulée ; sinon, terminez l'engagement");
        }
        engagements.delete(engagement);
        audit.enregistrer("INVITATION_ANNULEE", engagementId.toString(), null);
    }

    /** Affecte un enseignant actif de l'établissement à la matière d'une classe. */
    @Transactional
    public MatiereDeClasseVue affecter(UUID classeId, UUID matiereId, UUID engagementId) {
        Engagement engagement = charger(engagementId);
        if (engagement.getStatut() != StatutEngagement.ACTIF) {
            throw new RegleMetierException("ENGAGEMENT_INACTIF",
                    "Seul un enseignant dont l'engagement est actif peut être affecté");
        }
        return classes.affecterEnseignant(classeId, matiereId, engagementId);
    }

    @Transactional
    public MatiereDeClasseVue retirerAffectation(UUID classeId, UUID matiereId) {
        UtilisateurConnecte.etablissementActif();
        return classes.affecterEnseignant(classeId, matiereId, null);
    }

    /**
     * Le compte enseigne-t-il dans cette classe (engagement actif dans l'établissement
     * et au moins une matière affectée) ? Sert à limiter ce qu'un enseignant consulte.
     */
    @Transactional(readOnly = true)
    public boolean enseigneDans(UUID utilisateurId, UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        return enseignants.findByUtilisateurId(utilisateurId)
                .flatMap(s -> engagements.findFirstByEnseignantIdAndStatut(s.getId(), StatutEngagement.ACTIF))
                .map(e -> classes.estAffecte(e.getId(), classeId))
                .orElse(false);
    }

    /** « NOM Prénoms » des enseignants de ces engagements (invitations non acceptées exclues). */
    @Transactional(readOnly = true)
    public Map<UUID, String> nomsParEngagement(Collection<UUID> engagementIds) {
        UtilisateurConnecte.etablissementActif();
        if (engagementIds.isEmpty()) {
            return Map.of();
        }
        List<Engagement> liste = engagements.findAllById(engagementIds).stream()
                .filter(e -> e.getStatut() == StatutEngagement.ACTIF || e.getStatut() == StatutEngagement.TERMINE)
                .toList();
        if (liste.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Enseignant> parId = enseignants
                .findByIdIn(liste.stream().map(Engagement::getEnseignantId).distinct().toList()).stream()
                .collect(Collectors.toMap(Enseignant::getId, Function.identity()));
        Map<UUID, String> noms = new java.util.HashMap<>();
        for (Engagement e : liste) {
            Enseignant s = parId.get(e.getEnseignantId());
            if (s != null) {
                noms.put(e.getId(), s.getNom() + " " + s.getPrenoms());
            }
        }
        return noms;
    }

    /** Engagement actif du compte dans l'établissement actif, s'il en a un. */
    @Transactional(readOnly = true)
    public Optional<UUID> engagementActif(UUID utilisateurId) {
        UtilisateurConnecte.etablissementActif();
        return enseignants.findByUtilisateurId(utilisateurId)
                .flatMap(s -> engagements.findFirstByEnseignantIdAndStatut(s.getId(), StatutEngagement.ACTIF))
                .map(Engagement::getId);
    }

    /** Fiche de l'enseignant connecté dans l'établissement actif. */
    @Transactional(readOnly = true)
    public FicheEnseignantVue maFiche(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        Enseignant enseignant = enseignants.findByUtilisateurId(UtilisateurConnecte.id())
                .orElseThrow(() -> new RessourceIntrouvableException("Aucun engagement d'enseignant dans cet établissement"));
        Engagement engagement = engagements.findFirstByEnseignantIdAndStatut(enseignant.getId(), StatutEngagement.ACTIF)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucun engagement actif dans cet établissement"));
        return fiche(engagement, enseignant, anneeId);
    }

    // ------------------------------------------------------------------

    private FicheEnseignantVue fiche(Engagement engagement, Enseignant enseignant, UUID anneeId) {
        UUID annee = anneeId != null ? anneeId
                : annees.lister().stream().filter(a -> a.etat() == EtatAnnee.ACTIVE).map(AnneeVue::id).findFirst()
                        .orElse(null);
        List<AffectationVue> affectations = annee == null ? List.of() : classes.affectations(engagement.getId(), annee);
        BigDecimal charge = affectations.stream()
                .map(a -> a.volumeHebdo() != null ? a.volumeHebdo() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FicheEnseignantVue(EnseignantVue.depuis(engagement, enseignant), annee, affectations, charge);
    }

    private Engagement charger(UUID engagementId) {
        UtilisateurConnecte.etablissementActif();
        return engagements.findById(engagementId)
                .orElseThrow(() -> new RessourceIntrouvableException("Engagement introuvable"));
    }

    private static void verifierConditions(TypeEngagement type, LocalDate debut, LocalDate fin, BigDecimal taux) {
        if (type == null) {
            throw new IllegalArgumentException("Le type d'engagement est obligatoire (TITULAIRE ou VACATAIRE)");
        }
        if (debut == null) {
            throw new IllegalArgumentException("La date de début est obligatoire");
        }
        if (fin != null && fin.isBefore(debut)) {
            throw new IllegalArgumentException("La date de fin ne peut précéder la date de début");
        }
        if (type == TypeEngagement.TITULAIRE && taux != null) {
            throw new IllegalArgumentException("Le taux horaire ne concerne que les vacataires");
        }
    }

    private String telephone(String saisie) {
        return NumeroTelephone.normaliser(saisie, parametres.indicatifTelephone());
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }

    private static String obligatoire(String valeur, String libelle, int longueurMax) {
        if (vide(valeur)) {
            throw new IllegalArgumentException(libelle + " est obligatoire");
        }
        return facultatif(valeur, libelle, longueurMax);
    }

    private static String facultatif(String valeur, String libelle, int longueurMax) {
        if (vide(valeur)) {
            return null;
        }
        String nettoye = valeur.trim().replaceAll("\\s+", " ");
        if (nettoye.length() > longueurMax) {
            throw new IllegalArgumentException(libelle + " dépasse " + longueurMax + " caractères");
        }
        return nettoye;
    }
}
