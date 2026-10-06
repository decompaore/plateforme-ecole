package bf.edutech.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * Une migration Flyway déjà livrée ne doit jamais changer : la base qui l'a appliquée refuserait
 * de démarrer (« Migration checksum mismatch »). Ce test calcule l'empreinte de chaque fichier
 * comme Flyway (CRC32 ligne par ligne, sans fin de ligne ni BOM) et la compare à
 * {@code migrations-figees.txt}. Il échoue dès {@code mvn verify}, avant tout démarrage.
 * Ne pas utiliser {@code flyway repair} pour « réparer » : remettre le fichier d'origine
 * ({@code git checkout develop -- <fichier>}) et corriger dans une NOUVELLE migration.
 */
class MigrationsFigeesTest {

    private static final Path DOSSIER = Path.of("src/main/resources/db/migration");

    @Test
    void lesMigrationsLivreesNOntPasChange() throws IOException {
        Map<String, Integer> attendues = attendues();
        List<String> erreurs = new ArrayList<>();
        try (Stream<Path> fichiers = Files.list(DOSSIER)) {
            for (Path f : fichiers.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) {
                String nom = f.getFileName().toString();
                int empreinte = empreinteFlyway(f);
                Integer attendue = attendues.remove(nom);
                if (attendue == null) {
                    erreurs.add("Nouvelle migration " + nom + " : ajouter à src/test/resources/migrations-figees.txt la ligne\n    "
                            + nom + " " + empreinte);
                } else if (attendue != empreinte) {
                    erreurs.add(nom + " a été MODIFIÉE (empreinte " + empreinte + " au lieu de " + attendue
                            + "). Remettre le fichier d'origine : git checkout develop -- " + DOSSIER.resolve(nom)
                            + " (jamais flyway repair) ; une correction se fait dans une nouvelle migration.");
                }
            }
        }
        attendues.keySet().forEach(nom -> erreurs.add("Migration livrée " + nom + " SUPPRIMÉE ou renommée : la remettre."));
        assertThat(erreurs).as(String.join("\n", erreurs)).isEmpty();
    }

    /** Même calcul que Flyway (ChecksumCalculator) : CRC32 des lignes en UTF-8, sans fin de ligne ni BOM. */
    static int empreinteFlyway(Path fichier) throws IOException {
        CRC32 crc = new CRC32();
        try (BufferedReader lecteur = Files.newBufferedReader(fichier, StandardCharsets.UTF_8)) {
            String ligne = lecteur.readLine();
            if (ligne != null && !ligne.isEmpty() && ligne.charAt(0) == '﻿') {
                ligne = ligne.substring(1);
            }
            while (ligne != null) {
                crc.update(ligne.getBytes(StandardCharsets.UTF_8));
                ligne = lecteur.readLine();
            }
        }
        return (int) crc.getValue();
    }

    private static Map<String, Integer> attendues() throws IOException {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (InputStream in = MigrationsFigeesTest.class.getResourceAsStream("/migrations-figees.txt");
                BufferedReader lecteur = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String ligne = lecteur.readLine(); ligne != null; ligne = lecteur.readLine()) {
                ligne = ligne.strip();
                if (!ligne.isEmpty() && !ligne.startsWith("#")) {
                    String[] p = ligne.split("\\s+");
                    m.put(p[0], Integer.parseInt(p[1]));
                }
            }
        }
        return m;
    }
}
