package bf.edutech.plateforme.pilotage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.AuthentificationException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.AuthService;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Pilotage (v0.37) : une direction régionale et ses deux directions provinciales, un lycée
 * dans chacune. Lycée A : une 3e (2 filles, 1 garçon) qui passe le BEPC, 2 admis sur 3 ;
 * lycée B : une 6e de 2 garçons, sans décision. La direction régionale voit les nombres de
 * chaque lycée et leurs cumuls, jamais au-dessus d'elle ni hors de son ressort.
 */
@SpringBootTest
@ActiveProfiles("test")
class PilotageIntegrationTest {

    @Autowired private WebApplicationContext contexte;
    @Autowired private AuthService auth;
    @Autowired private EtablissementsService etablissements;
    @Autowired private MembresService membres;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private static final RequestPostProcessor SUPER_ADMIN = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
            .claim("typ_jeton", "ACCES"))
            .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private UUID ecole;

    @Test
    void uneDirectionNeVoitQueDesNombresDeSonRessort() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String pays = mvc.perform(get("/api/v1/plateforme/territoire/pays").with(SUPER_ADMIN))
                .andReturn().getResponse().getContentAsString();
        String bf = ((List<String>) JsonPath.read(pays, "$[?(@.code == 'BF')].id")).get(0);

        // Ministère, une direction régionale et deux directions provinciales
        String ministere = JsonPath.read(mvc.perform(post("/api/v1/plateforme/territoire/pays/{id}/ministeres", bf)
                .with(SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sigle\":\"PI" + suffixe + "\",\"nom\":\"Ministère " + suffixe + "\",\"niveaux\":[\"Direction régionale\",\"Direction provinciale\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contenu\":\"DR;DR Centre;\\nDP1;DP Kadiogo;DR\\nDP2;DP Oubritenga;DR\",\"simulation\":false}"))
                .andExpect(jsonPath("$.creees").value(3));
        String arbre = mvc.perform(get("/api/v1/plateforme/territoire/ministeres/{id}/directions", ministere)
                .with(SUPER_ADMIN)).andReturn().getResponse().getContentAsString();
        UUID dr = UUID.fromString(((List<String>) JsonPath.read(arbre, "$[?(@.code == 'DR')].id")).get(0));
        UUID dp1 = UUID.fromString(((List<String>) JsonPath.read(arbre, "$[?(@.code == 'DP1')].id")).get(0));
        UUID dp2 = UUID.fromString(((List<String>) JsonPath.read(arbre, "$[?(@.code == 'DP2')].id")).get(0));

        String libelle = j.getYear() + "-" + (j.getYear() + 1);
        UUID lyceeA = lycee("pa-" + suffixe.toLowerCase(), "Lycée A " + suffixe, dp1);
        UUID troisieme = structure(libelle, "3A", "3e");
        UUID awa = inscrire("OUEDRAOGO", "Awa", Sexe.F, troisieme);
        UUID mariam = inscrire("KABORE", "Mariam", Sexe.F, troisieme);
        UUID issa = inscrire("SAWADOGO", "Issa", Sexe.M, troisieme);
        dansTransaction(() -> {
            jdbc.update("insert into classe_examen (tenant_id, classe_id, examen) values (?, ?, 'BEPC')", ecole, troisieme);
            decision(troisieme, awa, "ADMIS", "ADMIS");
            decision(troisieme, mariam, "ADMIS", "ADMIS");
            decision(troisieme, issa, "REDOUBLE", "AJOURNE");
            return null;
        });
        UUID lyceeB = lycee("pb-" + suffixe.toLowerCase(), "Lycée B " + suffixe, dp2);
        UUID sixieme = structure(libelle, "6A", "6e");
        inscrire("ZONGO", "Ali", Sexe.M, sixieme);
        inscrire("TRAORE", "Paul", Sexe.M, sixieme);

        // L'administrateur pays (ou le super administrateur) nomme le compte de la direction régionale
        String tel = telephone();
        String nomination = mvc.perform(post("/api/v1/plateforme/territoire/directions/{id}/comptes", dr)
                .with(SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"telephone\":\"" + tel + "\",\"nom\":\"Sanou\",\"prenoms\":\"Aline\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.compte.nom").value("SANOU"))
                .andReturn().getResponse().getContentAsString();
        String compteId = JsonPath.read(nomination, "$.compte.utilisateurId");
        String motDePasse = JsonPath.read(nomination, "$.motDePasseTemporaire");
        mvc.perform(get("/api/v1/plateforme/territoire/directions/{id}/comptes", dr).with(SUPER_ADMIN))
                .andExpect(jsonPath("$[0].utilisateurId").value(compteId));
        // Un administrateur d'un autre pays ne nomme rien ici
        RequestPostProcessor autrePays = jwt().jwt(x -> x.subject(UUID.randomUUID().toString()).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("ADMIN_PAYS")).claim("pays_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_PAYS"));
        mvc.perform(get("/api/v1/plateforme/territoire/directions/{id}/comptes", dr).with(autrePays))
                .andExpect(status().isForbidden());

        // Sa connexion : session sans établissement, sa direction ; jamais membre d'un établissement
        AuthService.ResultatConnexion connexion = auth.connexion(tel, motDePasse);
        assertThat(connexion.direction()).isNotNull();
        assertThat(connexion.direction().id()).isEqualTo(dr);
        assertThat(connexion.direction().chemin()).contains("DR Centre");
        assertThat(connexion.etablissementActif()).isNull();
        assertThat(connexion.adminPays()).isNull();
        ecole = lyceeA;
        assertThatThrownBy(() -> dans(() -> membres.ajouter(tel, "SANOU", "Aline", Role.SECRETARIAT)))
                .isInstanceOf(RegleMetierException.class);

        RequestPostProcessor direction = jwt().jwt(x -> x.subject(compteId).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("DIRECTION")).claim("direction_id", dr.toString()))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_DIRECTION"));

        // Tableau de bord : sa direction, ses deux directions provinciales, les deux lycées
        mvc.perform(get("/api/v1/pilotage").with(direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perimetre.id").value(dr.toString()))
                .andExpect(jsonPath("$.perimetre.niveau").value("Direction régionale"))
                .andExpect(jsonPath("$.parent").doesNotExist())
                .andExpect(jsonPath("$.annee").value(libelle))
                .andExpect(jsonPath("$.niveauDirections").value("Direction provinciale"))
                .andExpect(jsonPath("$.directions.length()").value(2))
                .andExpect(jsonPath("$.etablissements.length()").value(2))
                .andExpect(jsonPath("$.synthese.etablissements").value(2))
                .andExpect(jsonPath("$.synthese.classes").value(2))
                .andExpect(jsonPath("$.synthese.eleves.total").value(5))
                .andExpect(jsonPath("$.synthese.eleves.filles").value(2))
                .andExpect(jsonPath("$.synthese.decides.total").value(3))
                .andExpect(jsonPath("$.synthese.tauxAdmission").value(66.7))
                .andExpect(jsonPath("$.synthese.examens[0].examen").value("BEPC"))
                .andExpect(jsonPath("$.synthese.examens[0].candidats.total").value(3))
                .andExpect(jsonPath("$.synthese.examens[0].taux").value(66.7))
                .andExpect(jsonPath("$.synthese.examens[0].tauxFilles").value(100.0))
                .andExpect(jsonPath("$.synthese.examens[0].tauxGarcons").value(0.0))
                .andExpect(jsonPath("$.directions[?(@.id == '" + dp1 + "')].indicateurs.eleves.total").value(3))
                .andExpect(jsonPath("$.directions[?(@.id == '" + dp2 + "')].indicateurs.eleves.total").value(2))
                .andExpect(jsonPath("$.etablissements[?(@.id == '" + lyceeB + "')].sousDirectionId").value(dp2.toString()))
                // Aucune donnée d'élève : ni nom ni matricule dans la réponse
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("OUEDRAOGO"));

        // Il descend vers une direction provinciale et peut remonter jusqu'à la sienne, pas au-delà
        mvc.perform(get("/api/v1/pilotage").param("direction", dp1.toString()).with(direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.id").value(dr.toString()))
                .andExpect(jsonPath("$.etablissements.length()").value(1))
                .andExpect(jsonPath("$.etablissements[0].indicateurs.examens[0].admis.total").value(2));
        mvc.perform(get("/api/v1/pilotage").param("direction", UUID.randomUUID().toString()).with(direction))
                .andExpect(status().isForbidden());
        RequestPostProcessor provinciale = jwt().jwt(x -> x.subject(compteId).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("DIRECTION")).claim("direction_id", dp2.toString()))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_DIRECTION"));
        mvc.perform(get("/api/v1/pilotage").param("direction", dr.toString()).with(provinciale))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pilotage").param("direction", dp1.toString()).with(provinciale))
                .andExpect(status().isForbidden());

        // Rien de l'administration de la plateforme ni des établissements
        mvc.perform(get("/api/v1/plateforme/etablissements").with(direction)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/plateforme/territoire/pays").with(direction)).andExpect(status().isForbidden());

        // Le super administrateur voit la même direction, avec le pays au-dessus
        mvc.perform(get("/api/v1/pilotage").param("direction", dr.toString()).with(SUPER_ADMIN))
                .andExpect(jsonPath("$.parent.type").value("PAYS"))
                .andExpect(jsonPath("$.synthese.eleves.total").value(5));
        mvc.perform(get("/api/v1/pilotage").param("direction", dr.toString()).with(autrePays))
                .andExpect(status().isForbidden());

        // Export Excel
        byte[] classeur = mvc.perform(get("/api/v1/pilotage/export").param("format", "xlsx").with(direction))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook x = new XSSFWorkbook(new ByteArrayInputStream(classeur))) {
            assertThat(x.getSheet("Établissements")).isNotNull();
            assertThat(x.getSheet("Examens")).isNotNull();
            assertThat(x.getSheet("Directions")).isNotNull();
        }
        mvc.perform(get("/api/v1/pilotage/export").param("format", "pdf").with(direction)).andExpect(status().isOk());

        // Retiré : il ne peut plus se connecter
        mvc.perform(delete("/api/v1/plateforme/territoire/directions/{id}/comptes/{u}", dr, compteId).with(SUPER_ADMIN))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> auth.connexion(tel, motDePasse)).isInstanceOf(AuthentificationException.class);
    }

    // ------------------------------------------------------------------

    private UUID lycee(String code, String nom, UUID direction) {
        ecole = etablissements.creer(code, nom, telephone(), "ADMIN", "Lycée", direction).etablissement().id();
        return ecole;
    }

    /** Année (même libellé dans chaque lycée), filière générale et une classe. */
    private UUID structure(String libelle, String code, String niveau) {
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        UUID annee = dans(() -> annees.creer(libelle, j.minusDays(30), j.plusDays(300))).id();
        UUID filiere = dans(() -> filieres.creer("G" + hasard(), "Enseignement général", "Premier cycle", "BEPC",
                general)).id();
        return dans(() -> classes.creer(annee, filiere, code, niveau, null)).id();
    }

    private UUID inscrire(String nom, String prenoms, Sexe sexe, UUID classe) {
        UUID eleve = dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, sexe, LocalDate.of(2011, 1, 1), null,
                null, null, null), List.of())).eleve().id();
        return dans(() -> inscriptions.inscrire(eleve, classe, false, StatutBourse.NON_BOURSIER)).id();
    }

    private void decision(UUID classe, UUID inscription, String decision, String examen) {
        jdbc.update("""
                insert into decision_fin_annee (tenant_id, id, inscription_id, classe_id, resultat_examen, proposition,
                    decision, valide_le) values (?, ?, ?, ?, ?, ?, ?, now())""",
                ecole, UUID.randomUUID(), inscription, classe, examen, decision, decision);
    }

    private <T> T dansTransaction(Supplier<T> action) {
        return dans(() -> new TransactionTemplate(transactions).execute(t -> action.get()));
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String hasard() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }
}
