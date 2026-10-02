package bf.edutech.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembreEtablissementRepository;

/**
 * Tests d'intégration du socle, sur une vraie base PostgreSQL (profil "test").
 * <p>
 * Chaque test crée ses propres établissements avec des codes et des numéros
 * aléatoires : les tests peuvent être relancés sans vider la base.
 */
@SpringBootTest
@ActiveProfiles("test")
class SocleIntegrationTest {

    private static final String COOKIE = "plateforme_rt";
    private static final String TEL_SUPER_ADMIN = "69000000";
    private static final String MDP_SUPER_ADMIN_INITIAL = "MotDePasseTest-2026";
    private static final String MDP_DEFINITIF = "Definitif-2026";

    private static String jetonSuperAdmin;

    @Autowired
    private WebApplicationContext contexte;

    @Autowired
    private EtablissementsService etablissements;

    @Autowired
    private MembreEtablissementRepository membres;

    @Autowired
    private PlatformTransactionManager gestionnaireTransactions;

    private MockMvc mvc;

    /** Jeton d'accès + cookie de rafraîchissement. */
    private record Session(String jeton, Cookie cookie) {
    }

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    // ------------------------------------------------------------------
    // Scénarios
    // ------------------------------------------------------------------

    @Test
    void parcoursCompletAvecUnEnseignantDansDeuxEtablissements() throws Exception {
        String telAdminA = telephone();
        String telAdminB = telephone();
        String telEnseignant = telephone();

        MvcResult creationA = creerEtablissement("lycee-a", telAdminA);
        MvcResult creationB = creerEtablissement("cfp-b", telAdminB);
        String idA = lire(creationA, "$.etablissement.id");
        String idB = lire(creationB, "$.etablissement.id");

        // L'administrateur doit changer son mot de passe temporaire avant tout
        Session adminA = connecterUnique(telAdminA, lire(creationA, "$.motDePasseTemporaire"));
        mvc.perform(get("/api/v1/membres").header("Authorization", "Bearer " + adminA.jeton()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MOT_DE_PASSE_A_CHANGER"));
        adminA = finaliserMotDePasse(adminA, lire(creationA, "$.motDePasseTemporaire"));
        Session adminB = finaliserMotDePasse(
                connecterUnique(telAdminB, lire(creationB, "$.motDePasseTemporaire")),
                lire(creationB, "$.motDePasseTemporaire"));

        // L'école A crée le compte de l'enseignant ; l'école B réutilise ce même compte
        MvcResult ajoutA = ajouterMembre(adminA, telEnseignant, "ENSEIGNANT");
        String mdpEnseignant = lire(ajoutA, "$.motDePasseTemporaire");
        assertThat(mdpEnseignant).isNotBlank();
        MvcResult ajoutB = ajouterMembre(adminB, telEnseignant, "ENSEIGNANT");
        assertThat((Object) JsonPath.read(ajoutB.getResponse().getContentAsString(), "$.motDePasseTemporaire"))
                .isNull();

        // Connexion de l'enseignant : choix entre deux établissements
        MvcResult connexion = mvc.perform(post("/api/v1/auth/connexion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(identifiants(telEnseignant, mdpEnseignant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequise").value(true))
                .andExpect(jsonPath("$.etablissements", hasSize(2)))
                .andReturn();
        String jetonSelection = lire(connexion, "$.jetonSelection");

        // Un jeton de sélection ne donne accès à rien d'autre qu'au choix de l'établissement
        mvc.perform(get("/api/v1/moi").header("Authorization", "Bearer " + jetonSelection))
                .andExpect(status().isForbidden());

        Session enseignantA = choisir(jetonSelection, idA);
        enseignantA = finaliserMotDePasse(enseignantA, mdpEnseignant);
        enseignantA = choisir(enseignantA.jeton(), idA);
        mvc.perform(get("/api/v1/moi").header("Authorization", "Bearer " + enseignantA.jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etablissementId").value(idA))
                .andExpect(jsonPath("$.roles", hasItem("ENSEIGNANT")));

        // Isolation : l'administrateur A ne voit que ses membres, même en forgeant un en-tête
        mvc.perform(get("/api/v1/membres")
                        .header("Authorization", "Bearer " + adminA.jeton())
                        .header("X-Tenant-Id", idB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].telephone", hasItem("+226" + telAdminA)))
                .andExpect(jsonPath("$[*].telephone", hasItem("+226" + telEnseignant)))
                .andExpect(jsonPath("$[*].telephone", not(hasItem("+226" + telAdminB))));

        // Contrôle des rôles
        mvc.perform(get("/api/v1/membres").header("Authorization", "Bearer " + enseignantA.jeton()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(get("/api/v1/plateforme/etablissements").header("Authorization", "Bearer " + adminA.jeton()))
                .andExpect(status().isForbidden());

        // Changement d'établissement
        Session enseignantB = choisir(enseignantA.jeton(), idB);
        mvc.perform(get("/api/v1/moi").header("Authorization", "Bearer " + enseignantB.jeton()))
                .andExpect(jsonPath("$.etablissementId").value(idB));
    }

    @Test
    void leCompteEstVerrouilleApresCinqEchecs() throws Exception {
        String telAdmin = telephone();
        MvcResult creation = creerEtablissement("verrou", telAdmin);
        String mdp = lire(creation, "$.motDePasseTemporaire");

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                            .content(identifiants(telAdmin, "mauvais-mot-de-passe")))
                    .andExpect(status().isUnauthorized());
        }
        // Même le bon mot de passe est refusé pendant le verrouillage
        mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                        .content(identifiants(telAdmin, mdp)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void laReutilisationDUnJetonDeRafraichissementRevoqueToutLaSession() throws Exception {
        String telAdmin = telephone();
        MvcResult creation = creerEtablissement("rotation", telAdmin);
        Session session = connecterUnique(telAdmin, lire(creation, "$.motDePasseTemporaire"));

        Cookie deuxieme = renouveler(session.cookie());
        Cookie troisieme = renouveler(deuxieme);

        // Le premier jeton est rejoué alors que son successeur a déjà servi (vol simulé) : refus...
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(session.cookie()))
                .andExpect(status().isUnauthorized());
        // ... et toute la famille est révoquée, y compris le jeton légitime
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(troisieme))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uneReponseDeRenouvellementPerdueNeFermePasLaSession() throws Exception {
        String telAdmin = telephone();
        MvcResult creation = creerEtablissement("grace", telAdmin);
        Session session = connecterUnique(telAdmin, lire(creation, "$.motDePasseTemporaire"));

        // Le serveur renouvelle, mais la réponse n'arrive jamais au téléphone : le cookie émis est perdu
        Cookie perdu = renouveler(session.cookie());

        // Le téléphone renvoie l'ancien cookie quelques secondes plus tard : accepté, nouveau cookie
        Cookie repris = renouveler(session.cookie());
        assertThat(repris.getValue()).isNotEqualTo(perdu.getValue());

        // La session continue normalement avec le nouveau cookie
        Cookie suivant = renouveler(repris);
        assertThat(suivant).isNotNull();

        // Le cookie perdu, lui, ne sert plus : le présenter révèle un vol et ferme la session
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(perdu)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(suivant)).andExpect(status().isUnauthorized());
    }

    private Cookie renouveler(Cookie cookie) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/rafraichir").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn();
        Cookie nouveau = r.getResponse().getCookie(COOKIE);
        assertThat(nouveau).isNotNull();
        return nouveau;
    }

    @Test
    void laRowLevelSecurityFiltreAuNiveauDeLaBase() {
        EtablissementsService.ResultatCreation creation = etablissements.creer("rls-" + suffixe(), "École RLS",
                telephone(), "TEST", "Rls");
        UUID idEtablissement = creation.etablissement().id();
        TransactionTemplate transaction = new TransactionTemplate(gestionnaireTransactions);

        Long sansEtablissement = transaction.execute(s -> membres.count());
        Long dansEtablissement = TenantContext.executerPour(idEtablissement,
                () -> transaction.execute(s -> membres.count()));

        assertThat(sansEtablissement).isZero();
        assertThat(dansEtablissement).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    private String superAdmin() throws Exception {
        if (jetonSuperAdmin != null) {
            return jetonSuperAdmin;
        }
        MvcResult resultat = mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                .content(identifiants(TEL_SUPER_ADMIN, MDP_SUPER_ADMIN_INITIAL))).andReturn();
        String mdpActuel = MDP_SUPER_ADMIN_INITIAL;
        if (resultat.getResponse().getStatus() != 200) {
            // Base déjà utilisée par une exécution précédente : le mot de passe a été changé
            mdpActuel = MDP_DEFINITIF;
            resultat = mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                            .content(identifiants(TEL_SUPER_ADMIN, mdpActuel)))
                    .andExpect(status().isOk())
                    .andReturn();
        }
        Session session = new Session(lire(resultat, "$.jetonAcces"), resultat.getResponse().getCookie(COOKIE));
        if (Boolean.TRUE.equals(JsonPath.read(resultat.getResponse().getContentAsString(),
                "$.doitChangerMotDePasse"))) {
            session = finaliserMotDePasse(session, mdpActuel);
        }
        jetonSuperAdmin = session.jeton();
        return jetonSuperAdmin;
    }

    private MvcResult creerEtablissement(String prefixe, String telAdmin) throws Exception {
        return mvc.perform(post("/api/v1/plateforme/etablissements")
                        .header("Authorization", "Bearer " + superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s-%s","nom":"Établissement %s","telephoneAdministrateur":"%s",
                                 "nomAdministrateur":"ADMIN","prenomsAdministrateur":"Test"}
                                """.formatted(prefixe, suffixe(), prefixe, telAdmin)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult ajouterMembre(Session admin, String tel, String role) throws Exception {
        return mvc.perform(post("/api/v1/membres")
                        .header("Authorization", "Bearer " + admin.jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"telephone":"%s","nom":"MEMBRE","prenoms":"Test","role":"%s"}
                                """.formatted(tel, role)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private Session connecterUnique(String tel, String mdp) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                        .content(identifiants(tel, mdp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequise").value(false))
                .andReturn();
        return new Session(lire(r, "$.jetonAcces"), r.getResponse().getCookie(COOKIE));
    }

    private Session choisir(String jeton, String idEtablissement) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/etablissement")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"etablissementId\":\"" + idEtablissement + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(lire(r, "$.jetonAcces"), r.getResponse().getCookie(COOKIE));
    }

    /** Change le mot de passe temporaire puis obtient un jeton à jour via le rafraîchissement. */
    private Session finaliserMotDePasse(Session session, String mdpTemporaire) throws Exception {
        mvc.perform(post("/api/v1/moi/mot-de-passe")
                        .header("Authorization", "Bearer " + session.jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"motDePasseActuel":"%s","nouveauMotDePasse":"%s"}
                                """.formatted(mdpTemporaire, MDP_DEFINITIF)))
                .andExpect(status().isNoContent());
        MvcResult r = mvc.perform(post("/api/v1/auth/rafraichir").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(lire(r, "$.jetonAcces"), r.getResponse().getCookie(COOKIE));
    }

    private static String identifiants(String tel, String mdp) {
        return "{\"telephone\":\"" + tel + "\",\"motDePasse\":\"" + mdp + "\"}";
    }

    private static String lire(MvcResult resultat, String chemin) throws Exception {
        return JsonPath.read(resultat.getResponse().getContentAsString(), chemin);
    }

    /** Numéro national aléatoire à 8 chiffres commençant par 7 (jamais celui du super admin). */
    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private static String suffixe() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
    }
}
