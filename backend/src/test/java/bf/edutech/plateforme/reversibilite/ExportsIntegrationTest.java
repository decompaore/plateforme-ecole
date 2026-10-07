package bf.edutech.plateforme.reversibilite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.plateforme.EtablissementsService;

/**
 * Export complet : mot de passe redemandé, préparation en tâche de fond, archive limitée à
 * l'établissement (rien d'un autre), sans secret, vérifiable par ses empreintes, téléchargée
 * par un lien signé qui ne vaut que pour elle.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExportsIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private WebApplicationContext contexte;

    private record Ecole(UUID id, UUID admin, String telephone, String motDePasse, RequestPostProcessor jeton) {
    }

    private Ecole ecole() {
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String telephone = "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        EtablissementsService.ResultatCreation c = etablissements.creer("exp-" + suffixe, "Lycée " + suffixe,
                telephone, "ADMIN", "Export");
        UUID id = c.etablissement().id();
        UUID admin = c.administrateur().utilisateurId();
        RequestPostProcessor jeton = jwt().jwt(j -> j.subject(admin.toString())
                .claim("tenant_id", id.toString()).claim("typ_jeton", "ACCES").claim("roles", List.of("ADMIN_ECOLE")))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_ECOLE"));
        return new Ecole(id, admin, telephone, c.motDePasseTemporaire(), jeton);
    }

    @Test
    void lArchiveContientToutesLesDonneesDeLEtablissementEtRienDAutre() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        Ecole a = ecole();
        Ecole b = ecole();
        assertThat(a.motDePasse()).isNotBlank();

        // Mauvais mot de passe : refusé, la session reste ouverte (409, pas 401)
        mvc.perform(post("/api/v1/exports").with(a.jeton()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motDePasse\":\"faux-1234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOT_DE_PASSE_INCORRECT"));

        String reponse = mvc.perform(post("/api/v1/exports").with(a.jeton()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motDePasse\":\"" + a.motDePasse() + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statut").value("EN_COURS"))
                .andExpect(jsonPath("$.demandePar").value("ADMIN Export"))
                .andReturn().getResponse().getContentAsString();
        String exportId = JsonPath.read(reponse, "$.id");

        // Préparation en tâche de fond
        String statut = "EN_COURS";
        for (int i = 0; i < 120 && statut.equals("EN_COURS"); i++) {
            Thread.sleep(250);
            statut = JsonPath.read(mvc.perform(get("/api/v1/exports").with(a.jeton())).andReturn().getResponse()
                    .getContentAsString(), "$[0].statut");
        }
        assertThat(statut).isEqualTo("PRET");
        String liste = mvc.perform(get("/api/v1/exports").with(a.jeton()))
                .andExpect(jsonPath("$[0].id").value(exportId))
                .andExpect(jsonPath("$[0].empreinte").isString())
                .andReturn().getResponse().getContentAsString();
        Integer tables = JsonPath.read(liste, "$[0].nombreTables");
        assertThat(tables).isGreaterThan(70);

        // L'autre établissement ne voit pas cet export ; l'administrateur n'a pas accès à la plateforme
        mvc.perform(get("/api/v1/exports").with(b.jeton())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/exports/{id}/lien", exportId).with(b.jeton())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/exports", b.id()).with(a.jeton()))
                .andExpect(status().isForbidden());

        // Lien signé : téléchargement sans jeton d'accès
        String chemin = JsonPath.read(mvc.perform(post("/api/v1/exports/{id}/lien", exportId).with(a.jeton()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.chemin");
        assertThat(chemin).startsWith("/telechargements/exports/" + exportId + "?jeton=");
        byte[] zip = mvc.perform(get("/api/v1" + chemin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".zip")))
                .andReturn().getResponse().getContentAsByteArray();

        Map<String, byte[]> entrees = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                entrees.put(e.getName(), in.readAllBytes());
            }
        }
        assertThat(entrees).containsKeys("LISEZMOI.txt", "manifeste.json", "SHA256SUMS.txt",
                "donnees/membre_etablissement.csv", "donnees/eleve.csv", "donnees/journal_audit.csv",
                "donnees/export_donnees.csv", "comptes/utilisateurs.csv");
        String membres = texte(entrees, "donnees/membre_etablissement.csv");
        assertThat(membres).startsWith("\uFEFF").contains(a.admin().toString()).doesNotContain(b.admin().toString());
        String comptes = texte(entrees, "comptes/utilisateurs.csv");
        assertThat(comptes).contains("+226" + a.telephone()).doesNotContain("+226" + b.telephone())
                .doesNotContain("mot_de_passe").doesNotContain("$2a$");
        assertThat(texte(entrees, "donnees/journal_audit.csv").lines().findFirst().orElseThrow())
                .doesNotContain("adresse_ip");
        assertThat(texte(entrees, "donnees/configuration_mobile_money.csv").lines().findFirst().orElseThrow())
                .doesNotContain("chiffre");
        for (Map.Entry<String, byte[]> e : entrees.entrySet()) {
            assertThat(new String(e.getValue(), StandardCharsets.UTF_8)).doesNotContain(b.id().toString());
        }
        String manifeste = texte(entrees, "manifeste.json");
        assertThat((String) JsonPath.read(manifeste, "$.etablissement.id")).isEqualTo(a.id().toString());
        assertThat((List<String>) JsonPath.read(manifeste, "$.tables[?(@.nom == 'journal_audit')].colonnesNonExportees[0]"))
                .containsExactly("adresse_ip");

        // Chaque fichier correspond à son empreinte
        for (String ligne : texte(entrees, "SHA256SUMS.txt").split("\n")) {
            String[] parties = ligne.split("  ", 2);
            assertThat(entrees).containsKey(parties[1]);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entrees.get(parties[1]))))
                    .as(parties[1]).isEqualTo(parties[0]);
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip)))
                .isEqualTo(JsonPath.read(liste, "$[0].empreinte"));

        // Un lien falsifié, ou utilisé pour une autre archive, ne donne rien
        mvc.perform(get("/api/v1" + chemin + "x")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1" + chemin.replace(exportId, UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/telechargements/exports/{id}", exportId)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/exports").with(a.jeton())).andExpect(jsonPath("$[0].telechargements").value(1));

        // Le super administrateur exporte l'autre établissement ; celui-ci le voit dans sa liste
        RequestPostProcessor superAdmin = jwt().jwt(j -> j.subject(b.admin().toString()).claim("typ_jeton", "ACCES"))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        mvc.perform(post("/api/v1/plateforme/etablissements/{id}/exports", b.id()).with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"motDePasse\":\"" + b.motDePasse() + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.parPlateforme").value(true));
        mvc.perform(get("/api/v1/exports").with(b.jeton()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].demandePar").value("Plateforme (ADMIN Export)"));
        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/exports", UUID.randomUUID()).with(superAdmin))
                .andExpect(status().isNotFound());
    }

    private static String texte(Map<String, byte[]> entrees, String nom) {
        return new String(entrees.get(nom), StandardCharsets.UTF_8);
    }
}
