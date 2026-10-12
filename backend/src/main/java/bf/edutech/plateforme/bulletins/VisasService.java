package bf.edutech.plateforme.bulletins;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.Signatures;

/**
 * Visa des matières sur les bulletins (v0.44). Une fois la période verrouillée (notes figées), chaque
 * enseignant vise sa matière dans chaque classe : il atteste ses notes et ses appréciations. Le bulletin
 * imprime alors, devant la matière, sa signature numérisée et la date du visa. Une appréciation modifiée
 * ensuite annule le visa. Les bulletins se génèrent même sans tous les visas : la direction voit la liste des
 * matières non visées et la case reste vide.
 */
@Service
public class VisasService {

    static final String[] DIRECTION = { "CENSEUR", "ADMIN_ECOLE" };
    private static final String[] CONSULTATION = { "CENSEUR", "ADMIN_ECOLE", "SECRETARIAT" };

    /** Une matière de la classe et son visa pour la période. */
    public record VisaVue(UUID matiereId, String matiereCode, String matiereLibelle, UUID engagementId, String enseignant,
            boolean vise, Instant viseLe, boolean signature, boolean moi) {
    }

    /** Ce que le bulletin imprime devant une matière visée. */
    record VisaImprime(String enseignant, Instant viseLe, byte[] signature) {
    }

    private final VisaMatiereRepository visas;
    private final GenerationBulletinsRepository generations;
    private final ClassesService classes;
    private final PeriodesService periodes;
    private final EnseignantsService enseignants;
    private final Signatures signatures;
    private final AuditService audit;
    private final Clock horloge;

    VisasService(VisaMatiereRepository visas, GenerationBulletinsRepository generations, ClassesService classes,
            PeriodesService periodes, EnseignantsService enseignants, Signatures signatures, AuditService audit,
            Clock horloge) {
        this.visas = visas;
        this.generations = generations;
        this.classes = classes;
        this.periodes = periodes;
        this.enseignants = enseignants;
        this.signatures = signatures;
        this.audit = audit;
        this.horloge = horloge;
    }

    /**
     * Matières de la classe et leurs visas. La direction et le secrétariat voient toutes les matières ;
     * un enseignant, les siennes.
     */
    @Transactional(readOnly = true)
    public List<VisaVue> visas(UUID classeId, UUID periodeId) {
        ClasseVue classe = verifier(classeId, periodeId);
        Optional<UUID> moi = monEngagement();
        boolean consultation = UtilisateurConnecte.aUnRole(CONSULTATION);
        if (!consultation && moi.isEmpty()) {
            throw new AccesRefuseException("Réservé à la direction, au secrétariat et aux enseignants de la classe");
        }
        List<MatiereDeClasseVue> matieres = classes.matieres(classe.id()).stream()
                .filter(m -> consultation || Objects.equals(m.engagementId(), moi.orElse(null)))
                .toList();
        if (!consultation && matieres.isEmpty()) {
            throw new AccesRefuseException("Vous n'enseignez pas dans cette classe");
        }
        Map<UUID, VisaMatiere> parMatiere = visas.findByClasseIdAndPeriodeId(classeId, periodeId).stream()
                .collect(Collectors.toMap(VisaMatiere::getMatiereId, Function.identity()));
        Map<UUID, String> noms = enseignants.nomsParEngagement(
                matieres.stream().map(MatiereDeClasseVue::engagementId).filter(Objects::nonNull).distinct().toList());
        Map<UUID, byte[]> signes = signatures.signatures(parMatiere.values().stream().map(VisaMatiere::getVisePar).toList());
        List<VisaVue> resultat = new ArrayList<>();
        for (MatiereDeClasseVue m : matieres) {
            VisaMatiere v = parMatiere.get(m.matiereId());
            boolean valide = v != null && v.getEngagementId().equals(m.engagementId());
            resultat.add(new VisaVue(m.matiereId(), m.matiereCode(), m.matiereLibelle(), m.engagementId(),
                    m.engagementId() != null ? noms.get(m.engagementId()) : null, valide, valide ? v.getViseLe() : null,
                    valide && signes.containsKey(v.getVisePar()),
                    m.engagementId() != null && m.engagementId().equals(moi.orElse(null))));
        }
        resultat.sort(Comparator.comparing(VisaVue::matiereLibelle, String.CASE_INSENSITIVE_ORDER));
        return resultat;
    }

    /** L'enseignant vise sa matière : période verrouillée, bulletins pas encore publiés. Sans effet s'il l'a déjà visée. */
    @Transactional
    public List<VisaVue> viser(UUID classeId, UUID periodeId, UUID matiereId) {
        ClasseVue classe = verifier(classeId, periodeId);
        MatiereDeClasseVue matiere = matiere(classe, matiereId);
        UUID engagement = monEngagement()
                .filter(e -> e.equals(matiere.engagementId()))
                .orElseThrow(() -> new AccesRefuseException(
                        "Seul l'enseignant de " + matiere.matiereLibelle() + " en " + classe.code() + " vise cette matière"));
        PeriodeVue periode = periodes.trouver(periodeId);
        if (!periode.verrouillee()) {
            throw new RegleMetierException("VISA_PERIODE_OUVERTE", "La période « " + periode.libelle()
                    + " » n'est pas encore verrouillée : vous viserez quand les notes seront figées");
        }
        exigerNonPublie(classeId, periodeId);
        Optional<VisaMatiere> existant = visas.findByClasseIdAndMatiereIdAndPeriodeId(classeId, matiereId, periodeId);
        if (existant.isPresent() && existant.get().getEngagementId().equals(engagement)) {
            return visas(classeId, periodeId);
        }
        existant.ifPresent(v -> {
            visas.delete(v); // visa d'un enseignant précédent de la matière
            visas.flush();
        });
        visas.save(new VisaMatiere(classeId, matiereId, periodeId, engagement, UtilisateurConnecte.id(), horloge.instant()));
        audit.enregistrer("MATIERE_VISEE", classe.code() + " / " + matiere.matiereCode() + " / " + periode.libelle(), null);
        return visas(classeId, periodeId);
    }

