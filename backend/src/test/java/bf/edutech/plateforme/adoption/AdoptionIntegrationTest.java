package bf.edutech.plateforme.adoption;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.utilisateurs.AuthService;

/**
 * Mesure de l'adoption : une connexion compte comme un jour d'activité ; l'administrateur voit
 * l'activité de son personnel (et seulement du sien) ; le super administrateur voit les nombres
 * de tous les établissements et ceux qui sont à accompagner.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdoptionIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private AuthService auth;
    @Autowired private WebApplicationContext contexte;

    private record Ecole(UUID id, UUID admin, String telephone, String motDePasse, RequestPostProcessor jeton) {
    }

    private Ecole ecole() {
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String telephone = "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        EtablissementsService.ResultatCreation c = etablissements.creer("ado-" + suffixe, "Lycée " + suffixe,
                telephone, "ADMIN", "Adoption");
        UUID id = c.etablissement().id();
        UUID admin = c.administrateur().utilisateurId();
        RequestPostProcessor jeton = jwt().jwt(j -> j.subject(admin.toString())
                .claim("tenant_id", id.toString()).claim("typ_jeton", "ACCES").claim("roles", List.of("ADMIN_ECOLE")))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_ECOLE"));
        return new Ecole(id, admin, telephone, c.motDePasseTemporaire(), jeton);
    }

    @Test
    void uneConnexionCompteCommeUnJourDActivite() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        Ecole a = ecole();
        Ecole b = ecole();

        // Avant toute connexion : personne n'est actif
        mvc.perform(get("/api/v1/adoption").with(a.jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comptes").value(1))
                .andExpect(jsonPath("$.actifs").value(0))
                .andExpect(jsonPath("$.personnes.length()").value(1))
                .andExpect(jsonPath("$.personnes[0].joursActifs").value(0));

        auth.connexion(a.telephone(), a.motDePasse());

        mvc.perform(get("/api/v1/adoption").with(a.jeton()).param("jours", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotidien.length()").value(7))
                .andExpect(jsonPath("$.actifs").value(1))
                .andExpect(jsonPath("$.personnel.total").value(1))
                .andExpect(jsonPath("$.personnel.actifs").value(1))
                .andExpect(jsonPath("$.personnes[0].utilisateurId").value(a.admin().toString()))
                .andExpect(jsonPath("$.personnes[0].roles[0]").value("ADMIN_ECOLE"))
                .andExpect(jsonPath("$.personnes[0].joursActifs").value(1))
                .andExpect(jsonPath("$.quotidien[6].actifs").value(1))
                .andExpect(jsonPath("$.indicateurs[0].code").value("APPELS"));
        // L'autre établissement ne voit que son personnel
        mvc.perform(get("/api/v1/adoption").with(b.jeton()))
                .andExpect(jsonPath("$.actifs").value(0))
                .andExpect(jsonPath("$.personnes[0].utilisateurId").value(b.admin().toString()));

        // Super administrateur : des nombres pour chaque établissement
        RequestPostProcessor superAdmin = jwt().jwt(j -> j.subject(a.admin().toString()).claim("typ_jeton", "ACCES"))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        mvc.perform(get("/api/v1/plateforme/adoption").with(a.jeton())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/plateforme/adoption").with(superAdmin).param("jours", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotidien.length()").value(30))
                .andExpect(jsonPath("$.details[?(@.id == '" + a.id() + "')].actifs").value(1))
                .andExpect(jsonPath("$.details[?(@.id == '" + a.id() + "')].comptes").value(1))
                .andExpect(jsonPath("$.details[?(@.id == '" + a.id() + "')].joursActifs").value(1))
                .andExpect(jsonPath("$.details[?(@.id == '" + b.id() + "')].actifs").value(0))
                .andExpect(jsonPath("$.details[?(@.id == '" + b.id() + "')].alertes[0]").value("SANS_ACTIVITE"));
        mvc.perform(post("/api/v1/plateforme/adoption/calcul").with(superAdmin).param("jours", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debut").isString());
    }
}
