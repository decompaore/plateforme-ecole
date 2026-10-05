package bf.edutech.plateforme.emploidutemps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bf.edutech.plateforme.emploidutemps.Generateur.Besoin;
import bf.edutech.plateforme.emploidutemps.Generateur.Case;
import bf.edutech.plateforme.emploidutemps.Generateur.Placement;
import bf.edutech.plateforme.emploidutemps.Generateur.Resultat;
import bf.edutech.plateforme.etablissement.TypeMatiere;

/** Générateur d'emploi du temps, sans base de données. */
class GenerateurTest {

    private static final Set<Integer> SEMAINE = Set.of(1, 2, 3, 4, 5);

    /** 5 heures le matin (récréation de 15 min après la 3e), 2 l'après-midi sauf le mercredi. */
    private static List<Case> grille() {
        List<Case> cases = new ArrayList<>();
        String[][] matin = { { "07:00", "08:00" }, { "08:00", "09:00" }, { "09:00", "10:00" }, { "10:15", "11:15" },
                { "11:15", "12:15" } };
        for (String[] h : matin) {
            cases.add(new Case(UUID.randomUUID(), LocalTime.parse(h[0]), LocalTime.parse(h[1]), SEMAINE));
        }
        cases.add(new Case(UUID.randomUUID(), LocalTime.parse("15:00"), LocalTime.parse("16:00"), Set.of(1, 2, 4, 5)));
        cases.add(new Case(UUID.randomUUID(), LocalTime.parse("16:00"), LocalTime.parse("17:00"), Set.of(1, 2, 4, 5)));
        return cases;
    }

    @Test
    void placeToutSansConflitEtRegroupeLesTravauxPratiquesDansUnAtelier() {
        List<Case> cases = grille();
        UUID filiere = UUID.randomUUID();
        UUID atelier = UUID.randomUUID();
        Generateur g = new Generateur(cases, Map.of(filiere, List.of(atelier)));
        UUID maths = UUID.randomUUID();
        UUID tp = UUID.randomUUID();
        UUID prof = UUID.randomUUID();
        UUID profTp = UUID.randomUUID();
        List<Besoin> besoins = new ArrayList<>();
        List<UUID> classes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID c = UUID.randomUUID();
            classes.add(c);
            // Le même professeur de maths et le même atelier pour les trois classes
            besoins.add(new Besoin(c, "C" + i, filiere, maths, "Maths", TypeMatiere.GENERALE, prof, 240));
            besoins.add(new Besoin(c, "C" + i, filiere, tp, "TP", TypeMatiere.PRATIQUE, profTp, 240));
        }
        Resultat r = g.generer(besoins);

        assertTrue(r.manques().isEmpty(), () -> "Manques : " + r.manques());
        assertEquals(24, r.placements().size());
        Set<String> classesCases = new HashSet<>();
        Set<String> profs = new HashSet<>();
        Set<String> ateliers = new HashSet<>();
        Map<String, Integer> mathsParJour = new HashMap<>();
        for (Placement p : r.placements()) {
            String cas = p.jour() + "|" + p.creneauId();
            assertTrue(classesCases.add(p.classeId() + "|" + cas), "classe en double");
            UUID enseignant = p.matiereId().equals(maths) ? prof : profTp;
            assertTrue(profs.add(enseignant + "|" + cas), "enseignant en double");
            if (p.matiereId().equals(tp)) {
                assertEquals(atelier, p.atelierId());
                assertTrue(ateliers.add(atelier + "|" + cas), "atelier en double");
            } else {
                mathsParJour.merge(p.classeId() + "|" + p.jour(), 1, Integer::sum);
            }
        }
        // Les maths : jamais plus de 2 heures le même jour dans une classe
        assertTrue(mathsParJour.values().stream().allMatch(n -> n <= 2), mathsParJour::toString);
        // Le TP de chaque classe : un bloc de 4 heures consécutives le même jour
        for (UUID c : classes) {
            List<Placement> tps = r.placements().stream().filter(p -> p.classeId().equals(c) && p.matiereId().equals(tp))
                    .toList();
            assertEquals(4, tps.size());
            assertEquals(1, tps.stream().map(Placement::jour).distinct().count());
        }
    }

    @Test
    void respecteLesHeuresPrisesAilleursEtSignaleCeQuiNePeutPasEtrePlace() {
        List<Case> cases = List.of(
                new Case(UUID.randomUUID(), LocalTime.parse("08:00"), LocalTime.parse("09:00"), Set.of(1)),
                new Case(UUID.randomUUID(), LocalTime.parse("09:00"), LocalTime.parse("10:00"), Set.of(1)));
        Generateur g = new Generateur(cases, Map.of());
        UUID vacataire = UUID.randomUUID();
        // Pris ailleurs de 8 h 30 à 9 h 30 : les deux heures sont touchées
        g.prisAilleurs(vacataire, 1, LocalTime.parse("08:30"), LocalTime.parse("09:30"));
        UUID classe = UUID.randomUUID();
        Resultat r = g.generer(List.of(new Besoin(classe, "CAP", UUID.randomUUID(), UUID.randomUUID(), "Maths",
                TypeMatiere.GENERALE, vacataire, 60)));
        assertTrue(r.placements().isEmpty());
        assertEquals(1, r.manques().size());
        assertEquals(60, r.manques().get(0).minutes());

        // Une séance déjà placée occupe la classe : l'autre heure est prise
        Generateur g2 = new Generateur(cases, Map.of());
        g2.dejaPlace(classe, UUID.randomUUID(), null, null, 1, cases.get(0).creneauId());
        Resultat r2 = g2.generer(List.of(new Besoin(classe, "CAP", UUID.randomUUID(), UUID.randomUUID(), "Français",
                TypeMatiere.GENERALE, UUID.randomUUID(), 60)));
        assertEquals(1, r2.placements().size());
        assertEquals(cases.get(1).creneauId(), r2.placements().get(0).creneauId());
    }
}
