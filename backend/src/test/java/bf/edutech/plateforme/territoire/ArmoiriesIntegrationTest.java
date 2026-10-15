package bf.edutech.plateforme.territoire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

import bf.edutech.plateforme.plateforme.EtablissementsService;

/**
 * Armoiries des pays (v0.48.1) : l'image officielle est téléversée pour un pays, puis servie en filigrane à tout
 * compte de ce pays (établissement rattaché, direction, administrateur pays) ; un administrateur d'un autre pays ne la
 * modifie pas ; un établissement sans rattachement n'en a pas. Le test utilise le Burkina Faso et retire les
 * armoiries à la fin.
 */
@SpringBootTest
@ActiveProfiles("test")
class ArmoiriesIntegrationTest {

    @Autowired private WebApplicationContext contexte;
    @Autowired private EtablissementsService etablissements;

    private static final RequestPostProcessor SUPER_ADMIN = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
            .claim("typ_jeton", "ACCES"))
            .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @Test
    void lesArmoiriesDuPaysSontServiesAuxComptesDeCePays() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String pays = mvc.perform(get("/api/v1/plateforme/territoire/pays").with(SUPER_ADMIN)).andReturn().getResponse()
                .getContentAsString();
        String paysId = ((List<String>) JsonPath.read(pays, "$[?(@.code == 'BF')].id")).get(0);
        String ministere = JsonPath.read(mvc.perform(post("/api/v1/plateforme/territoire/pays/{id}/ministeres", paysId)
                .with(SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sigle\":\"AR" + suffixe + "\",\"nom\":\"Ministère " + suffixe + "\",\"niveaux\":[\"Direction régionale\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contenu\":\"DR" + suffixe + ";DR " + suffixe + ";\",\"simulation\":false}"))
                .andExpect(jsonPath("$.creees").value(1));
        String arbre = mvc.perform(get("/api/v1/plateforme/territoire/ministeres/{id}/directions", ministere)
                .with(SUPER_ADMIN)).andReturn().getResponse().getContentAsString();
        UUID dr = UUID.fromString(((List<String>) JsonPath.read(arbre, "$[?(@.code == 'DR" + suffixe + "')].id")).get(0));
        EtablissementsService.ResultatCreation lycee = etablissements.creer("ar-" + suffixe.toLowerCase(),
                "Lycée " + suffixe, telephone(), "ADMIN", "Lycée", dr);
        EtablissementsService.ResultatCreation college = etablissements.creer("sr-" + suffixe.toLowerCase(),
                "Collège " + suffixe, telephone(), "ADMIN", "Collège");

        RequestPostProcessor ecole = etablissement(lycee);
        RequestPostProcessor direction = jwt().jwt(x -> x.subject(UUID.randomUUID().toString()).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("DIRECTION")).claim("direction_id", dr.toString()))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_DIRECTION"));
        // Le dépôt enregistre l'auteur (modifie_par → utilisateur) : le jeton porte un compte existant
        UUID compte = lycee.administrateur().utilisateurId();
        RequestPostProcessor adminPays = adminPays(compte, paysId);
        RequestPostProcessor autrePays = adminPays(compte, UUID.randomUUID().toString());

        // Pas encore d'armoiries (on repart d'un état propre si un passage précédent a échoué en route)
        mvc.perform(delete("/api/v1/plateforme/territoire/pays/{id}/armoiries", paysId).with(SUPER_ADMIN))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/armoiries").with(ecole)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plateforme/territoire/pays/{id}/armoiries/etat", paysId).with(adminPays))
                .andExpect(jsonPath("$.presentes").value(false));

        // Un fichier qui n'est pas une image est refusé ; un administrateur d'un autre pays ne modifie rien
        mvc.perform(multipart("/api/v1/plateforme/territoire/pays/{id}/armoiries", paysId)
                .file(new MockMultipartFile("fichier", "a.txt", "text/plain", "pas une image".getBytes())).with(adminPays))
                .andExpect(status().isBadRequest());
        MockMultipartFile png = new MockMultipartFile("fichier", "armoiries.png", "image/png", png(1200, 1000));
        mvc.perform(multipart("/api/v1/plateforme/territoire/pays/{id}/armoiries", paysId).file(png).with(autrePays))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/plateforme/territoire/pays/{id}/armoiries", paysId).file(png).with(adminPays))
                .andExpect(status().isOk()).andExpect(jsonPath("$.presentes").value(true));

        // Servies à l'établissement rattaché, à la direction et à l'administrateur du pays ; réduites à 800 px
        byte[] image = mvc.perform(get("/api/v1/armoiries").with(ecole)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage lue = ImageIO.read(new java.io.ByteArrayInputStream(image));
        assertThat(Math.max(lue.getWidth(), lue.getHeight())).isLessThanOrEqualTo(800);
        mvc.perform(get("/api/v1/armoiries").with(direction)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/armoiries").with(adminPays)).andExpect(status().isOk());
        // Aucune pour un établissement sans rattachement, ni pour le super administrateur
        mvc.perform(get("/api/v1/armoiries").with(etablissement(college))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/armoiries").with(SUPER_ADMIN)).andExpect(status().isNotFound());

        // Retrait
        mvc.perform(delete("/api/v1/plateforme/territoire/pays/{id}/armoiries", paysId).with(SUPER_ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.presentes").value(false));
        mvc.perform(get("/api/v1/armoiries").with(ecole)).andExpect(status().isNotFound());
    }

    private static RequestPostProcessor etablissement(EtablissementsService.ResultatCreation e) {
        return jwt().jwt(x -> x.subject(e.administrateur().utilisateurId().toString())
                .claim("tenant_id", e.etablissement().id().toString())
                .claim("typ_jeton", "ACCES").claim("roles", List.of("ADMIN_ECOLE")))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_ECOLE"));
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private static RequestPostProcessor adminPays(UUID compte, String pays) {
        return jwt().jwt(x -> x.subject(compte.toString()).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("ADMIN_PAYS")).claim("pays_id", pays))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_PAYS"));
    }

    private static byte[] png(int l, int h) throws Exception {
        BufferedImage i = new BufferedImage(l, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = i.createGraphics();
        g.setColor(new Color(0, 122, 61));
        g.fillOval(100, 100, l - 200, h - 200);
        g.dispose();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(i, "png", o);
        return o.toByteArray();
    }
}
