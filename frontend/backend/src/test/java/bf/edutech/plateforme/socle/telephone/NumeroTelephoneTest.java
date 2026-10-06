package bf.edutech.plateforme.socle.telephone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NumeroTelephoneTest {

    @Test
    void ajouteLIndicatifAuxNumerosNationaux() {
        assertThat(NumeroTelephone.normaliser("70 12 34 56", "+226")).isEqualTo("+22670123456");
    }

    @Test
    void accepteLesFormatsInternationaux() {
        assertThat(NumeroTelephone.normaliser("0022670123456", "+226")).isEqualTo("+22670123456");
        assertThat(NumeroTelephone.normaliser("+225 07 12 34 56 78", "+226")).isEqualTo("+2250712345678");
        assertThat(NumeroTelephone.normaliser("22670123456", "+226")).isEqualTo("+22670123456");
    }

    @Test
    void refuseLesNumerosInvalides() {
        assertThatThrownBy(() -> NumeroTelephone.normaliser("12ab", "+226"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumeroTelephone.normaliser(" ", "+226"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masqueLeNumeroDansLesJournaux() {
        assertThat(NumeroTelephone.masquer("+22670123456")).isEqualTo("+226****3456");
    }
}
