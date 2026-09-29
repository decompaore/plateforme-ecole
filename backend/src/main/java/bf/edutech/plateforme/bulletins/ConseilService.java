package bf.edutech.plateforme.bulletins;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.bulletins.ParametresBulletinsService.ParametresBulletins;
import bf.edutech.plateforme.bulletins.Vues.AppreciationVue;
import bf.edutech.plateforme.bulletins.Vues.AvisVue;
import bf.edutech.plateforme.bulletins.Vues.SaisieAppreciation;
import bf.edutech.plateforme.bulletins.Vues.SaisieAvis;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.ResultatsService;
import bf.edutech.plateforme.evaluations.Vues.ResultatEleveVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Préparation du conseil de classe : appréciations des enseignants par matière,
 * appréciation générale et distinction de chaque élève (proposée d'après les
 * seuils, modifiable). Plus rien ne change une fois les bulletins publiés.
 */
@Service
public class ConseilService {

    static final String[] DIRECTION = { "ADMIN_ECOLE", "CENSEUR" };

    private final AppreciationRepository appreciations;
    private final AvisConseilRepository avis;
    private final GenerationBulletinsRepository generations;
    private final ClassesService classes;
    private final PeriodesService periodes;
    private final InscriptionsService inscriptions;
    private final EnseignantsService enseignants;
    private final ResultatsService resultats;
    private final ParametresBulletinsService parametres;
    private final AuditService audit;
    private final Clock horloge;

