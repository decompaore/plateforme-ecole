package bf.edutech.plateforme.utilisateurs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Espaces (v0.41) : une mère d'élève devient administratrice du pays. À la connexion, elle choisit
 * entre l'administration du pays et l'établissement de son enfant, où elle n'a que le rôle PARENT ;
 * elle passe ensuite de l'un à l'autre. Elle ne peut jamais devenir membre du personnel.
 */
@SpringBootTest
@ActiveProfiles("test")
class EspacesIntegrationTest {

    @Autowired private WebApplicationContext contexte;
    @Autowired private AuthService auth;
    @Autowired private EtablissementsService etablissements;
    @Autowired private ElevesService eleves;
    @Autowired private EspaceParentService espaceParent;
    @Autowired private MembresService membres;

    private static final RequestPostProcessor SUPER_ADMIN = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
            .claim("typ_jeton", "ACCES"))
            .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    private UUID ecole;

    @Test
    void unAdministrateurPaysParentChoisitSonEspace() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var creation = etablissements.creer("es-" + suffixe, "Lycée " + suffixe, telephone(), "ADMIN", "Test");
        ecole = creation.etablissement().id();
        UUID adminEcole = creation.administrateur().utilisateurId();

        // 1. Elle est d'abord mère d'élève : son espace parent est ouvert
        String tel = telephone();
        DossierEleveVue dossier = dans(() -> eleves.creer(new DonneesEleve(null, "OUEDRAOGO", "Awa", Sexe.F,
                LocalDate.of(2011, 2, 3), null, null, null, null),
                List.of(new DonneesResponsable("OUEDRAOGO", "Mariam", tel, LienParente.MERE, null, null, true, null))));
        String motDePasse = dans(() -> espaceParent.ouvrir(dossier.responsables().get(0).responsableId()))
                .motDePasseTemporaire();
        assertThat(motDePasse).isNotNull();

        // 2. Le super administrateur la nomme administratrice du Burkina Faso : permis, elle n'y est que parent
        String pays = mvc.perform(get("/api/v1/plateforme/territoire/pays").with(SUPER_ADMIN))
                .andReturn().getResponse().getContentAsString();
        String bf = ((List<String>) JsonPath.read(pays, "$[?(@.code == 'BF')].id")).get(0);
        String nomination = mvc.perform(post("/api/v1/plateforme/territoire/pays/{id}/administrateurs", bf).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"telephone\":\"" + tel + "\",\"nom\":\"Ouedraogo\",\"prenoms\":\"Mariam\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID utilisateur = UUID.fromString(JsonPath.read(nomination, "$.administrateur.utilisateurId"));

        // 3. Connexion : choix entre l'administration du pays et l'établissement de son enfant
        AuthService.ResultatConnexion c = auth.connexion(tel, motDePasse);
        assertThat(c.jetonSelection()).isNotNull();
        assertThat(c.jetonAcces()).isNull();
        assertThat(c.administration().type()).isEqualTo("PAYS");
        assertThat(c.administration().libelle()).isEqualTo("Administration du pays : Burkina Faso");
        assertThat(c.etablissements()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(ecole);
            assertThat(e.roles()).containsExactly("PARENT");
        });

        // 4. Dans l'établissement : seulement parent, jamais les droits du pays
        AuthService.ResultatConnexion parent = auth.choisirEtablissement(utilisateur, ecole, null);
        assertThat(parent.etablissementActif().roles()).containsExactly("PARENT");
        assertThat(parent.adminPays()).isNull();
        assertThat(parent.superAdmin()).isFalse();
        assertThat(parent.administration()).isNotNull();

        // 5. « Changer d'espace » : l'administration du pays
        AuthService.ResultatConnexion administration = auth.choisirAdministration(utilisateur, parent.jetonRafraichissement());
        assertThat(administration.etablissementActif()).isNull();
        assertThat(administration.adminPays().code()).isEqualTo("BF");
        assertThat(administration.etablissements()).hasSize(1);

        // Par l'API : le jeton de sélection ouvre l'administration ; un compte sans administration est refusé
        RequestPostProcessor selection = jwt().jwt(j -> j.subject(utilisateur.toString()).claim("typ_jeton", "SELECTION"))
                .authorities(new SimpleGrantedAuthority("TYPE_SELECTION"));
        mvc.perform(post("/api/v1/auth/administration").with(selection))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminPays.code").value("BF"))
                .andExpect(jsonPath("$.administration.type").value("PAYS"));
        RequestPostProcessor autre = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("typ_jeton", "SELECTION"))
                .authorities(new SimpleGrantedAuthority("TYPE_SELECTION"));
        mvc.perform(post("/api/v1/auth/administration").with(autre)).andExpect(status().isUnauthorized());

        // L'établissement de son enfant ne gère pas ce compte : ni réinitialisation, ni déverrouillage
        RequestPostProcessor directeur = jwt().jwt(j -> j.subject(adminEcole.toString()).claim("typ_jeton", "ACCES")
                .claim("tenant_id", ecole.toString()).claim("roles", List.of("ADMIN_ECOLE")))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_ECOLE"));
        mvc.perform(post("/api/v1/comptes/{id}/reinitialisation", utilisateur).with(directeur)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/comptes/{id}/deverrouillage", utilisateur).with(directeur)).andExpect(status().isForbidden());

        // 6. Jamais membre du personnel d'un établissement
        assertThatThrownBy(() -> dans(() -> membres.ajouter(tel, "OUEDRAOGO", "Mariam", Role.SECRETARIAT)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("COMPTE_PLATEFORME");
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }
}
