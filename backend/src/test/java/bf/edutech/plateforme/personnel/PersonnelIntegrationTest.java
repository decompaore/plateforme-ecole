package bf.edutech.plateforme.personnel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.enseignants.EnseignantsService.DonneesEngagement;
import bf.edutech.plateforme.enseignants.TypeEngagement;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Dossier administratif du personnel (v0.51) : reprise des comptes, agent de soutien sans compte, ancienneté,
 * diplômes, pièces (cloisonnées), actes préparés par le secrétariat et signés par le chef d'établissement,
 * demande d'absence par l'agent et autorisation signée, signalement d'erreur, droits de chacun.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonnelIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private UUID chef;
    private UUID secretaire;
    private UUID censeur;
    private UUID kabore;
    private UUID autreEnseignant;
    private String matricule;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("per-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        chef = membre(Role.ADMIN_ECOLE, "SAWADOGO", "Issouf");
        secretaire = membre(Role.SECRETARIAT, "ZONGO", "Ines");
        censeur = membre(Role.CENSEUR, "TRAORE", "Moussa");
        matricule = "M" + ThreadLocalRandom.current().nextInt(100_000, 999_999);
        kabore = enseignant("KABORE", "Awa", Sexe.F, matricule);
        autreEnseignant = enseignant("SANOU", "Paul", Sexe.M, null);
    }

    @Test
    void dossiersActesAbsencesEtDroits() throws Exception {
        // Reprise : un dossier par enseignant engagé et par compte du personnel ; une seconde reprise n'ajoute rien
        mvc.perform(post("/api/v1/personnel/reprise").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.crees").value(6));
        mvc.perform(post("/api/v1/personnel/reprise").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$.crees").value(0));
        MvcResult liste = mvc.perform(get("/api/v1/personnel").with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comptesSansDossier").value(0))
                .andExpect(jsonPath("$.agents[?(@.nom == 'KABORE')].matricule").value(matricule))
                .andExpect(jsonPath("$.agents[?(@.nom == 'KABORE')].categorie").value("ENSEIGNANT"))
                .andExpect(jsonPath("$.agents[?(@.nom == 'SAWADOGO')].fonction").value("Chef d'établissement"))
                .andReturn();
        String dossierKabore = ((List<String>) JsonPath.read(liste.getResponse().getContentAsString(),
                "$.agents[?(@.nom == 'KABORE')].id")).get(0);

        // Agent de soutien sans compte : le gardien
        String gardien = JsonPath.read(mvc.perform(post("/api/v1/personnel").with(jeton(secretaire, "SECRETARIAT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"categorie":"SOUTIEN","nom":"ouedraogo","prenoms":"Issa","sexe":"M","situation":"CONTRACTUEL",
                         "fonction":"Gardien","datePriseService":"%s"}""".formatted(aujourdhui.minusYears(3).minusMonths(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("OUEDRAOGO"))
                .andExpect(jsonPath("$.anciennete.etablissement.annees").value(3))
                .andExpect(jsonPath("$.anciennete.etablissement.mois").value(2))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // Situation administrative de Mme KABORE
        String donnees = """
                {"categorie":"ENSEIGNANT","nom":"KABORE","prenoms":"Awa","sexe":"F","dateNaissance":"1988-05-04",
                 "lieuNaissance":"Koudougou","matricule":"%s","situation":"FONCTIONNAIRE",
                 "emploi":"Professeur certifié des lycées et collèges","grade":"Professeur certifié",
                 "categorieEchelle":"A1","echelon":"3","fonction":"Professeur de mathématiques",
                 "datePremierePriseService":"2015-10-01","dateEntreeGrade":"2019-10-01","datePriseService":"2022-10-03",
                 "observations":"Note interne"}""".formatted(matricule);
        mvc.perform(put("/api/v1/personnel/{id}", dossierKabore).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(donnees))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anciennete.fonction.annees")
                        .value(java.time.Period.between(LocalDate.of(2015, 10, 1), aujourdhui).getYears()))
                .andExpect(jsonPath("$.manquants[0]").value("diplômes"));
        mvc.perform(put("/api/v1/personnel/{id}", dossierKabore).with(jeton(censeur, "CENSEUR"))
                        .contentType(MediaType.APPLICATION_JSON).content(donnees))
                .andExpect(status().isForbidden());
        // Un matricule déjà attribué est refusé
        mvc.perform(put("/api/v1/personnel/{id}", gardien).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"categorie":"SOUTIEN","nom":"OUEDRAOGO","prenoms":"Issa","sexe":"M","matricule":"%s"}"""
                                .formatted(matricule.toLowerCase())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MATRICULE_EXISTANT"));

        // Diplôme et pièce scannée
        MockMultipartFile scan = new MockMultipartFile("fichier", "capes.pdf", "application/pdf",
                "%PDF-1.4\n% diplôme scanné\n".getBytes(StandardCharsets.UTF_8));
        String reponsePiece = mvc.perform(multipart("/api/v1/personnel/{id}/pieces", dossierKabore).file(scan)
                        .param("nature", "DIPLOME").param("objet", "CAPES").param("reference", "N° 2015/0457")
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pieces.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String piece = JsonPath.read(reponsePiece, "$.pieces[0].id");
        mvc.perform(post("/api/v1/personnel/{id}/diplomes", dossierKabore).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nature":"PROFESSIONNEL","intitule":"CAPES","specialite":"Mathématiques",
                                 "delivrePar":"École normale supérieure","annee":2015,"pieceId":"%s"}""".formatted(piece)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.manquants.length()").value(0));
        mvc.perform(multipart("/api/v1/personnel/{id}/pieces", dossierKabore)
                        .file(new MockMultipartFile("fichier", "x.exe", "application/octet-stream", "MZ\u0090\u0000aaaa".getBytes()))
                        .param("nature", "AUTRE").param("objet", "Exécutable").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/personnel/pieces/{id}", piece).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/v1/personnel/pieces/{id}", piece).with(jeton(autreEnseignant, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/personnel/pieces/{id}", piece).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/personnel/{id}", dossierKabore).with(jeton(autreEnseignant, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/personnel").with(jeton(autreEnseignant, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        // Attestation préparée par le secrétariat (projet), signée par le chef d'établissement
        String acte = JsonPath.read(mvc.perform(post("/api/v1/personnel/{id}/actes", dossierKabore)
                        .with(jeton(secretaire, "SECRETARIAT")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ATTESTATION_TRAVAIL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").doesNotExist())
                .andExpect(jsonPath("$.signeLe").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/personnel/actes/{id}/pdf", acte).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/personnel/actes/{id}/pdf", acte).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/personnel/actes/{id}/signature", acte).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isForbidden());
        int an = aujourdhui.getYear();
        mvc.perform(post("/api/v1/personnel/actes/{id}/signature", acte).with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("AP-" + an + "-0001"))
                .andExpect(jsonPath("$.signataire").value("Issouf SAWADOGO"));
        mvc.perform(get("/api/v1/personnel/actes/{id}/pdf", acte).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        // Prise de service établie par le chef : signée aussitôt
        mvc.perform(post("/api/v1/personnel/{id}/actes", gardien).with(jeton(chef, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PRISE_SERVICE\",\"complement\":\"note de service n° 12/2023\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.numero").value("AP-" + an + "-0002"));
        mvc.perform(post("/api/v1/personnel/{id}/actes", gardien).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CESSATION_SERVICE\",\"dateEffet\":\"%s\"}"
                                .formatted(aujourdhui)))
                .andExpect(status().isBadRequest());

        // Mme KABORE demande une absence depuis « Mon dossier »
        LocalDate debut = aujourdhui.plusDays(10);
        String demande = """
                {"type":"AUTORISATION_ABSENCE","debut":"%s","fin":"%s","motif":"Baptême de mon enfant"}""";
        String absence = JsonPath.read(mvc.perform(post("/api/v1/mon-dossier/absences").with(jeton(kabore, "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(demande.formatted(debut, debut.plusDays(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("DEMANDEE"))
                .andExpect(jsonPath("$.jours").value(3))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/mon-dossier/absences").with(jeton(kabore, "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(demande.formatted(debut.plusDays(1), debut.plusDays(4))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ABSENCE_CHEVAUCHEMENT"));
        mvc.perform(get("/api/v1/personnel/absences").with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(jsonPath("$[0].id").value(absence)).andExpect(jsonPath("$[0].agent").value("KABORE Awa"));
        mvc.perform(post("/api/v1/personnel/absences/{id}/decision", absence).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accorder\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/personnel/absences/{id}/decision", absence).with(jeton(chef, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accorder\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("ACCORDEE"))
                .andExpect(jsonPath("$.acteId").isNotEmpty());

        // Son dossier : sans les observations internes, actes signés seulement ; elle signale une erreur
        mvc.perform(get("/api/v1/mon-dossier").with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observations").doesNotExist())
                .andExpect(jsonPath("$.actes.length()").value(2))
                .andExpect(jsonPath("$.actes[0].type").value("AUTORISATION_ABSENCE"))
                .andExpect(jsonPath("$.droits.modifier").value(false));
        String signalement = JsonPath.read(mvc.perform(post("/api/v1/mon-dossier/signalements")
                        .with(jeton(kabore, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texte\":\"Mon échelon est 4 depuis octobre.\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.signalements[0].id");
        mvc.perform(post("/api/v1/personnel/signalements/{id}/traitement", signalement).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"texte\":\"Corrigé au vu de l'arrêté.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signalements[0].traiteLe").isNotEmpty());
        mvc.perform(get("/api/v1/personnel/{id}", dossierKabore).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$.observations").value("Note interne"))
                .andExpect(jsonPath("$.droits.modifier").value(true))
                .andExpect(jsonPath("$.droits.signer").value(false));

        // Le chef annule l'absence accordée : l'autorisation est annulée aussi
        mvc.perform(post("/api/v1/personnel/absences/{id}/annulation", absence).with(jeton(chef, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"texte\":\"Baptême reporté\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("ANNULEE"));
        mvc.perform(get("/api/v1/mon-dossier").with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(jsonPath("$.actes[0].annuleLe").isNotEmpty());
        assertThat(an).isPositive();
    }

    private UUID membre(Role role, String nom, String prenoms) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, nom, prenoms, role));
        return compte(tel, role);
    }

    private UUID enseignant(String nom, String prenoms, Sexe sexe, String matriculeFp) {
        String tel = telephone();
        dans(() -> enseignants.engager(new DonneesEngagement(tel, matriculeFp, nom, prenoms, sexe, "Mathématiques",
                TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null, null)));
        return compte(tel, Role.ENSEIGNANT);
    }

    private UUID compte(String tel, Role role) {
        return dans(() -> membres.lister()).stream().filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "6" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private RequestPostProcessor jeton(UUID utilisateur, String role) {
        return jwt().jwt(j -> j.subject(utilisateur.toString()).claim("tenant_id", ecole.toString())
                        .claim("typ_jeton", "ACCES").claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
