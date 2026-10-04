package bf.edutech.plateforme.utilisateurs;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Renouvellement périodique du mot de passe du personnel, sur une vraie base PostgreSQL :
 * au début de chaque période (trimestre ou semestre de l'année active), un membre du personnel
 * dont le mot de passe date d'avant la période doit en choisir un nouveau ; les parents ne sont
 * pas concernés.
 */
@SpringBootTest
@ActiveProfiles("test")
class RenouvellementIntegrationTest {

    private static final String COOKIE = "plateforme_rt";
    private static final String MDP_DEFINITIF = "Definitif-2026";
    private static final String MDP_PERIODE = "Trimestre2-2026";

    @Autowired private EtablissementsService etablissements;
    @Autowired private MembresService membres;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;

    private record Session(String jeton, Cookie cookie) {
    }

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    @Test
    void lePersonnelChangeDeMotDePasseAChaquePeriode() throws Exception {
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String telAdmin = telephone();
        EtablissementsService.ResultatCreation creation = etablissements.creer("renouv-" + suffixe, "Lycée " + suffixe,
                telAdmin, "ADMIN", "Test");
        ecole = creation.etablissement().id();
        Session admin = finaliser(connecter(telAdmin, creation.motDePasseTemporaire()), creation.motDePasseTemporaire(), MDP_DEFINITIF);

        String telEnseignant = telephone();
        String telParent = telephone();
        String mdpEnseignant = dans(() -> membres.ajouter(telEnseignant, "SANOU", "Paul", Role.ENSEIGNANT)).motDePasseTemporaire();
        String mdpParent = dans(() -> membres.ajouter(telParent, "OUEDRAOGO", "Mariam", Role.PARENT)).motDePasseTemporaire();
        finaliser(connecter(telEnseignant, mdpEnseignant), mdpEnseignant, MDP_DEFINITIF);
        finaliser(connecter(telParent, mdpParent), mdpParent, MDP_DEFINITIF);

        // Sans année active : aucune période, aucun renouvellement
        connexion(telEnseignant, MDP_DEFINITIF).andExpect(jsonPath("$.doitChangerMotDePasse").value(false));

        // Année active découpée en trimestres : le 2e trimestre a commencé il y a quelques semaines
        dans(() -> profils.initialiserProfilsTypes());
        UUID technique = dans(() -> profils.lister()).stream()
                .filter(p -> p.code().equals("TECHNIQUE")).findFirst().orElseThrow().id();
        int an = aujourdhui.getYear();
        AnneeVue annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(200), aujourdhui.plusDays(150)));
        dans(() -> periodes.generer(annee.id(), technique));
        // L'ouverture de l'année exige au moins une classe
        UUID filiere = dans(() -> filieres.creer("F3" + ThreadLocalRandom.current().nextInt(100, 999), "Électrotechnique",
                "Second cycle", "BAC F3", technique)).id();
        dans(() -> classes.creer(annee.id(), filiere, "2nde F3", "2nde", null));
        dans(() -> annees.ouvrir(annee.id()));
        LocalDate debutT2 = jdbc.queryForObject("select debut_periode_en_cours(?, ?)", LocalDate.class, ecole, aujourdhui);
        org.assertj.core.api.Assertions.assertThat(debutT2).isAfter(aujourdhui.minusDays(200)).isBeforeOrEqualTo(aujourdhui);

        // Mots de passe choisis pendant le 1er trimestre (avant le début du 2e)
        jdbc.update("update utilisateur set mot_de_passe_change_le = ? where telephone in (?, ?)",
                java.sql.Timestamp.valueOf(debutT2.minusDays(10).atStartOfDay()), "+226" + telEnseignant, "+226" + telParent);

        // L'enseignant doit choisir un nouveau mot de passe avant tout le reste
        MvcResult r = connexion(telEnseignant, MDP_DEFINITIF)
                .andExpect(jsonPath("$.doitChangerMotDePasse").value(true))
                .andExpect(jsonPath("$.motifChangementMotDePasse").value("RENOUVELLEMENT"))
                .andReturn();
        Session enseignant = new Session(lire(r, "$.jetonAcces"), r.getResponse().getCookie(COOKIE));
        mvc.perform(get("/api/v1/annees").header("Authorization", "Bearer " + enseignant.jeton()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MOT_DE_PASSE_A_CHANGER"));
        mvc.perform(get("/api/v1/moi").header("Authorization", "Bearer " + enseignant.jeton()))
                .andExpect(jsonPath("$.motifChangementMotDePasse").value("RENOUVELLEMENT"));
        // L'ancien mot de passe ne peut pas être repris
        mvc.perform(post("/api/v1/moi/mot-de-passe").header("Authorization", "Bearer " + enseignant.jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motDePasseActuel\":\"%s\",\"nouveauMotDePasse\":\"%s\"}".formatted(MDP_DEFINITIF, MDP_DEFINITIF)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOT_DE_PASSE_IDENTIQUE"));
        finaliser(enseignant, MDP_DEFINITIF, MDP_PERIODE);
        connexion(telEnseignant, MDP_PERIODE)
                .andExpect(jsonPath("$.doitChangerMotDePasse").value(false))
                .andExpect(jsonPath("$.motifChangementMotDePasse").doesNotExist());

        // Le parent n'est pas concerné
        connexion(telParent, MDP_DEFINITIF).andExpect(jsonPath("$.doitChangerMotDePasse").value(false));

        // La page Comptes de l'administrateur montre la date du dernier changement
        mvc.perform(get("/api/v1/comptes").header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.telephone=='+226" + telEnseignant + "')].motDePasseChangeLe",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.notNullValue())));
    }

    // ------------------------------------------------------------------ outils

    private org.springframework.test.web.servlet.ResultActions connexion(String tel, String mdp) throws Exception {
        return mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                .content("{\"telephone\":\"" + tel + "\",\"motDePasse\":\"" + mdp + "\"}")).andExpect(status().isOk());
    }

    private Session connecter(String tel, String mdp) throws Exception {
        MvcResult r = connexion(tel, mdp).andExpect(jsonPath("$.selectionRequise").value(false)).andReturn();
        return new Session(lire(r, "$.jetonAcces"), r.getResponse().getCookie(COOKIE));
    }

    private Session finaliser(Session session, String actuel, String nouveau) throws Exception {
        mvc.perform(post("/api/v1/moi/mot-de-passe")
                        .header("Authorization", "Bearer " + session.jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motDePasseActuel\":\"%s\",\"nouveauMotDePasse\":\"%s\"}".formatted(actuel, nouveau)))
                .andExpect(status().isNoContent());
        MvcResult r = mvc.perform(post("/api/v1/auth/rafraichir").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doitChangerMotDePasse").value(false))
                .andReturn();
        return new Session(lire(r, "$.jetonAcces"), r.getResponse().getCookie(COOKIE));
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String lire(MvcResult resultat, String chemin) throws Exception {
        return JsonPath.read(resultat.getResponse().getContentAsString(), chemin);
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }
}
