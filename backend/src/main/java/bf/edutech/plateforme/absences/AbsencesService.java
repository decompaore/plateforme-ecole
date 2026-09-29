package bf.edutech.plateforme.absences;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.absences.Vues.AbsenceVue;
import bf.edutech.plateforme.absences.Vues.JustificatifVue;
import bf.edutech.plateforme.absences.Vues.SyntheseEleveVue;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
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

    AbsencesService(AbsenceRepository absences, JustificatifRepository justificatifs,
            InscriptionsService inscriptions, ElevesService eleves, Autorisations autorisations, AuditService audit) {
        this.absences = absences;
        this.justificatifs = justificatifs;
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.autorisations = autorisations;
        this.audit = audit;
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
        return absences.avecAppels(inscriptionIds, du != null ? du : TOUJOURS_DEBUT, au != null ? au : TOUJOURS_FIN)
                .stream()
                .map(ligne -> {
                    Absence a = (Absence) ligne[0];
                    Appel p = (Appel) ligne[1];
                    boolean justifiee = couvertures.getOrDefault(a.getInscriptionId(), List.of()).stream()
                            .anyMatch(j -> j.couvre(p.getDateAppel()));
                    return new AbsenceVue(a.getId(), p.getId(), a.getInscriptionId(), p.getDateAppel(),
                            p.getHeureDebut(), p.getHeureFin(), a.getType(),
                            a.getMinutesRetard() != null ? a.getMinutesRetard().intValue() : null, justifiee);
                })
                .toList();
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
