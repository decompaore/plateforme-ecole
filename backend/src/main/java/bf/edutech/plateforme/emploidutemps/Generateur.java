package bf.edutech.plateforme.emploidutemps;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import bf.edutech.plateforme.etablissement.TypeMatiere;

/**
 * Proposition automatique d'emploi du temps : place, dans les cases encore libres, les heures
 * qui manquent à chaque matière. Glouton et déterministe (même entrée, même résultat) :
 * <ul>
 * <li>les séances pratiques et les modules d'abord (blocs de 2 à 4 heures consécutives dans un
 * atelier de la filière), puis les matières techniques, puis les générales (blocs de 1 ou 2 heures) ;</li>
 * <li>les enseignants les plus chargés d'abord, car ils ont le moins de choix ;</li>
 * <li>une seule séance d'une même matière par jour quand c'est possible, des journées équilibrées
 * et sans trous, les matières générales plutôt le matin ;</li>
 * <li>jamais de conflit : classe, enseignant (y compris ses heures dans d'autres établissements) et
 * atelier libres.</li>
 * </ul>
 * Ce que le générateur ne peut pas placer est signalé ; le censeur et le chef des travaux
 * terminent à la main.
 */
final class Generateur {

    /** Créneau de la grille. */
    record Case(UUID creneauId, LocalTime debut, LocalTime fin, Set<Integer> jours) {

        int minutes() {
            return (int) Duration.between(debut, fin).toMinutes();
        }
    }

    /** Heures à placer pour une matière d'une classe. */
    record Besoin(UUID classeId, String classeCode, UUID filiereId, UUID matiereId, String matiereLibelle,
            TypeMatiere type, UUID engagementId, int minutes) {
    }

    record Placement(UUID classeId, UUID matiereId, int jour, UUID creneauId, UUID atelierId) {
    }

    record Manque(Besoin besoin, int minutes, String raison) {
    }

    record Resultat(List<Placement> placements, List<Manque> manques) {
    }

    /** Écart maximal entre deux heures d'un même bloc (une récréation, pas la pause de midi). */
    static final int PAUSE_DANS_UN_BLOC = 20;

    private final List<Case> cases;
    private final List<Integer> jours;
    private final Map<UUID, List<UUID>> ateliersParFiliere;
    private final int dureeMin;
    private final int dureeMoyenne;

    private final Set<String> classesOccupees = new HashSet<>();
    private final Set<String> enseignantsOccupes = new HashSet<>();
    private final Set<String> ateliersOccupes = new HashSet<>();
    private final Set<String> joursDeLaMatiere = new HashSet<>();
    private final Map<String, Integer> chargeClasseJour = new HashMap<>();
    private final Map<String, Integer> chargeEnseignantJour = new HashMap<>();

    Generateur(List<Case> cases, Map<UUID, List<UUID>> ateliersParFiliere) {
        this.cases = cases.stream().sorted(Comparator.comparing(Case::debut)).toList();
        Set<Integer> j = new TreeSet<>();
        cases.forEach(c -> j.addAll(c.jours()));
        this.jours = new ArrayList<>(j);
        this.ateliersParFiliere = ateliersParFiliere;
        this.dureeMin = cases.stream().mapToInt(Case::minutes).min().orElse(60);
        this.dureeMoyenne = (int) Math.round(cases.stream().mapToInt(Case::minutes).average().orElse(60));
    }

    // ------------------------------------------------------------------ ce qui est déjà placé

    /** Séance existante ({@code groupe} non null : la case reste prise pour toute la classe). */
    void dejaPlace(UUID classeId, UUID matiereId, UUID engagementId, UUID atelierId, int jour, UUID creneauId) {
        occuper(classeId, matiereId, engagementId, atelierId, jour, creneauId);
    }

    /** Heures prises par l'enseignant dans un autre établissement. */
    void prisAilleurs(UUID engagementId, int jour, LocalTime debut, LocalTime fin) {
        for (Case c : cases) {
            if (c.debut().isBefore(fin) && debut.isBefore(c.fin())) {
                enseignantsOccupes.add(cle(engagementId, jour, c.creneauId()));
            }
        }
    }

    // ------------------------------------------------------------------ génération

    Resultat generer(List<Besoin> besoins) {
        Map<UUID, Integer> chargeEnseignant = new HashMap<>();
        for (Besoin b : besoins) {
            if (b.engagementId() != null) {
                chargeEnseignant.merge(b.engagementId(), b.minutes(), Integer::sum);
            }
        }
        List<Besoin> ordre = new ArrayList<>(besoins);
        ordre.sort(Comparator.<Besoin>comparingInt(b -> rang(b.type()))
                .thenComparing(b -> -chargeEnseignant.getOrDefault(b.engagementId(), 0))
                .thenComparing(b -> -b.minutes())
                .thenComparing(Besoin::classeCode)
                .thenComparing(Besoin::matiereLibelle));
        List<Placement> placements = new ArrayList<>();
        List<Manque> manques = new ArrayList<>();
        for (Besoin b : ordre) {
            int reste = b.minutes();
            while (reste * 2 >= dureeMin) {
                int voulu = Math.max(1, Math.round((float) reste / dureeMoyenne));
                List<Placement> bloc = null;
                for (int taille = Math.min(blocMax(b.type()), voulu); taille >= 1 && bloc == null; taille--) {
                    bloc = meilleurBloc(b, taille, reste);
                }
                if (bloc == null) {
                    manques.add(new Manque(b, reste, besoinAtelier(b)
                            ? "Aucune case où la classe, l'enseignant et un atelier de la filière sont libres ensemble"
                            : "Aucune case où la classe et l'enseignant sont libres ensemble"));
                    break;
                }
                for (Placement p : bloc) {
                    occuper(p.classeId(), p.matiereId(), b.engagementId(), p.atelierId(), p.jour(), p.creneauId());
                    reste -= caseDe(p.creneauId()).minutes();
                }
                placements.addAll(bloc);
            }
        }
        return new Resultat(placements, manques);
    }

