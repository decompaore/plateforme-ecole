package bf.edutech.plateforme.progression;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.progression.Vues.DemandeVisa;
import bf.edutech.plateforme.progression.Vues.DonneesFiche;
import bf.edutech.plateforme.progression.Vues.Domaine;
import bf.edutech.plateforme.progression.Vues.FicheVue;
import bf.edutech.plateforme.progression.Vues.SaisieSequence;
import bf.edutech.plateforme.progression.Vues.SequenceVue;
import bf.edutech.plateforme.progression.Vues.SuiviProgressionVue;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;

/**
 * Fiches de progression : l'enseignant prépare la progression annuelle de chacune de ses
 * matières (séquences, contenus, compétences, heures prévues), la soumet, et la direction la
 * vise ou la renvoie avec un commentaire.
 */
@Service
public class ProgressionService {

    static final int SEQUENCES_MAX = 60;

    private final FicheProgressionRepository fiches;
    private final SequenceProgressionRepository sequences;
    private final ClassesService classes;
    private final AnneesService annees;
    private final EnseignantsService enseignants;
    private final MembresService membres;
    private final Supervision supervision;
    private final Clock horloge;

    ProgressionService(FicheProgressionRepository fiches, SequenceProgressionRepository sequences,
            ClassesService classes, AnneesService annees, EnseignantsService enseignants, MembresService membres,
            Supervision supervision, Clock horloge) {
        this.fiches = fiches;
        this.sequences = sequences;
        this.classes = classes;
        this.annees = annees;
        this.enseignants = enseignants;
        this.membres = membres;
        this.supervision = supervision;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ consultation

    /** Fiche d'une matière : pour son enseignant et pour qui supervise son domaine. */
    @Transactional(readOnly = true)
    public FicheVue fiche(UUID classeId, UUID matiereId) {
        Contexte c = contexte(classeId, matiereId);
        if (!c.auteur() && !c.superviseur()) {
            throw new AccesRefuseException("Vous n'enseignez pas " + c.matiere().matiereLibelle() + " en "
                    + c.classe().code() + " et ne supervisez pas cette matière");
        }
        return vue(c, fiches.findByClasseIdAndMatiereId(classeId, matiereId).orElse(null));
    }

    /** Les fiches de l'enseignant connecté pour l'année active (une ligne par matière confiée). */
    @Transactional(readOnly = true)
    public List<SuiviProgressionVue> mesFiches() {
        UUID engagement = monEngagement()
                .orElseThrow(() -> new AccesRefuseException("Réservé aux enseignants en fonction"));
        return lignes(annees.active().id(), m -> engagement.equals(m.engagementId()));
    }

    /** Suivi de la direction : toutes les matières de son domaine, fiche commencée ou non. */
    @Transactional(readOnly = true)
    public List<SuiviProgressionVue> suivi(UUID anneeId) {
        Set<Domaine> domaines = supervision.domaines();
        if (domaines.isEmpty()) {
            throw new AccesRefuseException("Le suivi des progressions est réservé à la direction");
        }
        return lignes(anneeId, m -> domaines.contains(Domaine.de(m.type())));
    }

    // ------------------------------------------------------------------ saisie par l'enseignant

    @Transactional
    public FicheVue enregistrer(UUID classeId, UUID matiereId, DonneesFiche d) {
        Contexte c = contexte(classeId, matiereId);
        exigerAuteur(c);
        List<SaisieSequence> saisies = valider(d);
        Instant maintenant = horloge.instant();
        FicheProgression fiche = fiches.findByClasseIdAndMatiereId(classeId, matiereId)
                .orElseGet(() -> new FicheProgression(classeId, matiereId, c.matiere().engagementId(), maintenant));
        fiche.modifier(c.matiere().engagementId(), maintenant);
        fiches.save(fiche);
        sequences.supprimerDeLaFiche(fiche.getId());
        List<SequenceProgression> nouvelles = new ArrayList<>();
        for (int i = 0; i < saisies.size(); i++) {
            SaisieSequence s = saisies.get(i);
            nouvelles.add(new SequenceProgression(fiche.getId(), i + 1, s.titre().strip(), texte(s.contenu()),
                    texte(s.competences()), s.heuresPrevues(), s.semaineDebut()));
        }
        sequences.saveAll(nouvelles);
        return vue(c, fiche);
    }

    @Transactional
    public FicheVue soumettre(UUID classeId, UUID matiereId) {
        Contexte c = contexte(classeId, matiereId);
        exigerAuteur(c);
        FicheProgression fiche = fiches.findByClasseIdAndMatiereId(classeId, matiereId)
                .orElseThrow(() -> new RegleMetierException("FICHE_VIDE", "Ajoutez au moins une séquence avant de soumettre"));
        if (sequences.findByFicheIdOrderByOrdre(fiche.getId()).isEmpty()) {
            throw new RegleMetierException("FICHE_VIDE", "Ajoutez au moins une séquence avant de soumettre");
        }
        fiche.soumettre(horloge.instant());
        fiches.save(fiche);
        return vue(c, fiche);
    }

    // ------------------------------------------------------------------ visa par la direction

    @Transactional
    public FicheVue viser(UUID classeId, UUID matiereId, DemandeVisa d) {
        Contexte c = contexte(classeId, matiereId);
        if (!c.superviseur()) {
            throw new AccesRefuseException(c.domaine() == Domaine.TECHNIQUE
                    ? "Les progressions des matières techniques sont visées par le chef des travaux"
                    : "Les progressions des matières générales sont visées par le censeur");
        }
        if (c.auteur()) {
            throw new RegleMetierException("VISA_PAR_AUTEUR", "Vous ne pouvez pas viser votre propre progression");
        }
        if (d == null || d.accepte() == null) {
            throw new IllegalArgumentException("Indiquez si la progression est visée ou à revoir");
        }
        if (d.commentaire() != null && d.commentaire().strip().length() > 500) {
            throw new IllegalArgumentException("Commentaire de 500 caractères au plus");
        }
        FicheProgression fiche = fiches.findByClasseIdAndMatiereId(classeId, matiereId)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune progression pour cette matière"));
        fiche.viser(d.accepte(), d.commentaire(), UtilisateurConnecte.id(), horloge.instant());
        fiches.save(fiche);
        return vue(c, fiche);
    }

    // ------------------------------------------------------------------ outils

    private record Contexte(ClasseVue classe, MatiereDeClasseVue matiere, Domaine domaine, boolean auteur,
            boolean superviseur) {
    }

    private Contexte contexte(UUID classeId, UUID matiereId) {
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

    private static void exigerAuteur(Contexte c) {
        if (!c.auteur()) {
            throw new AccesRefuseException("Seul l'enseignant de " + c.matiere().matiereLibelle() + " en "
                    + c.classe().code() + " prépare sa progression");
        }
    }

    private Optional<UUID> monEngagement() {
        return UtilisateurConnecte.idSiConnecte().flatMap(enseignants::engagementActif);
    }

    private static List<SaisieSequence> valider(DonneesFiche d) {
        List<SaisieSequence> saisies = d == null || d.sequences() == null ? List.of() : d.sequences();
        if (saisies.size() > SEQUENCES_MAX) {
            throw new IllegalArgumentException(SEQUENCES_MAX + " séquences au plus");
        }
        for (int i = 0; i < saisies.size(); i++) {
            SaisieSequence s = saisies.get(i);
            String n = "Séquence " + (i + 1) + " : ";
            if (s == null || s.titre() == null || s.titre().isBlank()) {
                throw new IllegalArgumentException(n + "titre obligatoire");
            }
            if (s.titre().strip().length() > 150) {
                throw new IllegalArgumentException(n + "titre de 150 caractères au plus");
            }
            if (s.contenu() != null && s.contenu().strip().length() > 2000) {
                throw new IllegalArgumentException(n + "contenu de 2 000 caractères au plus");
            }
            if (s.competences() != null && s.competences().strip().length() > 500) {
                throw new IllegalArgumentException(n + "compétences de 500 caractères au plus");
            }
            BigDecimal h = s.heuresPrevues();
            if (h == null || h.signum() <= 0 || h.compareTo(BigDecimal.valueOf(999)) > 0
                    || h.stripTrailingZeros().scale() > 1) {
                throw new IllegalArgumentException(n + "heures prévues entre 0,5 et 999 (une décimale au plus)");
            }
        }
        return saisies;
    }

    private static String texte(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private FicheVue vue(Contexte c, FicheProgression fiche) {
        List<SequenceVue> liste = fiche == null ? List.of()
                : sequences.findByFicheIdOrderByOrdre(fiche.getId()).stream()
                        .map(s -> new SequenceVue(s.getOrdre(), s.getTitre(), s.getContenu(), s.getCompetences(),
                                s.getHeuresPrevues(), s.getSemaineDebut()))
                        .toList();
        BigDecimal heures = liste.stream().map(SequenceVue::heuresPrevues).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID engagement = c.matiere().engagementId();
        String enseignant = engagement == null ? null : enseignants.nomsParEngagement(List.of(engagement)).get(engagement);
        String visePar = fiche == null || fiche.getVisePar() == null ? null : nomDe(fiche.getVisePar());
        StatutFiche statut = fiche == null ? null : fiche.getStatut();
        return new FicheVue(fiche == null ? null : fiche.getId(), c.classe().id(), c.classe().code(),
                c.matiere().matiereId(), c.matiere().matiereCode(), c.matiere().matiereLibelle(), c.matiere().type(),
                c.domaine(), engagement, enseignant, statut, liste, heures, c.matiere().volumeHebdo(),
                c.matiere().volumeTotal(), fiche == null ? null : fiche.getModifieeLe(),
                fiche == null ? null : fiche.getSoumiseLe(), fiche == null ? null : fiche.getViseLe(), visePar,
                fiche == null ? null : fiche.getCommentaireVisa(),
                c.auteur() && statut != StatutFiche.SOUMISE,
                c.superviseur() && !c.auteur() && statut == StatutFiche.SOUMISE);
    }

    private String nomDe(UUID utilisateurId) {
        return membres.lister().stream().filter(m -> m.utilisateurId().equals(utilisateurId)).findFirst()
                .map(m -> m.nom() + " " + m.prenoms()).orElse(null);
    }

    private List<SuiviProgressionVue> lignes(UUID anneeId, Predicate<MatiereDeClasseVue> retenue) {
        List<ClasseVue> lesClasses = classes.lister(anneeId);
        if (lesClasses.isEmpty()) {
            return List.of();
        }
        Map<String, FicheProgression> parCle = fiches.findByClasseIdIn(lesClasses.stream().map(ClasseVue::id).toList())
                .stream().collect(Collectors.toMap(f -> cle(f.getClasseId(), f.getMatiereId()), Function.identity()));
        Map<UUID, List<SequenceProgression>> parFiche = parCle.isEmpty() ? Map.of()
                : sequences.findByFicheIdIn(parCle.values().stream().map(FicheProgression::getId).toList()).stream()
                        .collect(Collectors.groupingBy(SequenceProgression::getFicheId));
        List<SuiviProgressionVue> lignes = new ArrayList<>();
        List<UUID> engagements = new ArrayList<>();
        record Ligne(ClasseVue classe, MatiereDeClasseVue matiere) {
        }
        List<Ligne> retenues = new ArrayList<>();
        for (ClasseVue classe : lesClasses) {
            for (MatiereDeClasseVue m : classes.matieres(classe.id())) {
                if (retenue.test(m)) {
                    retenues.add(new Ligne(classe, m));
                    if (m.engagementId() != null) {
                        engagements.add(m.engagementId());
                    }
                }
            }
        }
        Map<UUID, String> noms = enseignants.nomsParEngagement(engagements.stream().distinct().toList());
        for (Ligne l : retenues) {
            FicheProgression f = parCle.get(cle(l.classe().id(), l.matiere().matiereId()));
            List<SequenceProgression> seq = f == null ? List.of() : parFiche.getOrDefault(f.getId(), List.of());
            UUID e = l.matiere().engagementId();
            lignes.add(new SuiviProgressionVue(l.classe().id(), l.classe().code(), l.classe().niveau(),
                    l.matiere().matiereId(), l.matiere().matiereCode(), l.matiere().matiereLibelle(), l.matiere().type(),
                    Domaine.de(l.matiere().type()), e, e == null ? null : noms.get(e), f == null ? null : f.getStatut(),
                    seq.size(), seq.stream().map(SequenceProgression::getHeuresPrevues).reduce(BigDecimal.ZERO, BigDecimal::add),
                    l.matiere().volumeHebdo(), f == null ? null : f.getSoumiseLe(), f == null ? null : f.getViseLe()));
        }
        lignes.sort(Comparator.comparing(SuiviProgressionVue::classeCode).thenComparing(SuiviProgressionVue::matiereLibelle));
        return lignes;
    }

    private static String cle(UUID classeId, UUID matiereId) {
        return classeId + "/" + matiereId;
    }
}
