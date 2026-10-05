package bf.edutech.plateforme.emploidutemps;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import bf.edutech.plateforme.ateliers.Vues.AtelierCourtVue;
import bf.edutech.plateforme.emploidutemps.Vues.ConflitVue;
import bf.edutech.plateforme.emploidutemps.Vues.Domaine;
import bf.edutech.plateforme.emploidutemps.Vues.TypeConflit;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;

/**
 * Tout l'emploi du temps d'une année, chargé une fois par requête : grille, classes et leur
 * programme, séances, enseignants, ateliers et heures prises ailleurs par les vacataires.
 */
final class Instantane {

    static final String[] JOURS = { "", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche" };

    /** Heure prise dans un autre établissement. */
    record Occupation(UUID engagementId, int jour, LocalTime debut, LocalTime fin) {

        boolean chevauche(int j, LocalTime d, LocalTime f) {
            return jour == j && debut.isBefore(f) && d.isBefore(fin);
        }
    }

    final AnneeVue annee;
    final List<Creneau> creneaux;
    final Map<UUID, Creneau> creneauParId = new HashMap<>();
    final List<ClasseVue> classes;
    final Map<UUID, ClasseVue> classeParId = new LinkedHashMap<>();
    final Map<UUID, List<MatiereDeClasseVue>> programme;
    final List<SeanceEmploi> seances;
    final Map<UUID, String> noms;
    final List<AtelierCourtVue> ateliers;
    final Map<UUID, AtelierCourtVue> atelierParId = new HashMap<>();
    final List<Occupation> ailleurs;

    Instantane(AnneeVue annee, List<Creneau> creneaux, List<ClasseVue> classes,
            Map<UUID, List<MatiereDeClasseVue>> programme, List<SeanceEmploi> seances, Map<UUID, String> noms,
            List<AtelierCourtVue> ateliers, List<Occupation> ailleurs) {
        this.annee = annee;
        this.creneaux = creneaux;
        creneaux.forEach(c -> creneauParId.put(c.getId(), c));
        this.classes = classes;
        classes.forEach(c -> classeParId.put(c.id(), c));
        this.programme = programme;
        this.seances = seances;
        this.noms = noms;
        this.ateliers = ateliers;
        ateliers.forEach(a -> atelierParId.put(a.id(), a));
        this.ailleurs = ailleurs;
    }

    MatiereDeClasseVue matiere(UUID classeId, UUID matiereId) {
        return programme.getOrDefault(classeId, List.of()).stream().filter(m -> m.matiereId().equals(matiereId))
                .findFirst().orElse(null);
    }

    UUID engagement(SeanceEmploi s) {
        MatiereDeClasseVue m = matiere(s.getClasseId(), s.getMatiereId());
        return m == null ? null : m.engagementId();
    }

    Domaine domaine(SeanceEmploi s) {
        MatiereDeClasseVue m = matiere(s.getClasseId(), s.getMatiereId());
        return m == null ? Domaine.GENERAL : Domaine.de(m.type());
    }

    /** Jours de la semaine où au moins un créneau existe. */
    List<Integer> jours() {
        Set<Integer> j = new TreeSet<>();
        creneaux.forEach(c -> j.addAll(c.jours()));
        return new ArrayList<>(j);
    }

    /** Demi-classes utilisées dans les séances d'une classe. */
    List<String> groupes(UUID classeId) {
        return seances.stream().filter(s -> s.getClasseId().equals(classeId) && s.getGroupe() != null)
                .map(SeanceEmploi::getGroupe).distinct().sorted().toList();
    }

    /**
     * Minutes hebdomadaires placées pour une matière d'une classe : les séances de toute la classe,
     * plus (si la classe travaille en groupes) le minimum reçu par chacun de ses groupes.
     */
    int minutesPlacees(UUID classeId, UUID matiereId) {
        int entiere = 0;
        Map<String, Integer> parGroupe = new HashMap<>();
        groupes(classeId).forEach(g -> parGroupe.put(g, 0));
        for (SeanceEmploi s : seances) {
            if (!s.getClasseId().equals(classeId) || !s.getMatiereId().equals(matiereId)) {
                continue;
            }
            Creneau c = creneauParId.get(s.getCreneauId());
            int m = c == null ? 0 : c.minutes();
            if (s.getGroupe() == null) {
                entiere += m;
            } else {
                parGroupe.merge(s.getGroupe(), m, Integer::sum);
            }
        }
        boolean auMoinsUn = parGroupe.values().stream().anyMatch(v -> v > 0);
        return entiere + (auMoinsUn ? parGroupe.values().stream().min(Integer::compare).orElse(0) : 0);
    }

    static int minutesPrevues(BigDecimal volumeHebdo) {
        return volumeHebdo == null ? 0 : volumeHebdo.multiply(BigDecimal.valueOf(60)).intValue();
    }

    // ------------------------------------------------------------------ conflits

    /** Conflits de tout l'emploi du temps (une affectation ou une autre école peut en créer après coup). */
    List<ConflitVue> conflits() {
        List<ConflitVue> liste = new ArrayList<>();
        Map<String, List<SeanceEmploi>> parCase = seances.stream()
                .collect(Collectors.groupingBy(s -> s.getJour() + "|" + s.getCreneauId(), LinkedHashMap::new,
                        Collectors.toList()));
        for (List<SeanceEmploi> memeCase : parCase.values()) {
            for (int i = 0; i < memeCase.size(); i++) {
                for (int k = i + 1; k < memeCase.size(); k++) {
                    SeanceEmploi a = memeCase.get(i);
                    SeanceEmploi b = memeCase.get(k);
                    String conflit = conflit(a, b.getClasseId(), b.getMatiereId(), b.getGroupe(), b.getAtelierId());
                    if (conflit != null) {
                        liste.add(new ConflitVue(typeConflit(a, b), List.of(a.getId(), b.getId()), conflit));
                    }
                }
            }
        }
        for (SeanceEmploi s : seances) {
            String ailleursMsg = ailleurs(engagement(s), s.getJour(), creneauParId.get(s.getCreneauId()));
            if (ailleursMsg != null) {
                liste.add(new ConflitVue(TypeConflit.ENSEIGNANT_AILLEURS, List.of(s.getId()), ailleursMsg));
            }
        }
        liste.sort(Comparator.comparing(ConflitVue::message));
        return liste;
    }

    private TypeConflit typeConflit(SeanceEmploi a, SeanceEmploi b) {
        if (a.getClasseId().equals(b.getClasseId()) && memeGroupe(a.getGroupe(), b.getGroupe())) {
            return TypeConflit.CLASSE;
        }
        UUID ea = engagement(a);
        if (ea != null && ea.equals(engagement(b))) {
            return TypeConflit.ENSEIGNANT;
        }
        return TypeConflit.ATELIER;
    }

    /**
     * Ce qui empêche de placer (classe, matière, groupe, atelier) dans la même case qu'une séance
     * existante, ou null si les deux peuvent coexister.
     */
    String conflit(SeanceEmploi existante, UUID classeId, UUID matiereId, String groupe, UUID atelierId) {
        String quand = quand(existante.getJour(), creneauParId.get(existante.getCreneauId()));
        if (existante.getClasseId().equals(classeId) && memeGroupe(existante.getGroupe(), groupe)) {
            return classe(classeId) + (groupe != null && existante.getGroupe() != null ? " (" + groupe + ")" : "")
                    + " a déjà " + libelle(existante) + " " + quand;
        }
        UUID e1 = engagement(existante);
        MatiereDeClasseVue m = matiere(classeId, matiereId);
        UUID e2 = m == null ? null : m.engagementId();
        if (e1 != null && e1.equals(e2)) {
            return noms.getOrDefault(e1, "L'enseignant") + " a déjà cours en " + classe(existante.getClasseId())
                    + " " + quand;
        }
        if (atelierId != null && atelierId.equals(existante.getAtelierId())) {
            AtelierCourtVue a = atelierParId.get(atelierId);
            return "L'atelier " + (a == null ? "" : a.code() + " ") + "accueille déjà la "
                    + classe(existante.getClasseId()) + " " + quand;
        }
        return null;
    }

    /** Le vacataire est-il pris ailleurs à ce moment ? Message, ou null. */
    String ailleurs(UUID engagementId, int jour, Creneau c) {
        if (engagementId == null || c == null) {
            return null;
        }
        boolean pris = ailleurs.stream().anyMatch(o -> o.engagementId().equals(engagementId)
                && o.chevauche(jour, c.getHeureDebut(), c.getHeureFin()));
        return pris ? noms.getOrDefault(engagementId, "L'enseignant")
                + " a déjà cours dans un autre établissement " + quand(jour, c) : null;
    }

    static boolean memeGroupe(String a, String b) {
        return a == null || b == null || a.equals(b);
    }

    String classe(UUID classeId) {
        ClasseVue c = classeParId.get(classeId);
        return c == null ? "la classe" : c.code();
    }

    private String libelle(SeanceEmploi s) {
        MatiereDeClasseVue m = matiere(s.getClasseId(), s.getMatiereId());
        return m == null ? "cours" : m.matiereLibelle();
    }

    static String quand(int jour, Creneau c) {
        return "le " + JOURS[jour] + (c == null ? "" : " à " + heure(c.getHeureDebut()));
    }

    static String heure(LocalTime t) {
        return String.format("%02dh%02d", t.getHour(), t.getMinute());
    }
}
