package bf.edutech.plateforme.territoire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import bf.edutech.plateforme.socle.erreurs.AuthentificationException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.utilisateurs.AuthService;

/**
 * Administrateurs pays (v0.36) : nommés par le super administrateur, ils gèrent les ministères,
 * directions et établissements de LEUR pays, sans résilier ; rien hors de leur pays.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdministrateursPaysIntegrationTest {

    @Autowired private WebApplicationContext contexte;
    @Autowired private AuthService auth;
    @Autowired private EtablissementsService etablissements;

    private static final RequestPostProcessor SUPER_ADMIN = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
            .claim("typ_jeton", "ACCES"))
            .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    private static String telephone() {
        return "6" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    @Test
    void unAdministrateurPaysNeGereQueSonPays() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String pays = mvc.perform(get("/api/v1/plateforme/territoire/pays").with(SUPER_ADMIN))
                .andReturn().getResponse().getContentAsString();
        String bf = ((List<String>) JsonPath.read(pays, "$[?(@.code == 'BF')].id")).get(0);
        List<String> zz = JsonPath.read(pays, "$[?(@.code == 'ZZ')].id");
        String autre = !zz.isEmpty() ? zz.get(0) : JsonPath.read(mvc.perform(post("/api/v1/plateforme/territoire/pays")
                .with(SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"ZZ","nom":"Pays de test","indicatifTelephone":"+999","longueurNumero":8,
                         "fuseauHoraire":"UTC","monnaie":"XOF","langue":"fr"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        // Le super administrateur nomme un administrateur pour le Burkina Faso
        String tel = telephone();
        String nomination = mvc.perform(post("/api/v1/plateforme/territoire/pays/{id}/administrateurs", bf).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"telephone\":\"" + tel + "\",\"nom\":\"Ouedraogo\",\"prenoms\":\"Awa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.administrateur.nom").value("OUEDRAOGO"))
                .andExpect(jsonPath("$.administrateur.telephone").value("+226" + tel))
                .andReturn().getResponse().getContentAsString();
        String adminId = JsonPath.read(nomination, "$.administrateur.utilisateurId");
        String motDePasse = JsonPath.read(nomination, "$.motDePasseTemporaire");
        mvc.perform(get("/api/v1/plateforme/territoire/pays/{id}/administrateurs", bf).with(SUPER_ADMIN))
                .andExpect(jsonPath("$[?(@.utilisateurId == '" + adminId + "')].actif").value(true));

        // Sa connexion ouvre une session « pays », sans établissement
        AuthService.ResultatConnexion connexion = auth.connexion(tel, motDePasse);
        assertThat(connexion.adminPays()).isNotNull();
        assertThat(connexion.adminPays().code()).isEqualTo("BF");
        assertThat(connexion.etablissementActif()).isNull();
        assertThat(connexion.superAdmin()).isFalse();
        // Un compte d'administrateur pays ne devient pas membre d'un établissement
        assertThatThrownBy(() -> etablissements.creer("pa-" + suffixe.toLowerCase(), "Lycée " + suffixe, tel, "X", "Y"))
                .isInstanceOf(RegleMetierException.class);

        RequestPostProcessor adminPays = jwt().jwt(j -> j.subject(adminId).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("ADMIN_PAYS")).claim("pays_id", bf))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_PAYS"));

        // Référentiel : son pays seulement ; il ne crée pas de pays
        mvc.perform(get("/api/v1/plateforme/territoire/pays").with(adminPays))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("BF"));
        mvc.perform(post("/api/v1/plateforme/territoire/pays").with(adminPays).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"QQ\",\"nom\":\"X\",\"indicatifTelephone\":\"+1\",\"longueurNumero\":8,\"fuseauHoraire\":\"UTC\",\"monnaie\":\"XOF\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/plateforme/territoire/pays/{id}/ministeres", autre).with(adminPays))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/plateforme/territoire/pays/{id}/administrateurs", bf).with(adminPays))
                .andExpect(status().isForbidden());

        // Il crée le ministère, les directions et un établissement de son pays
        String ministere = JsonPath.read(mvc.perform(post("/api/v1/plateforme/territoire/pays/{id}/ministeres", bf)
                .with(adminPays).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sigle\":\"P" + suffixe + "\",\"nom\":\"Ministère " + suffixe + "\",\"niveaux\":[\"DR\",\"DP\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(adminPays)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contenu\":\"DR1;Direction régionale;\\nDP1;Direction provinciale;DR1\",\"simulation\":false}"))
                .andExpect(jsonPath("$.creees").value(2));
        String directions = mvc.perform(get("/api/v1/plateforme/territoire/directions").with(adminPays))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(directions, "$[*].paysId")).allMatch(bf::equals);
        String dp = ((List<String>) JsonPath.read(directions,
                "$[?(@.ministereId == '" + ministere + "' && @.terminale == true)].id")).get(0);

        String sansRattachement = """
                {"code":"pb-%s","nom":"Lycée %s","telephoneAdministrateur":"%s","nomAdministrateur":"ADMIN","prenomsAdministrateur":"Pays"}"""
                .formatted(suffixe.toLowerCase(), suffixe, telephone());
        mvc.perform(post("/api/v1/plateforme/etablissements").with(adminPays).contentType(MediaType.APPLICATION_JSON)
                .content(sansRattachement)).andExpect(status().isBadRequest());
        String ecole = JsonPath.read(mvc.perform(post("/api/v1/plateforme/etablissements").with(adminPays)
                .contentType(MediaType.APPLICATION_JSON).content(sansRattachement.replace("}", ",\"directionId\":\"" + dp + "\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.etablissement.id");

        // Un établissement non rattaché (hors de tout pays) lui est invisible et inaccessible
        UUID horsPays = etablissements.creer("ph-" + suffixe.toLowerCase(), "Hors pays " + suffixe, telephone(), "A", "B")
                .etablissement().id();
        String liste = mvc.perform(get("/api/v1/plateforme/etablissements").with(adminPays))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(liste, "$[*].id")).contains(ecole).doesNotContain(horsPays.toString());
        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/modules", horsPays).with(adminPays))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/exports", horsPays).with(adminPays))
                .andExpect(status().isForbidden());

        // Il suspend et réactive ; la résiliation reste au super administrateur
        mvc.perform(patch("/api/v1/plateforme/etablissements/{id}/statut", ecole).with(adminPays)
                .contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"SUSPENDU\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("SUSPENDU"));
        mvc.perform(patch("/api/v1/plateforme/etablissements/{id}/statut", ecole).with(adminPays)
                .contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"RESILIE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/modules", ecole).with(adminPays))
                .andExpect(status().isOk());

        // Adoption : ses établissements seulement ; pas de recalcul
        String adoption = mvc.perform(get("/api/v1/plateforme/adoption").with(adminPays))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(adoption, "$.details[*].id")).contains(ecole).doesNotContain(horsPays.toString());
        mvc.perform(post("/api/v1/plateforme/adoption/calcul").with(adminPays)).andExpect(status().isForbidden());

        // Retiré par le super administrateur : il ne peut plus se connecter
        mvc.perform(delete("/api/v1/plateforme/territoire/pays/{id}/administrateurs/{u}", bf, adminId).with(SUPER_ADMIN))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> auth.connexion(tel, motDePasse)).isInstanceOf(AuthentificationException.class);
    }
}
