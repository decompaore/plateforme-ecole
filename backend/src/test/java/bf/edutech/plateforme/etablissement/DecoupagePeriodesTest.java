package bf.edutech.plateforme.etablissement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import bf.edutech.plateforme.etablissement.PeriodesService.Intervalle;

class DecoupagePeriodesTest {

    @Test
    void troisTrimestresContigusCouvrantToutLAnnee() {
        LocalDate debut = LocalDate.of(2026, 10, 1);
        LocalDate fin = LocalDate.of(2027, 7, 31);

        List<Intervalle> trimestres = PeriodesService.decouper(debut, fin, 3);

        assertThat(trimestres).hasSize(3);
        assertThat(trimestres.get(0).debut()).isEqualTo(debut);
        assertThat(trimestres.get(2).fin()).isEqualTo(fin);
        for (int i = 1; i < trimestres.size(); i++) {
            // Pas de trou ni de chevauchement : chaque période commence le lendemain de la précédente
            assertThat(trimestres.get(i).debut()).isEqualTo(trimestres.get(i - 1).fin().plusDays(1));
        }
    }

    @Test
    void deuxSemestres() {
        List<Intervalle> semestres = PeriodesService.decouper(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 6, 30), 2);
        assertThat(semestres).hasSize(2);
        assertThat(semestres.get(1).debut()).isEqualTo(semestres.get(0).fin().plusDays(1));
    }
}