    ConseilService(AppreciationRepository appreciations, AvisConseilRepository avis,
            GenerationBulletinsRepository generations, ClassesService classes, PeriodesService periodes,
            InscriptionsService inscriptions, EnseignantsService enseignants, ResultatsService resultats,
            ParametresBulletinsService parametres, AuditService audit, Clock horloge) {
        this.appreciations = appreciations;
        this.avis = avis;
        this.generations = generations;
        this.classes = classes;
        this.periodes = periodes;
        this.inscriptions = inscriptions;
        this.enseignants = enseignants;
        this.resultats = resultats;
        this.parametres = parametres;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<AppreciationVue> appreciations(UUID classeId, UUID periodeId, UUID matiereId) {
        ClasseVue classe = verifierPeriode(classeId, periodeId);
        verifierEnseignant(classe, matiere(classeId, matiereId));
        List<InscriptionVue> eleves = inscriptions.listerParClasse(classeId, false);
        Map<UUID, String> textes = new HashMap<>();
        if (!eleves.isEmpty()) {
            appreciations.findByPeriodeIdAndMatiereIdAndInscriptionIdIn(periodeId, matiereId,
                    eleves.stream().map(InscriptionVue::id).toList())
                    .forEach(a -> textes.put(a.getInscriptionId(), a.getTexte()));
        }
        return eleves.stream()
                .map(i -> new AppreciationVue(i.id(), i.nom(), i.prenoms(), textes.get(i.id())))
                .toList();
    }

    /** L'enseignant de la matière (ou la direction) saisit les appréciations ; un texte vide efface. */
    @Transactional
    public List<AppreciationVue> saisirAppreciations(UUID classeId, UUID periodeId, UUID matiereId,
            List<SaisieAppreciation> saisies) {
        ClasseVue classe = verifierPeriode(classeId, periodeId);
        MatiereDeClasseVue matiere = matiere(classeId, matiereId);
        verifierEnseignant(classe, matiere);
        verifierNonPublie(classeId, periodeId);
        Set<UUID> eleves = inscriptions.listerParClasse(classeId, false).stream().map(InscriptionVue::id)
                .collect(Collectors.toSet());
        Map<UUID, Appreciation> existantes = eleves.isEmpty() ? new HashMap<>()
                : appreciations.findByPeriodeIdAndMatiereIdAndInscriptionIdIn(periodeId, matiereId, eleves).stream()
                        .collect(Collectors.toMap(Appreciation::getInscriptionId, Function.identity()));
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        Instant maintenant = horloge.instant();
        Set<UUID> vus = new HashSet<>();
        for (SaisieAppreciation s : saisies) {
            if (s == null || s.inscriptionId() == null || !vus.add(s.inscriptionId())) {
                throw new IllegalArgumentException("Chaque élève figure une seule fois, avec son inscription");
            }
            if (!eleves.contains(s.inscriptionId())) {
                continue;
            }
            String texte = s.texte() == null ? "" : s.texte().trim().replaceAll("\\s+", " ");
            if (texte.length() > 200) {
                throw new IllegalArgumentException("Une appréciation fait 200 caractères au plus");
            }
            Appreciation existante = existantes.get(s.inscriptionId());
            if (texte.isEmpty()) {
                if (existante != null) {
                    appreciations.delete(existante);
                }
                continue;
            }
            Appreciation a = existante != null ? existante : new Appreciation(s.inscriptionId(), periodeId, matiereId);
            a.definir(texte, moi, maintenant);
            if (existante == null) {
                appreciations.save(a);
            }
        }
        audit.enregistrer("APPRECIATIONS_SAISIES", classe.code() + " / " + matiere.matiereCode(), null);
        return appreciations(classeId, periodeId, matiereId);
    }

    /**
     * Tableau du conseil : résultats, distinction proposée, avis déjà saisis.
     * (Transaction en écriture : les paramètres par défaut sont créés au premier accès.)
     */
    @Transactional
    public List<AvisVue> avis(UUID classeId, UUID periodeId) {
        verifierPeriode(classeId, periodeId);
        ParametresBulletins seuils = parametres.lire();
        List<ResultatEleveVue> liste = resultats.calculer(classeId, periodeId).eleves();
        Map<UUID, AvisConseil> saisis = liste.isEmpty() ? Map.of()
                : avis.findByPeriodeIdAndInscriptionIdIn(periodeId,
                        liste.stream().map(ResultatEleveVue::inscriptionId).toList()).stream()
                        .collect(Collectors.toMap(AvisConseil::getInscriptionId, Function.identity()));
        return liste.stream()
                .map(r -> {
                    AvisConseil a = saisis.get(r.inscriptionId());
                    return new AvisVue(r.inscriptionId(), r.nom(), r.prenoms(), r.moyenne(), r.rang(), r.tauxMaitrise(),
                            seuils.proposer(r.moyenne()), a != null ? a.getDistinction() : null,
                            a != null ? a.getAppreciation() : null);
                })
                .toList();
    }

    /** Avis du conseil (direction) : distinction retenue et appréciation générale. */
    @Transactional
    public List<AvisVue> saisirAvis(UUID classeId, UUID periodeId, List<SaisieAvis> saisies) {
        ClasseVue classe = verifierPeriode(classeId, periodeId);
        verifierNonPublie(classeId, periodeId);
        Set<UUID> eleves = inscriptions.listerParClasse(classeId, false).stream().map(InscriptionVue::id)
                .collect(Collectors.toSet());
        Map<UUID, AvisConseil> existants = eleves.isEmpty() ? new HashMap<>()
                : avis.findByPeriodeIdAndInscriptionIdIn(periodeId, eleves).stream()
                        .collect(Collectors.toMap(AvisConseil::getInscriptionId, Function.identity()));
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        Instant maintenant = horloge.instant();
        Set<UUID> vus = new HashSet<>();
        for (SaisieAvis s : saisies) {
            if (s == null || s.inscriptionId() == null || !vus.add(s.inscriptionId())) {
                throw new IllegalArgumentException("Chaque élève figure une seule fois, avec son inscription");
            }
            if (!eleves.contains(s.inscriptionId())) {
                continue;
            }
            String texte = s.appreciation() == null || s.appreciation().isBlank() ? null
                    : s.appreciation().trim().replaceAll("\\s+", " ");
            if (texte != null && texte.length() > 300) {
                throw new IllegalArgumentException("L'appréciation générale fait 300 caractères au plus");
            }
            AvisConseil existant = existants.get(s.inscriptionId());
            if (s.distinction() == null && texte == null) {
                if (existant != null) {
                    avis.delete(existant);
                }
                continue;
            }
            AvisConseil a = existant != null ? existant : new AvisConseil(s.inscriptionId(), periodeId);
            a.definir(s.distinction(), texte, moi, maintenant);
            if (existant == null) {
                avis.save(a);
            }
        }
        audit.enregistrer("AVIS_CONSEIL_SAISIS", classe.code(), Map.of("periode", periodeId));
        return avis(classeId, periodeId);
    }

    // ------------------------------------------------------------------

    private ClasseVue verifierPeriode(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        PeriodeVue periode = periodes.trouver(periodeId);
        if (!periode.anneeId().equals(classe.anneeId()) || !periode.profilId().equals(classe.profilId())) {
            throw new RegleMetierException("PERIODE_HORS_CLASSE",
                    "La période « " + periode.libelle() + " » ne concerne pas la classe " + classe.code());
        }
        return classe;
    }

    private MatiereDeClasseVue matiere(UUID classeId, UUID matiereId) {
        return classes.matieres(classeId).stream().filter(m -> m.matiereId().equals(matiereId)).findFirst()
                .orElseThrow(() -> new RegleMetierException("MATIERE_HORS_CLASSE",
                        "Cette matière n'est pas enseignée dans la classe"));
    }

    /** Hors direction, seul l'enseignant de la matière dans la classe accède à ses appréciations. */
    private void verifierEnseignant(ClasseVue classe, MatiereDeClasseVue matiere) {
        if (UtilisateurConnecte.aUnRole(DIRECTION)) {
            return;
        }
        Optional<UUID> engagement = UtilisateurConnecte.idSiConnecte().flatMap(enseignants::engagementActif);
        if (engagement.isEmpty() || !engagement.get().equals(matiere.engagementId())) {
            throw new AccesRefuseException("Vous n'enseignez pas " + matiere.matiereLibelle() + " en " + classe.code());
        }
    }

    private void verifierNonPublie(UUID classeId, UUID periodeId) {
        generations.findByClasseIdAndPeriodeId(classeId, periodeId).filter(GenerationBulletins::estPubliee)
                .ifPresent(g -> {
                    throw new RegleMetierException("BULLETINS_PUBLIES",
                            "Les bulletins de cette période sont publiés : plus aucune modification");
                });
    }
}