    /** Retrait du visa : son auteur, ou la direction ; jamais après la publication. */
    @Transactional
    public List<VisaVue> retirer(UUID classeId, UUID periodeId, UUID matiereId) {
        ClasseVue classe = verifier(classeId, periodeId);
        MatiereDeClasseVue matiere = matiere(classe, matiereId);
        exigerNonPublie(classeId, periodeId);
        visas.findByClasseIdAndMatiereIdAndPeriodeId(classeId, matiereId, periodeId).ifPresent(v -> {
            boolean auteur = v.getVisePar().equals(UtilisateurConnecte.idSiConnecte().orElse(null));
            if (!auteur && !UtilisateurConnecte.aUnRole(DIRECTION)) {
                throw new AccesRefuseException("Seul l'enseignant qui a visé (ou la direction) retire ce visa");
            }
            visas.delete(v);
            audit.enregistrer("VISA_RETIRE", classe.code() + " / " + matiere.matiereCode(), null);
        });
        return visas(classeId, periodeId);
    }

    /** Une appréciation de la matière a changé : le visa ne vaut plus (appelé par le conseil de classe). */
    @Transactional
    public void annulerVisa(UUID classeId, UUID periodeId, UUID matiereId) {
        visas.findByClasseIdAndMatiereIdAndPeriodeId(classeId, matiereId, periodeId).ifPresent(v -> {
            visas.delete(v);
            audit.enregistrer("VISA_ANNULE", matiereId.toString(), java.util.Map.of("motif", "appréciation modifiée"));
        });
    }

    /** Visas valides par matière, avec la signature à imprimer (génération des bulletins). */
    @Transactional(readOnly = true)
    Map<UUID, VisaImprime> pourImpression(UUID classeId, UUID periodeId, List<MatiereDeClasseVue> matieres) {
        Map<UUID, UUID> engagementParMatiere = new HashMap<>();
        matieres.forEach(m -> {
            if (m.engagementId() != null) {
                engagementParMatiere.put(m.matiereId(), m.engagementId());
            }
        });
        List<VisaMatiere> valides = visas.findByClasseIdAndPeriodeId(classeId, periodeId).stream()
                .filter(v -> v.getEngagementId().equals(engagementParMatiere.get(v.getMatiereId())))
                .toList();
        Map<UUID, String> noms = enseignants.nomsParEngagement(valides.stream().map(VisaMatiere::getEngagementId).distinct().toList());
        Map<UUID, byte[]> signes = signatures.signatures(valides.stream().map(VisaMatiere::getVisePar).toList());
        Map<UUID, VisaImprime> resultat = new HashMap<>();
        for (VisaMatiere v : valides) {
            resultat.put(v.getMatiereId(), new VisaImprime(noms.get(v.getEngagementId()), v.getViseLe(), signes.get(v.getVisePar())));
        }
        return resultat;
    }

    /** Matières enseignées (avec enseignant) qui n'ont pas de visa valide : libellés, pour avertir la direction. */
    @Transactional(readOnly = true)
    List<String> nonVisees(UUID classeId, UUID periodeId) {
        List<MatiereDeClasseVue> matieres = classes.matieres(classeId);
        Map<UUID, VisaImprime> valides = pourImpression(classeId, periodeId, matieres);
        return matieres.stream()
                .filter(m -> m.engagementId() != null && !valides.containsKey(m.matiereId()))
                .map(MatiereDeClasseVue::matiereLibelle)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // ------------------------------------------------------------------

    private ClasseVue verifier(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        PeriodeVue periode = periodes.trouver(periodeId);
        if (!periode.anneeId().equals(classe.anneeId()) || !periode.profilId().equals(classe.profilId())) {
            throw new RegleMetierException("PERIODE_HORS_CLASSE",
                    "La période « " + periode.libelle() + " » ne concerne pas la classe " + classe.code());
        }
        return classe;
    }

    private MatiereDeClasseVue matiere(ClasseVue classe, UUID matiereId) {
        return classes.matieres(classe.id()).stream().filter(m -> m.matiereId().equals(matiereId)).findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Cette matière n'est pas enseignée en " + classe.code()));
    }

    private void exigerNonPublie(UUID classeId, UUID periodeId) {
        generations.findByClasseIdAndPeriodeId(classeId, periodeId).filter(GenerationBulletins::estPubliee).ifPresent(g -> {
            throw new RegleMetierException("BULLETINS_PUBLIES", "Les bulletins de cette période sont publiés : les visas ne changent plus");
        });
    }

    private Optional<UUID> monEngagement() {
        if (!UtilisateurConnecte.aUnRole("ENSEIGNANT")) {
            return Optional.empty();
        }
        return UtilisateurConnecte.idSiConnecte().flatMap(enseignants::engagementActif);
    }
}
