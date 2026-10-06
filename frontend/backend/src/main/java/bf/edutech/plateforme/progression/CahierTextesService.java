package bf.edutech.plateforme.progression;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.progression.AccesMatiere.Contexte;
import bf.edutech.plateforme.progression.Vues.DonneesSeance;
import bf.edutech.plateforme.progression.Vues.SeanceVue;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Cahier de textes : après chaque cours, l'enseignant note ce qui a été fait et le travail
 * donné, en le rattachant si possible à une séquence de sa fiche de progression. La direction
 * du domaine le lit et compare le réalisé au prévu.
 */
@Service
public class CahierTextesService {

    private static final Duration DUREE_MAX = Duration.ofHours(8);

    private final SeanceCahierRepository seances;
    private final FicheProgressionRepository fiches;
    private final SequenceProgressionRepository sequences;
    private final AccesMatiere acces;
    private final Clock horloge;

    CahierTextesService(SeanceCahierRepository seances, FicheProgressionRepository fiches,
            SequenceProgressionRepository sequences, AccesMatiere acces, Clock horloge) {
        this.seances = seances;
        this.fiches = fiches;
        this.sequences = sequences;
        this.acces = acces;
        this.horloge = horloge;
    }

    /** Séances d'une matière, la plus récente d'abord : pour son enseignant et la direction du domaine. */
    @Transactional(readOnly = true)
    public List<SeanceVue> seances(UUID classeId, UUID matiereId) {
        acces.contexte(classeId, matiereId).exigerLecture();
        return seances.findByClasseIdAndMatiereIdOrderByDateDescHeureDebutDesc(classeId, matiereId).stream()
                .map(CahierTextesService::vue).toList();
    }

    /**
     * Crée ou modifie une séance. Idempotent : le téléphone choisit l'identifiant, un renvoi
     * après une coupure modifie la même séance.
     */
    @Transactional
    public SeanceVue enregistrer(UUID id, DonneesSeance d) {
        if (d == null || d.classeId() == null || d.matiereId() == null) {
            throw new IllegalArgumentException("Classe et matière obligatoires");
        }
        Contexte c = acces.contexte(d.classeId(), d.matiereId());
        c.exigerAuteur("tient son cahier de textes");
        valider(d);
        SeanceCahier existante = seances.findById(id).orElse(null);
        if (existante != null && (!existante.getClasseId().equals(d.classeId())
                || !existante.getMatiereId().equals(d.matiereId()))) {
            throw new RegleMetierException("IDENTIFIANT_DEJA_UTILISE", "Cet identifiant de séance appartient à un autre cours");
        }
        seances.findByClasseIdAndMatiereIdAndDateAndHeureDebut(d.classeId(), d.matiereId(), d.date(), d.heureDebut())
                .filter(autre -> !autre.getId().equals(id))
                .ifPresent(autre -> {
                    throw new RegleMetierException("SEANCE_EXISTANTE", "Une séance est déjà notée le " + d.date()
                            + " à " + d.heureDebut() + " : modifiez-la plutôt que d'en créer une seconde");
                });
        Instant maintenant = horloge.instant();
        SeanceCahier seance = existante != null ? existante
                : new SeanceCahier(id, d.classeId(), d.matiereId(), UtilisateurConnecte.id(), maintenant);
        // Séquence de la fiche : son titre est gardé tel qu'au jour du cours ; un numéro inconnu
        // (fiche raccourcie depuis la saisie hors connexion) laisse la séance hors séquence
        Integer demande = d.sequenceOrdre();
        String titre = demande == null ? null
                : fiches.findByClasseIdAndMatiereId(d.classeId(), d.matiereId())
                        .flatMap(f -> sequences.findByFicheIdOrderByOrdre(f.getId()).stream()
                                .filter(s -> s.getOrdre() == demande).findFirst())
                        .map(SequenceProgression::getTitre).orElse(null);
        Integer ordre = titre == null ? null : demande;
        seance.remplir(c.matiere().engagementId(), d.date(), d.heureDebut(), d.heureFin(), ordre, titre,
                d.contenu().strip(), texte(d.travailAFaire()), maintenant);
        return vue(seances.save(seance));
    }

    @Transactional
    public void supprimer(UUID id) {
        SeanceCahier seance = seances.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Séance introuvable"));
        Contexte c = acces.contexte(seance.getClasseId(), seance.getMatiereId());
        if (!c.auteur()) {
            throw new AccesRefuseException("Seul l'enseignant de la matière supprime une séance de son cahier");
        }
        seances.delete(seance);
    }

    private void valider(DonneesSeance d) {
        if (d.date() == null || d.heureDebut() == null || d.heureFin() == null) {
            throw new IllegalArgumentException("Date, heure de début et heure de fin obligatoires");
        }
        if (d.date().isAfter(LocalDate.now(horloge))) {
            throw new RegleMetierException("DATE_FUTURE", "Le cahier de textes se remplit après le cours, pas avant");
        }
        if (!d.heureFin().isAfter(d.heureDebut())) {
            throw new IllegalArgumentException("L'heure de fin doit suivre l'heure de début");
        }
        if (Duration.between(d.heureDebut(), d.heureFin()).compareTo(DUREE_MAX) > 0) {
            throw new IllegalArgumentException("Une séance dure 8 heures au plus");
        }
        if (d.contenu() == null || d.contenu().isBlank()) {
            throw new IllegalArgumentException("Notez ce qui a été fait pendant le cours");
        }
        if (d.contenu().strip().length() > 2000) {
            throw new IllegalArgumentException("Contenu de 2 000 caractères au plus");
        }
        if (d.travailAFaire() != null && d.travailAFaire().strip().length() > 1000) {
            throw new IllegalArgumentException("Travail à faire de 1 000 caractères au plus");
        }
    }

    private static String texte(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static SeanceVue vue(SeanceCahier s) {
        return new SeanceVue(s.getId(), s.getClasseId(), s.getMatiereId(), s.getDate(), s.getHeureDebut(),
                s.getHeureFin(), s.heures(), s.getSequenceOrdre(), s.getSequenceTitre(), s.getContenu(),
                s.getTravailAFaire(), s.getSaisiLe(), s.getModifieLe());
    }
}
