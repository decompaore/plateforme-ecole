package bf.edutech.plateforme.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
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

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.enseignants.EnseignantsService.DonneesEngagement;
import bf.edutech.plateforme.enseignants.TypeEngagement;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.MatieresService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Interpellation des enseignants (v0.48.1). Mathématiques en 2nde : trois séquences de 8 h, dont deux échues, une
 * seule séance de 2 h au cahier de textes. Le suivi signale le retard ; le censeur reçoit un constat chiffré, envoie
 * une demande d'explication numérotée avec délai ; le chef des travaux n'interpelle pas sur une matière générale ;
 * l'enseignant la reçoit dans sa messagerie, l'ouvre (lue), y répond ; le censeur donne la suite et clôt ; la lettre
 * s'imprime.
 */
@SpringBootTest
@ActiveProfiles("test")
class InterpellationsIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private AnneeVue annee;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("itp-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(40), aujourdhui.plusDays(300)));
    }

    @Test
    void leCenseurInterpelleLEnseignantEnRetardQuiRepond() throws Exception {
        UUID technique = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("TECHNIQUE")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F3" + ThreadLocalRandom.current().nextInt(100, 999),
                "Électrotechnique", "Second cycle", "BAC F3", technique)).id();
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "2nde F3", "2nde", null)).id();
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe);
        dans(() -> periodes.generer(annee.id(), technique));
        dans(() -> annees.ouvrir(annee.id()));
        UUID kabore = enseignant("Kabore", "Awa", classe, maths);
        UUID censeur = membre(Role.CENSEUR, "Zongo", "Ines");
        UUID chef = membre(Role.CHEF_TRAVAUX, "Ouedraogo", "Salif");
        UUID proviseur = membre(Role.ADMIN_ECOLE, "Traore", "Moussa");

        // Fiche : trois séquences de 8 h ; la troisième a commencé il y a une semaine, les deux premières sont échues
        mvc.perform(put("/api/v1/classes/{c}/matieres/{m}/progression", classe, maths).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sequences":[
                          {"titre":"Algèbre","heuresPrevues":8,"semaineDebut":"%s"},
                          {"titre":"Géométrie","heuresPrevues":8,"semaineDebut":"%s"},
                          {"titre":"Statistiques","heuresPrevues":8,"semaineDebut":"%s"}]}"""
                        .formatted(aujourdhui.minusDays(35), aujourdhui.minusDays(21), aujourdhui.minusDays(7))))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/cahier-textes/{id}", UUID.randomUUID()).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"classeId":"%s","matiereId":"%s","date":"%s","heureDebut":"08:00","heureFin":"10:00",
                         "sequenceOrdre":1,"contenu":"Équations du premier degré"}""".formatted(classe, maths,
                        aujourdhui.minusDays(3)))).andExpect(status().isOk());

        // Le suivi signale le retard : 16 h prévues à ce jour, 2 h faites, « Géométrie » sans séance
        mvc.perform(get("/api/v1/annees/{a}/progressions", annee.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].retard.heuresPrevuesADate").value(16.0))
                .andExpect(jsonPath("$[0].retard.ecart").value(14.0))
                .andExpect(jsonPath("$[0].retard.sequencesEnRetard").value(1))
                .andExpect(jsonPath("$[0].retard.enRetard").value(true))
                .andExpect(jsonPath("$[0].interpellations").value(0));

        // Le chef des travaux ne suit pas les matières générales
        String base = "/api/v1/classes/{c}/matieres/{m}/interpellations";
        mvc.perform(get(base + "/proposition", classe, maths).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isForbidden());
        mvc.perform(get(base + "/proposition", classe, maths).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        String proposition = mvc.perform(get(base + "/proposition", classe, maths).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enseignant").value("KABORE Awa"))
                .andExpect(jsonPath("$.objet").value("Retard sur la progression de MATH en 2nde F3"))
                .andReturn().getResponse().getContentAsString();
        String constat = JsonPath.read(proposition, "$.constat");
        assertThat(constat).contains("16 h").contains("2 h").contains("retard de 14 h").contains("« Géométrie »");

        // Demande d'explication : un délai est exigé, puis l'envoi est numéroté
        mvc.perform(post(base, classe, maths).with(jeton(censeur, "CENSEUR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"DEMANDE_EXPLICATION\",\"objet\":\"Retard\",\"constat\":\"x\"}"))
                .andExpect(status().isBadRequest());
        String demande = mvc.perform(post(base, classe, maths).with(jeton(censeur, "CENSEUR"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"DEMANDE_EXPLICATION","objet":"Retard sur la progression de MATH en 2nde F3",
                         "constat":%s,"demande":"Merci de vous expliquer par écrit.","delaiReponse":"%s"}"""
                        .formatted(json(constat), aujourdhui.plusDays(7))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("DE-" + LocalDate.now(java.time.ZoneId.of("Africa/Ouagadougou")).getYear() + "-0001"))
                .andExpect(jsonPath("$.statut").value("ENVOYEE"))
                .andExpect(jsonPath("$.qualiteEmetteur").value("Le Censeur"))
                .andExpect(jsonPath("$.heuresPrevues").value(16.0))
                .andExpect(jsonPath("$.peutClore").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(demande, "$.id");

        // L'enseignant la reçoit dans sa messagerie ; le chef d'établissement en a copie
        mvc.perform(get("/api/v1/messages").with(jeton(kabore, "ENSEIGNANT"))).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Demande d'explication"));
        mvc.perform(get("/api/v1/messages").with(jeton(proviseur, "ADMIN_ECOLE")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Copie"));
        mvc.perform(get("/api/v1/annees/{a}/progressions", annee.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$[0].interpellations").value(1));

        // L'enseignant l'ouvre (lue), y répond une fois
        mvc.perform(get("/api/v1/espace-enseignant/interpellations").with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].peutRepondre").value(true));
        mvc.perform(get("/api/v1/interpellations/{id}", id).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(jsonPath("$.statut").value("LUE")).andExpect(jsonPath("$.peutClore").value(false));
        mvc.perform(post("/api/v1/interpellations/{id}/cloture", id).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"suite\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/interpellations/{id}/reponse", id).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reponse\":\"J'ai été malade deux semaines ; je rattrape le samedi matin.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("REPONDUE"));
        mvc.perform(post("/api/v1/interpellations/{id}/reponse", id).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reponse\":\"Encore\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEJA_REPONDUE"));
        mvc.perform(get("/api/v1/messages").with(jeton(censeur, "CENSEUR")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Réponse de KABORE Awa"));

        // Le chef des travaux ne voit pas cette interpellation ; le censeur la clôt avec la suite donnée
        mvc.perform(get("/api/v1/interpellations/{id}", id).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/interpellations").param("anneeId", annee.id().toString()).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/interpellations/{id}/cloture", id).with(jeton(censeur, "CENSEUR"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/interpellations/{id}/cloture", id).with(jeton(censeur, "CENSEUR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"suite\":\"Explication recevable ; plan de rattrapage suivi chaque semaine.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("CLOSE"));
        mvc.perform(get("/api/v1/interpellations").param("anneeId", annee.id().toString()).with(jeton(proviseur, "ADMIN_ECOLE")))
                .andExpect(jsonPath("$.length()").value(1));

        // La lettre s'imprime (direction et enseignant)
        byte[] pdf = mvc.perform(get("/api/v1/interpellations/{id}/lettre", id).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        // Un simple rappel n'a pas de numéro ni de délai
        mvc.perform(post(base, classe, maths).with(jeton(censeur, "CENSEUR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"RAPPEL\",\"objet\":\"Rattrapage\",\"constat\":\"Le retard persiste.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").doesNotExist())
                .andExpect(jsonPath("$.delaiReponse").doesNotExist());
    }

    // ------------------------------------------------------------------

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private UUID matiere(String code, TypeMatiere type, UUID classe) {
        UUID id = dans(() -> matieres.creer(code, code, type)).id();
        String groupe = type == TypeMatiere.GENERALE ? "Enseignement général" : "Enseignement technique";
        dans(() -> classes.definirMatiere(classe, id, BigDecimal.TWO, groupe, new BigDecimal("4"), null));
        return id;
    }

    private UUID enseignant(String nom, String prenoms, UUID classe, UUID matiere) {
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, nom, prenoms, Sexe.F, null,
                TypeEngagement.TITULAIRE, aujourdhui.minusDays(40), null, null))).enseignant().engagementId();
        dans(() -> enseignants.affecter(classe, matiere, engagement));
        return compte(tel, Role.ENSEIGNANT);
    }

    private UUID membre(Role role, String nom, String prenoms) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, nom, prenoms, role));
        return compte(tel, role);
    }

    private UUID compte(String tel, Role role) {
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel)).findFirst().orElseThrow()
                .utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "6" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private RequestPostProcessor jeton(UUID utilisateur, String role) {
        return jwt()
                .jwt(j -> j.subject(utilisateur.toString())
                        .claim("tenant_id", ecole.toString())
                        .claim("typ_jeton", "ACCES")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