    private List<Placement> meilleurBloc(Besoin b, int taille, int reste) {
        List<Placement> meilleur = null;
        int meilleurScore = Integer.MAX_VALUE;
        for (int jour : jours) {
            for (int i = 0; i + taille <= cases.size(); i++) {
                List<Case> bloc = cases.subList(i, i + taille);
                if (!consecutifs(bloc, jour)) {
                    continue;
                }
                int duree = bloc.stream().mapToInt(Case::minutes).sum();
                // Un bloc ne dépasse pas le besoin de plus d'une demi-heure de cours
                if (taille > 1 && duree - reste > dureeMin / 2) {
                    continue;
                }
                if (!libres(b, bloc, jour)) {
                    continue;
                }
                UUID atelier = null;
                if (besoinAtelier(b)) {
                    List<UUID> possibles = ateliersParFiliere.getOrDefault(b.filiereId(), List.of());
                    if (!possibles.isEmpty()) {
                        atelier = possibles.stream().filter(a -> bloc.stream()
                                .noneMatch(c -> ateliersOccupes.contains(cle(a, jour, c.creneauId())))).findFirst()
                                .orElse(null);
                        if (atelier == null) {
                            continue;
                        }
                    }
                }
                int score = score(b, bloc, jour, i);
                if (score < meilleurScore) {
                    meilleurScore = score;
                    List<Placement> l = new ArrayList<>();
                    for (Case c : bloc) {
                        l.add(new Placement(b.classeId(), b.matiereId(), jour, c.creneauId(), atelier));
                    }
                    meilleur = l;
                }
            }
        }
        return meilleur;
    }

    private int score(Besoin b, List<Case> bloc, int jour, int debut) {
        int score = 0;
        if (joursDeLaMatiere.contains(b.classeId() + "|" + b.matiereId() + "|" + jour)) {
            score += 1000;
        }
        int chargeJour = chargeClasseJour.getOrDefault(b.classeId() + "|" + jour, 0);
        score += chargeJour * 10;
        if (b.engagementId() != null) {
            score += chargeEnseignantJour.getOrDefault(b.engagementId() + "|" + jour, 0) * 3;
        }
        if (b.type() == TypeMatiere.GENERALE && !bloc.get(0).debut().isBefore(LocalTime.NOON)) {
            score += 5;
        }
        if (chargeJour > 0) {
            boolean colle = (debut > 0 && classesOccupees.contains(cle(b.classeId(), jour, cases.get(debut - 1).creneauId())))
                    || (debut + bloc.size() < cases.size()
                            && classesOccupees.contains(cle(b.classeId(), jour, cases.get(debut + bloc.size()).creneauId())));
            if (!colle) {
                score += 4;
            }
        }
        return score;
    }

    private boolean consecutifs(List<Case> bloc, int jour) {
        for (int k = 0; k < bloc.size(); k++) {
            if (!bloc.get(k).jours().contains(jour)) {
                return false;
            }
            if (k > 0 && Duration.between(bloc.get(k - 1).fin(), bloc.get(k).debut()).toMinutes() > PAUSE_DANS_UN_BLOC) {
                return false;
            }
        }
        return true;
    }

    private boolean libres(Besoin b, List<Case> bloc, int jour) {
        for (Case c : bloc) {
            if (classesOccupees.contains(cle(b.classeId(), jour, c.creneauId()))) {
                return false;
            }
            if (b.engagementId() != null && enseignantsOccupes.contains(cle(b.engagementId(), jour, c.creneauId()))) {
                return false;
            }
        }
        return true;
    }

    private void occuper(UUID classeId, UUID matiereId, UUID engagementId, UUID atelierId, int jour, UUID creneauId) {
        classesOccupees.add(cle(classeId, jour, creneauId));
        joursDeLaMatiere.add(classeId + "|" + matiereId + "|" + jour);
        chargeClasseJour.merge(classeId + "|" + jour, 1, Integer::sum);
        if (engagementId != null) {
            enseignantsOccupes.add(cle(engagementId, jour, creneauId));
            chargeEnseignantJour.merge(engagementId + "|" + jour, 1, Integer::sum);
        }
        if (atelierId != null) {
            ateliersOccupes.add(cle(atelierId, jour, creneauId));
        }
    }

    private Case caseDe(UUID creneauId) {
        return cases.stream().filter(c -> c.creneauId().equals(creneauId)).findFirst().orElseThrow();
    }

    private static String cle(UUID qui, int jour, UUID creneauId) {
        return qui + "|" + jour + "|" + creneauId;
    }

    static boolean besoinAtelier(Besoin b) {
        return b.type() == TypeMatiere.PRATIQUE || b.type() == TypeMatiere.MODULE_COMPETENCES;
    }

    private static int blocMax(TypeMatiere t) {
        return t == TypeMatiere.PRATIQUE || t == TypeMatiere.MODULE_COMPETENCES ? 4 : 2;
    }

    private static int rang(TypeMatiere t) {
        return switch (t) {
            case PRATIQUE, MODULE_COMPETENCES -> 0;
            case TECHNIQUE -> 1;
            case GENERALE -> 2;
        };
    }
}
