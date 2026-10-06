package bf.edutech.plateforme.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import bf.edutech.plateforme.utilisateurs.AuthService;
import bf.edutech.plateforme.utilisateurs.EtablissementAccessible;

/**
 * Modules activables : le super administrateur désactive les ateliers et la scolarité d'un
 * établissement ; leurs chemins d'API sont fermés (Mobile Money avec la scolarité), le reste
 * fonctionne, et la connexion annonce les modules désactivés à l'application.
 */
@SpringBootTest
@ActiveProfiles("test")
class ModulesIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private AuthService auth;
    @Autowired private WebApplicationContext contexte;

    @Test
    void unModuleDesactiveFermeSesEcransSansToucherAuReste() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        EtablissementsService.ResultatCreation creation = etablissements.creer("mod-" + suffixe, "Lycée " + suffixe,
                "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000)), "ADMIN", "Test");
        UUID ecole = creation.etablissement().id();
        UUID admin = creation.administrateur().utilisateurId();
        RequestPostProcessor adminEcole = jwt().jwt(j -> j.subject(admin.toString())
                .claim("tenant_id", ecole.toString()).claim("typ_jeton", "ACCES").claim("roles", List.of("ADMIN_ECOLE")))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_ECOLE"));
        // Super administrateur (sans établissement) ; le compte existe pour la traçabilité
        RequestPostProcessor superAdmin = jwt().jwt(j -> j.subject(admin.toString()).claim("typ_jeton", "ACCES"))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

        mvc.perform(get("/api/v1/ateliers").with(adminEcole)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/modules", ecole).with(superAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[?(@.code == 'ATELIERS')].actif").value(true))
                .andExpect(jsonPath("$[?(@.code == 'MOBILE_MONEY')].requis").value("SCOLARITE"));

        // Seul le super administrateur choisit les modules
        mvc.perform(put("/api/v1/plateforme/etablissements/{id}/modules", ecole).with(adminEcole)
                .contentType(MediaType.APPLICATION_JSON).content("{\"actifs\":[]}"))
                .andExpect(status().isForbidden());

        // Ateliers et scolarité désactivés : Mobile Money, qui en dépend, l'est aussi
        mvc.perform(put("/api/v1/plateforme/etablissements/{id}/modules", ecole).with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actifs\":[\"EMPLOIS_DU_TEMPS\",\"PROGRESSION\",\"VIE_SCOLAIRE\",\"MOBILE_MONEY\",\"ESPACE_PARENT\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'ATELIERS')].actif").value(false))
                .andExpect(jsonPath("$[?(@.code == 'SCOLARITE')].actif").value(false))
                .andExpect(jsonPath("$[?(@.code == 'MOBILE_MONEY')].actif").value(false))
                .andExpect(jsonPath("$[?(@.code == 'EMPLOIS_DU_TEMPS')].actif").value(true));

        mvc.perform(get("/api/v1/ateliers").with(adminEcole))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DESACTIVE"))
                .andExpect(jsonPath("$.module").value("ATELIERS"));
        mvc.perform(get("/api/v1/catalogue").with(adminEcole)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/mobile-money/transactions").with(adminEcole))
                .andExpect(jsonPath("$.code").value("MODULE_DESACTIVE"));
        mvc.perform(get("/api/v1/annees").with(adminEcole)).andExpect(status().isOk());

        // La connexion annonce les modules désactivés
        EtablissementAccessible vu = auth.etablissementsDe(admin).stream().filter(e -> e.id().equals(ecole))
                .findFirst().orElseThrow();
        assertThat(vu.modulesDesactives()).containsExactly("ATELIERS", "MOBILE_MONEY", "SCOLARITE");

        // Tout réactiver : les écrans reviennent, rien n'a été effacé
        mvc.perform(put("/api/v1/plateforme/etablissements/{id}/modules", ecole).with(superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actifs\":[\"ATELIERS\",\"EMPLOIS_DU_TEMPS\",\"PROGRESSION\",\"VIE_SCOLAIRE\",\"SCOLARITE\",\"MOBILE_MONEY\",\"ESPACE_PARENT\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ateliers").with(adminEcole)).andExpect(status().isOk());
        assertThat(auth.etablissementsDe(admin).stream().filter(e -> e.id().equals(ecole)).findFirst().orElseThrow()
                .modulesDesactives()).isEmpty();
    }
}
