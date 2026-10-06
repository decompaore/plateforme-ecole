package bf.edutech.plateforme.reversibilite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Format des valeurs de l'archive (sans base de données). */
class ArchiveEtablissementTest {

    @Test
    void lesChampsCsvSontProtegesSeulementSiNecessaire() {
        assertThat(ArchiveEtablissement.csv(null)).isEmpty();
        assertThat(ArchiveEtablissement.csv("OUEDRAOGO")).isEqualTo("OUEDRAOGO");
        assertThat(ArchiveEtablissement.csv("12,50")).isEqualTo("12,50");
        assertThat(ArchiveEtablissement.csv("a;b")).isEqualTo("\"a;b\"");
        assertThat(ArchiveEtablissement.csv("dit \"Bébé\"")).isEqualTo("\"dit \"\"Bébé\"\"\"");
        assertThat(ArchiveEtablissement.csv("ligne 1\nligne 2")).isEqualTo("\"ligne 1\nligne 2\"");
    }

    @Test
    void leTypeDesFichiersJointsEstReconnuASesPremiersOctets() {
        assertThat(ArchiveEtablissement.extension("%PDF-1.7".getBytes())).isEqualTo(".pdf");
        assertThat(ArchiveEtablissement.extension(new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10 })).isEqualTo(".png");
        assertThat(ArchiveEtablissement.extension(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 })).isEqualTo(".jpg");
        assertThat(ArchiveEtablissement.extension("RIFF1234WEBPVP8".getBytes())).isEqualTo(".webp");
        assertThat(ArchiveEtablissement.extension(new byte[] { 1, 2 })).isEqualTo(".bin");
    }

    @Test
    void lesTextesDuManifesteSontEchappes() {
        assertThat(ArchiveEtablissement.json(null)).isEqualTo("null");
        assertThat(ArchiveEtablissement.json("Lycée \"Saint\"\n\\")).isEqualTo("\"Lycée \\\"Saint\\\"\\n\\\\\"");
        assertThat(ArchiveEtablissement.json("\u0001")).isEqualTo("\"\\u0001\"");
    }
}
