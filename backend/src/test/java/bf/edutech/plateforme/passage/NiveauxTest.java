package bf.edutech.plateforme.passage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Ordre des niveaux pour le plan de passage (v0.45). */
class NiveauxTest {

    @Test
    void enseignementGeneral() {
        List<String> college = List.of("6e", "5e", "4e", "3e");
        assertThat(Niveaux.suivant("6e", college)).contains("5e");
        assertThat(Niveaux.suivant("3e", college)).isEmpty();                    // fin de cycle
        List<String> lycee = List.of("2nde", "1re", "Tle");
        assertThat(Niveaux.suivant("2nde", lycee)).contains("1re");
        assertThat(Niveaux.suivant("Seconde", lycee)).contains("1re");
        assertThat(Niveaux.suivant("1ère", lycee)).contains("Tle");
        assertThat(Niveaux.suivant("Tle", lycee)).isEmpty();
    }

    @Test
    void niveauxNumerotesDeLaFormationProfessionnelle() {
        List<String> annees = List.of("1re année", "2e année", "3e année");
        assertThat(Niveaux.suivant("1ère année", annees)).contains("2e année");
        assertThat(Niveaux.suivant("2ème année", annees)).contains("3e année");
        assertThat(Niveaux.suivant("CAP 1", List.of("CAP 3", "CAP 2"))).contains("CAP 2");
        assertThat(Niveaux.suivant("BEP1", List.of("BEP2"))).contains("BEP2");
        // Familles différentes : la plateforme ne devine pas
        assertThat(Niveaux.suivant("3e", List.of("CAP 1"))).isEmpty();
        assertThat(Niveaux.suivant("Atelier", List.of("Atelier 2"))).isEmpty();
    }

    @Test
    void memeNiveauPourLesRedoublants() {
        assertThat(Niveaux.meme("1re", "1ère")).isTrue();
        assertThat(Niveaux.meme("Tle", "Terminale")).isTrue();
        assertThat(Niveaux.meme("CAP 1", "cap1")).isTrue();
        assertThat(Niveaux.meme("2nde", "1re")).isFalse();
        assertThat(Niveaux.meme("Atelier", "atelier")).isTrue();
    }
}
