package bf.edutech.plateforme.utilisateurs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

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

import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Intérim du chef d'établissement (v0.47) : le proviseur désigne le censeur pour son absence ; pendant la période,
 * les jetons du censeur portent le rôle ADMIN_ECOLE ; un intérimaire ne désigne pas lui-même d'intérim.
 */
@SpringBootTest
@ActiveProfiles("test")
class InterimsIntegrationTest {

    @Autowired private WebApplicationContext contexte;
    @Autowired private EtablissementsService etablissements;
    @Autowired private MembresService membres;
    @Autowired private AccesEtablissements acces;

    private UUID ecole;

    @Test
    void leCenseurAssureLInterimDuProviseur() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var creation = etablissements.creer("in-" + suffixe, "Lycée " + suffixe, telephone(), "ADMIN", "Test");
        ecole = creation.etablissement().id();
        UUID proviseur = creation.administrateur().utilisateurId();
        UUID censeur = membre(Role.CENSEUR);
        UUID intendant = membre(Role.INTENDANT);
        LocalDate j = LocalDate.now(ZoneOffset.UTC);
        assertThat(roles(censeur)).containsExactly("CENSEUR");

        String demande = "{\"utilisateurId\":\"%s\",\"du\":\"%s\",\"au\":\"%s\",\"motif\":\"%s\"}";
        // L'intendant n'est pas éligible ; le censeur ne se désigne pas ; pas d'intérim dans le passé
        mvc.perform(post("/api/v1/interims").with(jeton(proviseur, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(intendant, j, j.plusDays(5), "Mission")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTERIMAIRE_NON_ELIGIBLE"));
        mvc.perform(post("/api/v1/interims").with(jeton(censeur, "CENSEUR")).contentType(MediaType.APPLICATION_JSON)
                .content(demande.formatted(censeur, j, j.plusDays(5), "Mission"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/interims").with(jeton(proviseur, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(censeur, j.minusDays(1), j.plusDays(5), "Mission")))
                .andExpect(status().isBadRequest());

        String cree = mvc.perform(post("/api/v1/interims").with(jeton(proviseur, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(censeur, j, j.plusDays(5), "Mission à Ouagadougou")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enCours").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(cree.replaceAll("(?s)^\\{\"id\":\"([0-9a-f-]+)\".*", "$1"));
        mvc.perform(post("/api/v1/interims").with(jeton(proviseur, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(censeur, j.plusDays(3), j.plusDays(8), "Autre")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTERIM_EXISTANT"));

        // Pendant l'intérim : le censeur a les droits du chef d'établissement, sans pouvoir désigner d'intérim
        assertThat(roles(censeur)).containsExactlyInAnyOrder("ADMIN_ECOLE", "CENSEUR");
        mvc.perform(post("/api/v1/interims/{id}/fin", id).with(jeton(censeur, "ADMIN_ECOLE", "CENSEUR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/interims").with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].motif").value("Mission à Ouagadougou"));

        // Retour anticipé du proviseur : l'intérim prend fin
        mvc.perform(post("/api/v1/interims/{id}/fin", id).with(jeton(proviseur, "ADMIN_ECOLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enCours").value(false))
                .andExpect(jsonPath("$.termine").value(true));
        assertThat(roles(censeur)).containsExactly("CENSEUR");
        mvc.perform(post("/api/v1/interims/{id}/fin", id).with(jeton(proviseur, "ADMIN_ECOLE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTERIM_TERMINE"));
    }

    private List<String> roles(UUID utilisateur) {
        return acces.trouver(utilisateur, ecole).orElseThrow().roles();
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return dans(() -> membres.lister()).stream().filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private RequestPostProcessor jeton(UUID utilisateur, String... roles) {
        SimpleGrantedAuthority[] autorites = new SimpleGrantedAuthority[roles.length + 1];
        autorites[0] = new SimpleGrantedAuthority("TYPE_ACCES");
        for (int i = 0; i < roles.length; i++) {
            autorites[i + 1] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return jwt().jwt(t -> t.subject(utilisateur.toString()).claim("tenant_id", ecole.toString())
                .claim("typ_jeton", "ACCES").claim("roles", List.of(roles))).authorities(autorites);
    }
}
