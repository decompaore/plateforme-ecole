package bf.edutech.plateforme.territoire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

/**
 * Rattachement territorial : le super administrateur définit un ministère et ses niveaux, importe
 * ses directions, rattache un établissement à une direction du dernier niveau ; l'établissement
 * voit son en-tête officiel et choisit son logo.
 */
@SpringBootTest
@ActiveProfiles("test")
class TerritoireIntegrationTest {

    @Autowired private WebApplicationContext contexte;

    private static final RequestPostProcessor SUPER_ADMIN = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
            .claim("typ_jeton", "ACCES"))
            .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @Test
    void unEtablissementRattacheAUneDirectionPorteSonEnTeteOfficiel() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();

        // Le pays de la première installation existe (migration V26)
        String pays = mvc.perform(get("/api/v1/plateforme/territoire/pays").with(SUPER_ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bf = ((List<String>) JsonPath.read(pays, "$[?(@.code == 'BF')].id")).get(0);
        assertThat((List<String>) JsonPath.read(pays, "$[?(@.code == 'BF')].indicatifTelephone")).containsExactly("+226");

        // Ministère à deux niveaux
        String sigle = "M" + suffixe;
        String ministere = JsonPath.read(mvc.perform(post("/api/v1/plateforme/territoire/pays/{id}/ministeres", bf)
                .with(SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sigle":"%s","nom":"Ministère de test %s","niveaux":["Direction régionale","Direction provinciale"]}"""
                        .formatted(sigle, suffixe)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.niveaux.length()").value(2))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // Import : une erreur (parent inconnu) et rien n'est enregistré
        String erreur = "code;nom;code_parent\nDR1;Direction régionale du Centre;\nDP1;Direction provinciale du Kadiogo;INCONNU\n";
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(json(erreur, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erreurs.length()").value(1))
                .andExpect(jsonPath("$.erreurs[0].ligne").value(3));
        mvc.perform(get("/api/v1/plateforme/territoire/ministeres/{id}/directions", ministere).with(SUPER_ADMIN))
                .andExpect(jsonPath("$.length()").value(0));

        // Import correct : d'abord en simulation, puis pour de bon (enfants avant parents dans le fichier)
        String csv = "DP1;Direction provinciale du Kadiogo;DR1\nDP2;Direction provinciale du Bazèga;DR1\nDR1;Direction régionale du Centre;\n";
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(json(csv, true)))
                .andExpect(jsonPath("$.simulation").value(true))
                .andExpect(jsonPath("$.creees").value(3));
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(json(csv, false)))
                .andExpect(jsonPath("$.creees").value(3))
                .andExpect(jsonPath("$.erreurs.length()").value(0));
        String directions = mvc.perform(get("/api/v1/plateforme/territoire/ministeres/{id}/directions", ministere)
                .with(SUPER_ADMIN)).andExpect(jsonPath("$.length()").value(3)).andReturn().getResponse().getContentAsString();
        String dr = ((List<String>) JsonPath.read(directions, "$[?(@.code == 'DR1')].id")).get(0);
        String dp = ((List<String>) JsonPath.read(directions, "$[?(@.code == 'DP1')].id")).get(0);
        // Réimport : rien ne change
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(json(csv, false)))
                .andExpect(jsonPath("$.inchangees").value(3));

        // Le nombre de niveaux est figé dès qu'il y a des directions
        mvc.perform(put("/api/v1/plateforme/territoire/ministeres/{id}", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sigle\":\"" + sigle + "\",\"nom\":\"Ministère\",\"niveaux\":[\"Direction régionale\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NIVEAUX_FIGES"));
        // Pas de direction sous le dernier niveau
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":\"" + dp + "\",\"code\":\"X\",\"nom\":\"X\"}"))
                .andExpect(status().isConflict());

        // Création d'un établissement rattaché à la direction provinciale
        String telephone = "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        String creation = mvc.perform(post("/api/v1/plateforme/etablissements").with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"ter-%s","nom":"Lycée %s","telephoneAdministrateur":"%s","nomAdministrateur":"ADMIN",
                         "prenomsAdministrateur":"Territoire","directionId":"%s"}""".formatted(suffixe.toLowerCase(), suffixe,
                        telephone, dp)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.etablissement.directionId").value(dp))
                .andExpect(jsonPath("$.etablissement.rattachement").value("Burkina Faso · " + sigle
                        + " · Direction régionale du Centre · Direction provinciale du Kadiogo"))
                .andExpect(jsonPath("$.administrateur.telephone").value("+226" + telephone))
                .andReturn().getResponse().getContentAsString();
        String ecole = JsonPath.read(creation, "$.etablissement.id");
        String admin = JsonPath.read(creation, "$.administrateur.utilisateurId");

        // Un établissement ne se rattache qu'au dernier niveau
        mvc.perform(put("/api/v1/plateforme/etablissements/{id}/rattachement", ecole).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"directionId\":\"" + dr + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATTACHEMENT_INVALIDE"));
        // Filtre par direction régionale : l'établissement y figure
        mvc.perform(get("/api/v1/plateforme/etablissements").param("direction", dr).with(SUPER_ADMIN))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ecole));
        mvc.perform(get("/api/v1/plateforme/adoption").param("direction", dr).with(SUPER_ADMIN))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].rattachement").isString());

        // L'établissement voit son en-tête officiel et choisit son logo
        RequestPostProcessor adminEcole = jwt().jwt(j -> j.subject(admin)
                .claim("tenant_id", ecole).claim("typ_jeton", "ACCES").claim("roles", List.of("ADMIN_ECOLE")))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_ECOLE"));
        mvc.perform(get("/api/v1/identite").with(adminEcole))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rattache").value(true))
                .andExpect(jsonPath("$.pays").value("Burkina Faso"))
                .andExpect(jsonPath("$.autorites[0]").value("Ministère de test " + suffixe))
                .andExpect(jsonPath("$.autorites[2]").value("Direction provinciale du Kadiogo"))
                .andExpect(jsonPath("$.logo").doesNotExist());
        mvc.perform(multipart("/api/v1/identite/logo").file(new MockMultipartFile("fichier", "logo.txt", "image/png",
                "pas une image".getBytes(StandardCharsets.UTF_8))).with(adminEcole))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/identite/logo").file(new MockMultipartFile("fichier", "logo.png", "image/png", png()))
                .with(adminEcole))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logo.type").value("image/png"));
        byte[] apercu = mvc.perform(get("/api/v1/identite/apercu").with(adminEcole))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(apercu, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        mvc.perform(get("/api/v1/identite/logo").with(adminEcole)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/identite/logo").with(adminEcole))
                .andExpect(jsonPath("$.logo").doesNotExist());
        // Le référentiel reste réservé à la plateforme
        mvc.perform(get("/api/v1/plateforme/territoire/pays").with(adminEcole)).andExpect(status().isForbidden());
    }

    private static String json(String contenu, boolean simulation) {
        return "{\"contenu\":\"" + contenu.replace("\n", "\\n") + "\",\"simulation\":" + simulation + "}";
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        image.setRGB(5, 5, 0x2E7D32);
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        ImageIO.write(image, "png", sortie);
        return sortie.toByteArray();
    }
}
