package bf.edutech.plateforme.absences;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.absences.Vues.AbsenceVue;
import bf.edutech.plateforme.absences.Vues.AbsencesParMatiereVue;
import bf.edutech.plateforme.absences.Vues.CreneauVue;
import bf.edutech.plateforme.absences.Vues.EleveDuJourVue;
import bf.edutech.plateforme.absences.Vues.JustificatifVue;
import bf.edutech.plateforme.absences.Vues.SyntheseEleveVue;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.MatieresService;
import bf.edutech.plateforme.etablissement.Vues.MatiereVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Absences d'un élève, justificatifs et synthèse par classe. */
@Service
public class AbsencesService {

    private static final LocalDate TOUJOURS_DEBUT = LocalDate.of(1970, 1, 1);
    private static final LocalDate TOUJOURS_FIN = LocalDate.of(2999, 12, 31);

    private final AbsenceRepository absences;
    private final JustificatifRepository justificatifs;
    private final InscriptionsService inscriptions;
    private final ElevesService eleves;
    private final Autorisations autorisations;
    private final AuditService audit;
    private final MatieresService matieres;

    AbsencesService(AbsenceRepository absences, JustificatifRepository justificatifs,
            InscriptionsService inscriptions, ElevesService eleves, Autorisations autorisations, AuditService audit,
            MatieresService matieres) {
        this.absences = absences;
        this.justificatifs = justificatifs;
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.autorisations = autorisations;
        this.audit = audit;
        this.matieres = matieres;
    }

    /** Absences et retards d'une inscription (toute l'année si les dates sont omises). */
    @Transactional(readOnly = true)
    public List<AbsenceVue> deLInscription(UUID inscriptionId, LocalDate du, LocalDate au) {
        inscriptions.trouver(inscriptionId); // 404 si l'inscription n'existe pas dans l'établissement
        return absencesDe(List.of(inscriptionId), du, au);
    }

    /** Absences d'un élève sur toutes ses inscriptions (espace parent). */
    @Transactional(readOnly = true)
    public List<AbsenceVue> deLEleve(UUID eleveId) {
        List<UUID> ids = eleves.trouver(eleveId).inscriptions().stream().map(InscriptionVue::id).toList();
        return ids.isEmpty() ? List.of() : absencesDe(ids, null, null);
    }

