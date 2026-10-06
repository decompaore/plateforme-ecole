package bf.edutech.plateforme.socle.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantFilterTest {

    @Test
    void leSousDomaineDoitCorrespondreAuCodeDeLEtablissement() {
        assertThat(TenantFilter.sousDomaineCorrespond("lycee-a.plateforme.bf", "lycee-a")).isTrue();
        assertThat(TenantFilter.sousDomaineCorrespond("LYCEE-A.plateforme.bf", "lycee-a")).isTrue();
        assertThat(TenantFilter.sousDomaineCorrespond("lycee-b.plateforme.bf", "lycee-a")).isFalse();
        assertThat(TenantFilter.sousDomaineCorrespond("lycee-a.plateforme.bf", null)).isFalse();
    }
}
