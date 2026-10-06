package bf.edutech.plateforme.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import bf.edutech.plateforme.adoption.AdoptionService.Taux;

/** Établissements à accompagner (sans base de données). */
class AlertesAdoptionTest {

    private static final LocalDate FIN = LocalDate.of(2026, 10, 6);

    @Test
    void sansActiviteDepuisUneSemaine() {
        assertThat(AdoptionService.alertes("ACTIF", null, new Taux(10, 0), Map.of(), FIN)).containsExactly("SANS_ACTIVITE");
        assertThat(AdoptionService.alertes("ACTIF", FIN.minusDays(7), new Taux(10, 8), Map.of("APPELS", 5L), FIN))
                .containsExactly("SANS_ACTIVITE");
        assertThat(AdoptionService.alertes("ACTIF", FIN.minusDays(6), new Taux(10, 8), Map.of("APPELS", 5L), FIN))
                .isEmpty();
    }

    @Test
    void peuDEnseignantsOuPasDAppel() {
        assertThat(AdoptionService.alertes("ACTIF", FIN, new Taux(10, 4), Map.of("APPELS", 3L), FIN))
                .containsExactly("PEU_D_ENSEIGNANTS");
        assertThat(AdoptionService.alertes("ACTIF", FIN, new Taux(10, 5), Map.of("NOTES", 3L), FIN))
                .containsExactly("SANS_APPEL");
    }

    @Test
    void unEtablissementSuspenduNEstPasSignale() {
        assertThat(AdoptionService.alertes("SUSPENDU", null, new Taux(10, 0), Map.of(), FIN)).isEmpty();
        assertThat(AdoptionService.borner(0)).isEqualTo(1);
        assertThat(AdoptionService.borner(1000)).isEqualTo(366);
    }
}