    /** Synthèse par élève d'une classe sur une période : absences, justifiées, retards, heures. */
    @Transactional(readOnly = true)
    public List<SyntheseEleveVue> syntheseClasse(UUID classeId, LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        autorisations.verifierConsultationClasse(classeId);
        List<InscriptionVue> eleveDeLaClasse = inscriptions.listerParClasse(classeId, true);
        if (eleveDeLaClasse.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AbsenceVue>> parInscription = absencesDe(
                eleveDeLaClasse.stream().map(InscriptionVue::id).toList(), du, au).stream()
                .collect(Collectors.groupingBy(AbsenceVue::inscriptionId));
        return eleveDeLaClasse.stream()
                .map(i -> synthese(i, parInscription.getOrDefault(i.id(), List.of())))
                .toList();
    }

    /**
     * Absences d'une classe par discipline sur une période (toute l'année si les dates sont
     * omises) : repère la matière ou le créneau que les élèves manquent le plus. Les heures
     * manquées les plus nombreuses d'abord ; les appels sans matière forment une ligne à part.
     */
    @Transactional(readOnly = true)
    public List<AbsencesParMatiereVue> parMatiere(UUID classeId, LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        autorisations.verifierConsultationClasse(classeId);
        List<UUID> ids = inscriptions.listerParClasse(classeId, true).stream().map(InscriptionVue::id).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Optional<UUID>, List<AbsenceVue>> parDiscipline = absencesDe(ids, du, au).stream()
                .collect(Collectors.groupingBy(a -> Optional.ofNullable(a.matiereId()), LinkedHashMap::new,
                        Collectors.toList()));
        return parDiscipline.values().stream()
                .map(liste -> {
                    AbsenceVue premiere = liste.get(0);
                    long minutes = 0;
                    long nonJustifiees = 0;
                    int nbAbsences = 0;
                    Set<UUID> eleves = new HashSet<>();
                    for (AbsenceVue a : liste) {
                        if (a.type() == TypeAbsence.RETARD) {
                            continue;
                        }
                        nbAbsences++;
                        eleves.add(a.inscriptionId());
                        long m = Duration.between(a.heureDebut(), a.heureFin()).toMinutes();
                        minutes += m;
                        if (!a.justifiee()) {
                            nonJustifiees += m;
                        }
                    }
                    return new AbsencesParMatiereVue(premiere.matiereId(), premiere.matiereCode(),
                            premiere.matiereLibelle(), nbAbsences, heures(minutes), heures(nonJustifiees),
                            eleves.size(), liste.size() - nbAbsences);
                })
                .sorted(Comparator.comparing(AbsencesParMatiereVue::heures).reversed()
                        .thenComparing(v -> v.matiereLibelle() == null ? "~" : v.matiereLibelle()))
                .toList();
    }

    /**
     * Élèves absents ou en retard dans tout l'établissement un jour donné, par classe puis
     * par nom, avec leurs créneaux et l'état de justification (justifiée si un justificatif
     * couvre la date).
     */
    @Transactional(readOnly = true)
    public List<EleveDuJourVue> duJour(LocalDate date) {
        UtilisateurConnecte.etablissementActif();
        Map<UUID, List<CreneauVue>> parInscription = new LinkedHashMap<>();
        Map<UUID, UUID> classeDe = new HashMap<>();
        Map<UUID, MatiereVue> disciplines = disciplines();
        for (Object[] ligne : absences.duJour(date)) {
            Absence a = (Absence) ligne[0];
            Appel p = (Appel) ligne[1];
            parInscription.computeIfAbsent(a.getInscriptionId(), k -> new ArrayList<>())
                    .add(creneau(a, p, disciplines.get(p.getMatiereId())));
            classeDe.put(a.getInscriptionId(), p.getClasseId());
        }
        if (parInscription.isEmpty()) {
            return List.of();
        }
        Map<UUID, InscriptionVue> inscriptionsParId = new HashMap<>();
        for (UUID classeId : new HashSet<>(classeDe.values())) {
            inscriptions.listerParClasse(classeId, true).forEach(i -> inscriptionsParId.put(i.id(), i));
        }
        Map<UUID, List<Justificatif>> couvertures = justificatifs.findByInscriptionIdIn(parInscription.keySet())
                .stream().collect(Collectors.groupingBy(Justificatif::getInscriptionId));
        return parInscription.entrySet().stream()
                .map(e -> {
                    InscriptionVue i = inscriptionsParId.get(e.getKey());
                    List<CreneauVue> creneaux = e.getValue();
                    boolean justifiee = couvertures.getOrDefault(e.getKey(), List.of()).stream()
                            .anyMatch(j -> j.couvre(date));
                    int nbAbsences = (int) creneaux.stream().filter(c -> c.type() == TypeAbsence.ABSENCE).count();
                    return new EleveDuJourVue(e.getKey(), i != null ? i.eleveId() : null,
                            i != null ? i.matricule() : null, i != null ? i.nom() : "?", i != null ? i.prenoms() : "",
                            classeDe.get(e.getKey()), i != null ? i.classeCode() : null, nbAbsences,
                            creneaux.size() - nbAbsences, justifiee, creneaux);
                })
                .sorted(Comparator.comparing((EleveDuJourVue v) -> v.classeCode() == null ? "" : v.classeCode())
                        .thenComparing(EleveDuJourVue::nom).thenComparing(EleveDuJourVue::prenoms))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<JustificatifVue> justificatifs(UUID inscriptionId) {
        inscriptions.trouver(inscriptionId);
        return justificatifs.findByInscriptionIdOrderByDuDesc(inscriptionId).stream().map(JustificatifVue::depuis)
                .toList();
    }

    /** Justifie les absences d'un élève sur une période (certificat médical, convocation…). */
    @Transactional
    public JustificatifVue justifier(UUID inscriptionId, LocalDate du, LocalDate au, TypeJustificatif type,
            String motif) {
        InscriptionVue inscription = inscriptions.trouver(inscriptionId);
        if (au.isBefore(du)) {
            throw new IllegalArgumentException("La fin de la période précède son début");
        }
        if (Duration.between(du.atStartOfDay(), au.atStartOfDay()).toDays() > 366) {
            throw new IllegalArgumentException("Un justificatif couvre au plus une année");
        }
        String texte = motif == null || motif.isBlank() ? null : motif.trim();
        if (texte != null && texte.length() > 200) {
            throw new IllegalArgumentException("Le motif dépasse 200 caractères");
        }
        Justificatif justificatif = justificatifs.save(new Justificatif(inscriptionId, du, au, type, texte,
                UtilisateurConnecte.idSiConnecte().orElse(null)));
        audit.enregistrer("ABSENCES_JUSTIFIEES", inscription.matricule(),
                Map.of("du", du, "au", au, "type", type.name()));
        return JustificatifVue.depuis(justificatif);
    }

    @Transactional
    public void supprimerJustificatif(UUID justificatifId) {
        UtilisateurConnecte.etablissementActif();
        Justificatif justificatif = justificatifs.findById(justificatifId)
                .orElseThrow(() -> new RessourceIntrouvableException("Justificatif introuvable"));
        justificatifs.delete(justificatif);
        audit.enregistrer("JUSTIFICATIF_SUPPRIME", justificatifId.toString(), null);
    }

    // ------------------------------------------------------------------

    private List<AbsenceVue> absencesDe(Collection<UUID> inscriptionIds, LocalDate du, LocalDate au) {
        Map<UUID, List<Justificatif>> couvertures = justificatifs.findByInscriptionIdIn(inscriptionIds).stream()
                .collect(Collectors.groupingBy(Justificatif::getInscriptionId));
        Map<UUID, MatiereVue> disciplines = disciplines();
        return absences.avecAppels(inscriptionIds, du != null ? du : TOUJOURS_DEBUT, au != null ? au : TOUJOURS_FIN)
                .stream()
                .map(ligne -> {
                    Absence a = (Absence) ligne[0];
                    Appel p = (Appel) ligne[1];
                    boolean justifiee = couvertures.getOrDefault(a.getInscriptionId(), List.of()).stream()
                            .anyMatch(j -> j.couvre(p.getDateAppel()));
                    MatiereVue m = disciplines.get(p.getMatiereId());
                    return new AbsenceVue(a.getId(), p.getId(), a.getInscriptionId(), p.getDateAppel(),
                            p.getHeureDebut(), p.getHeureFin(), a.getType(),
                            a.getMinutesRetard() != null ? a.getMinutesRetard().intValue() : null, justifiee,
                            p.getMatiereId(), m != null ? m.code() : null, m != null ? m.libelle() : null);
                })
                .toList();
    }

    /** Disciplines de l'établissement, pour nommer la matière de chaque appel. */
    private Map<UUID, MatiereVue> disciplines() {
        return matieres.lister().stream().collect(Collectors.toMap(MatiereVue::id, m -> m));
    }

    private static CreneauVue creneau(Absence a, Appel p, MatiereVue m) {
        return new CreneauVue(p.getId(), p.getHeureDebut(), p.getHeureFin(), a.getType(),
                a.getMinutesRetard() != null ? a.getMinutesRetard().intValue() : null, p.getMatiereId(),
                m != null ? m.code() : null, m != null ? m.libelle() : null);
    }

    private static SyntheseEleveVue synthese(InscriptionVue inscription, List<AbsenceVue> liste) {
        int nbAbsences = 0;
        int justifiees = 0;
        int retards = 0;
        long minutesAbsence = 0;
        long minutesNonJustifiees = 0;
        for (AbsenceVue a : liste) {
            if (a.type() == TypeAbsence.RETARD) {
                retards++;
                continue;
            }
            nbAbsences++;
            long minutes = Duration.between(a.heureDebut(), a.heureFin()).toMinutes();
            minutesAbsence += minutes;
            if (a.justifiee()) {
                justifiees++;
            } else {
                minutesNonJustifiees += minutes;
            }
        }
        return new SyntheseEleveVue(inscription.id(), inscription.nom(), inscription.prenoms(), nbAbsences,
                justifiees, retards, heures(minutesAbsence), heures(minutesNonJustifiees));
    }

    private static BigDecimal heures(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
    }
}
