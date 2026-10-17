package bf.edutech.plateforme.professeurprincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
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
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.EvaluationsService;
import bf.edutech.plateforme.evaluations.TypeEvaluation;
import bf.edutech.plateforme.evaluations.Vues.EvaluationVue;
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Professeur principal (v0.49.1). Une 3e avec deux matières : KABORE (mathématiques) est désigné professeur principal
 * par le censeur (pas un enseignant hors de la classe). SAWADOGO (français) lui confie une revendication qu'il porte à
 * l'administration ; le proviseur répond, le professeur principal clôt. Un élève se plaint de SAWADOGO : médiation
 * ouverte par le seul professeur principal, cachée à l'enseignant tant qu'il n'est pas informé et à la direction tant
 * qu'elle n'est pas saisie. Enfin, vérification des notes du trimestre avec les élèves : une réclamation corrigée (la
 * note doit avoir changé), une maintenue (justification exigée), attestation impossible avant les réponses ; le conseil
 * de classe attend l'attestation ; plus de réclamation une fois la période verrouillée.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProfesseurPrincipalIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EvaluationsService evaluations;
    @Autowired private ProfesseursPrincipauxService professeursPrincipaux;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private AnneeVue annee;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("ppr-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
    }

    @Test
    void leProfesseurPrincipalPorteLesDossiersEtVerifieLesNotes() throws Exception {
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("G" + ThreadLocalRandom.current().nextInt(1000, 9999), "Général",
                "Premier cycle", "BEPC", general)).id();
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "3e A", "3e", null)).id();
        UUID maths = matiere("MATH", classe);
        UUID francais = matiere("FR", classe);
        List<PeriodeVue> trimestres = dans(() -> periodes.generer(annee.id(), general));
        dans(() -> annees.ouvrir(annee.id()));
        UUID trimestre = trimestres.stream().filter(t -> !aujourdhui.isBefore(t.debut()) && !aujourdhui.isAfter(t.fin()))
                .findFirst().orElseThrow().id();
        UUID kabore = enseignant("Kabore", "Paul", classe, maths);
        UUID sawadogo = enseignant("Sawadogo", "Rita", classe, francais);
        UUID horsClasse = enseignant("Zongo", "Ali", null, null);
        UUID censeur = membre(Role.CENSEUR, "Ilboudo", "Marc");
        UUID proviseur = membre(Role.ADMIN_ECOLE, "Traore", "Moussa");
        UUID cpe = membre(Role.SURVEILLANT, "Some", "Clarisse");
        UUID awa = inscrire(classe, "OUEDRAOGO", "Awa");
        UUID ali = inscrire(classe, "NIKIEMA", "Ali");
        EvaluationVue devoir = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir 1",
                TypeEvaluation.DEVOIR, aujourdhui, null, null));
        commeCenseur(() -> evaluations.saisir(devoir.id(), List.of(note(awa, "8"), note(ali, "12"))));

        // ------------------------------------------------------------ désignation
        String designation = "/api/v1/classes/{c}/professeur-principal";
        envoyer(put(designation, classe), jeton(kabore, "ENSEIGNANT"), "{\"utilisateurId\":\"" + kabore + "\"}")
                .andExpect(status().isForbidden());
        envoyer(put(designation, classe), jeton(censeur, "CENSEUR"), "{\"utilisateurId\":\"" + horsClasse + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENSEIGNANT_HORS_CLASSE"));
        envoyer(put(designation, classe), jeton(censeur, "CENSEUR"), "{\"utilisateurId\":\"" + kabore + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.professeurId").value(kabore.toString()))
                .andExpect(jsonPath("$.enseignants.length()").value(2));
        mvc.perform(get("/api/v1/messages").with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("Professeur principal de la 3e A"));
        mvc.perform(get("/api/v1/espace-enseignant/classes-principales").param("anneeId", annee.id().toString())
                .with(jeton(kabore, "ENSEIGNANT"))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/espace-enseignant/classes-principales").param("anneeId", annee.id().toString())
                .with(jeton(sawadogo, "ENSEIGNANT"))).andExpect(jsonPath("$.length()").value(0));

        // ------------------------------------------------------------ revendication des enseignants
        String revendication = envoyer(post("/api/v1/classes/{c}/saisines", classe), jeton(sawadogo, "ENSEIGNANT"), """
                {"nature":"REVENDICATION","objet":"Salle sans tableau","expose":"La salle de la 3e A n'a plus de tableau utilisable depuis deux semaines."}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.statut").value("OUVERTE"))
                .andExpect(jsonPath("$.droits.transmettre").value(false))
                .andReturn().getResponse().getContentAsString();
        String rev = JsonPath.read(revendication, "$.id");
        assertThat((String) JsonPath.read(revendication, "$.numero")).startsWith("PP-");
        mvc.perform(get("/api/v1/messages").with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("vous confie une revendication"));
        // La direction ne voit pas un dossier que le professeur principal ne lui a pas transmis
        mvc.perform(get("/api/v1/saisines/{id}", rev).with(jeton(proviseur, "ADMIN_ECOLE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/saisines").param("anneeId", annee.id().toString()).with(jeton(proviseur, "ADMIN_ECOLE")))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).as("Dossiers vus par le proviseur")
                        .isEqualTo("[]"));
        envoyer(post("/api/v1/saisines/{id}/transmission", rev), jeton(sawadogo, "ENSEIGNANT"), "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRANSMISSION_IMPOSSIBLE"));
        envoyer(post("/api/v1/saisines/{id}/transmission", rev), jeton(kabore, "ENSEIGNANT"),
                "{\"texte\":\"Les enseignants de la classe demandent un tableau neuf avant les compositions.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("TRANSMISE"))
                .andExpect(jsonPath("$.journal.length()").value(1));
        mvc.perform(get("/api/v1/messages").with(jeton(proviseur, "ADMIN_ECOLE")))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("Revendication des enseignants"));
        envoyer(post("/api/v1/saisines/{id}/suites", rev), jeton(proviseur, "ADMIN_ECOLE"),
                "{\"texte\":\"Un tableau sera posé lundi.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.journal[1].qualite").value("Chef d'établissement"));
        envoyer(post("/api/v1/saisines/{id}/cloture", rev), jeton(kabore, "ENSEIGNANT"), "{}")
                .andExpect(status().isBadRequest());
        envoyer(post("/api/v1/saisines/{id}/cloture", rev), jeton(kabore, "ENSEIGNANT"),
                "{\"texte\":\"Tableau posé ; les enseignants sont satisfaits.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("CLOSE"));
        envoyer(post("/api/v1/saisines/{id}/suites", rev), jeton(sawadogo, "ENSEIGNANT"), "{\"texte\":\"Merci\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAISINE_CLOSE"));

        // ------------------------------------------------------------ médiation élèves - enseignant
        String corpsMediation = """
                {"nature":"MEDIATION","objet":"Propos blessants en classe","expose":"Ali dit avoir été humilié devant la classe.",
                 "inscriptions":["%s"],"matiereId":"%s"}""".formatted(ali, francais);
        envoyer(post("/api/v1/classes/{c}/saisines", classe), jeton(sawadogo, "ENSEIGNANT"), corpsMediation)
                .andExpect(status().isForbidden());
        String mediation = envoyer(post("/api/v1/classes/{c}/saisines", classe), jeton(kabore, "ENSEIGNANT"),
                corpsMediation).andExpect(status().isCreated())
                .andExpect(jsonPath("$.enseignantId").value(sawadogo.toString()))
                .andExpect(jsonPath("$.eleves[0].inscriptionId").value(ali.toString()))
                .andExpect(jsonPath("$.droits.informer").value(true))
                .andReturn().getResponse().getContentAsString();
        String med = JsonPath.read(mediation, "$.id");
        mvc.perform(get("/api/v1/saisines/{id}", med).with(jeton(sawadogo, "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/saisines/{id}", med).with(jeton(cpe, "SURVEILLANT"))).andExpect(status().isForbidden());
        envoyer(post("/api/v1/saisines/{id}/information-enseignant", med), jeton(kabore, "ENSEIGNANT"), "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.enseignantInforme").value(true));
        envoyer(post("/api/v1/saisines/{id}/suites", med), jeton(sawadogo, "ENSEIGNANT"),
                "{\"texte\":\"Je n'ai pas voulu l'humilier ; je recevrai Ali avec le professeur principal.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.journal[0].qualite").value("Enseignant"));
        envoyer(post("/api/v1/saisines/{id}/transmission", med), jeton(kabore, "ENSEIGNANT"), "{}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/saisines/{id}", med).with(jeton(cpe, "SURVEILLANT"))).andExpect(status().isOk());
        envoyer(post("/api/v1/saisines/{id}/cloture", med), jeton(proviseur, "ADMIN_ECOLE"),
                "{\"texte\":\"Entretien tenu ; excuses présentées, incident clos.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("CLOSE"));

        // ------------------------------------------------------------ vérification des notes
        String verification = "/api/v1/classes/{c}/verification-notes";
        mvc.perform(get(verification, classe).param("periodeId", trimestre.toString()).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("A_FAIRE"))
                .andExpect(jsonPath("$.professeurPrincipal").value(true));
        String reclamationMaths = """
                {"periodeId":"%s","inscriptionId":"%s","matiereId":"%s","evaluationId":"%s",
                 "motif":"Awa a 12 sur sa copie, 8 a été saisi"}""".formatted(trimestre, awa, maths, devoir.id());
        envoyer(post(verification + "/reclamations", classe), jeton(sawadogo, "ENSEIGNANT"), reclamationMaths)
                .andExpect(status().isForbidden());
        String v = envoyer(post(verification + "/reclamations", classe), jeton(kabore, "ENSEIGNANT"), reclamationMaths)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.statut").value("EN_COURS"))
                .andExpect(jsonPath("$.reclamations[0].noteContestee").value(8.0))
                .andExpect(jsonPath("$.peutValider").value(false))
                .andReturn().getResponse().getContentAsString();
        String r1 = JsonPath.read(v, "$.reclamations[0].id");
        String v2 = envoyer(post(verification + "/reclamations", classe), jeton(kabore, "ENSEIGNANT"), """
                {"periodeId":"%s","inscriptionId":"%s","matiereId":"%s","motif":"Ali conteste sa moyenne de français"}"""
                .formatted(trimestre, ali, francais)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reclamations.length()").value(2)).andReturn().getResponse().getContentAsString();
        String r2 = JsonPath.<List<String>>read(v2, "$.reclamations[?(@.matiere == 'FR')].id").get(0);
        mvc.perform(get("/api/v1/espace-enseignant/reclamations-notes").with(jeton(sawadogo, "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].peutRepondre").value(true));
        envoyer(post(verification + "/validation", classe), jeton(kabore, "ENSEIGNANT"),
                "{\"periodeId\":\"" + trimestre + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECLAMATIONS_EN_ATTENTE"));

        // Le conseil de classe attend la vérification
        assertThatThrownBy(() -> dans(() -> {
            professeursPrincipaux.exigerVerificationValidee(classe, trimestre);
            return null;
        })).isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("VERIFICATION_NOTES_REQUISE");
        envoyer(put("/api/v1/classes/{c}/periodes/{p}/conseil", classe, trimestre), jeton(censeur, "CENSEUR"),
                "{\"avis\":[]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERIFICATION_NOTES_REQUISE"));

        // Réponses : SAWADOGO ne répond pas pour les mathématiques ; la note doit avoir été corrigée
        envoyer(post("/api/v1/reclamations-notes/{id}/reponse", r1), jeton(sawadogo, "ENSEIGNANT"),
                "{\"decision\":\"CORRIGEE\"}").andExpect(status().isForbidden());
        envoyer(post("/api/v1/reclamations-notes/{id}/reponse", r1), jeton(kabore, "ENSEIGNANT"),
                "{\"decision\":\"CORRIGEE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTE_INCHANGEE"));
        commeCenseur(() -> evaluations.saisir(devoir.id(), List.of(note(awa, "12"))));
        envoyer(post("/api/v1/reclamations-notes/{id}/reponse", r1), jeton(kabore, "ENSEIGNANT"),
                "{\"decision\":\"CORRIGEE\",\"reponse\":\"Erreur de saisie, corrigée.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("CORRIGEE"))
                .andExpect(jsonPath("$.noteCorrigee").value(12.0));
        envoyer(post("/api/v1/reclamations-notes/{id}/reponse", r2), jeton(sawadogo, "ENSEIGNANT"),
                "{\"decision\":\"MAINTENUE\"}").andExpect(status().isBadRequest());
        envoyer(post("/api/v1/reclamations-notes/{id}/reponse", r2), jeton(sawadogo, "ENSEIGNANT"),
                "{\"decision\":\"MAINTENUE\",\"reponse\":\"Toutes les notes ont été revues avec Ali : moyenne exacte.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("MAINTENUE"));
        envoyer(post("/api/v1/reclamations-notes/{id}/reponse", r2), jeton(sawadogo, "ENSEIGNANT"),
                "{\"decision\":\"CORRIGEE\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECLAMATION_TRAITEE"));

        // Attestation, puis le conseil de classe peut délibérer
        envoyer(post(verification + "/validation", classe), jeton(sawadogo, "ENSEIGNANT"),
                "{\"periodeId\":\"" + trimestre + "\"}").andExpect(status().isForbidden());
        envoyer(post(verification + "/validation", classe), jeton(kabore, "ENSEIGNANT"),
                "{\"periodeId\":\"" + trimestre + "\",\"observations\":\"Notes revues avec la classe le jour même.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("VALIDEE"))
                .andExpect(jsonPath("$.valideeLe").exists());
        mvc.perform(get("/api/v1/messages").with(jeton(censeur, "CENSEUR")))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("Notes vérifiées"));
        mvc.perform(get("/api/v1/annees/{a}/professeurs-principaux", annee.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$[0].verifications[0].statut").value("VALIDEE"))
                .andExpect(jsonPath("$[0].saisinesOuvertes").value(0));
        envoyer(put("/api/v1/classes/{c}/periodes/{p}/conseil", classe, trimestre), jeton(censeur, "CENSEUR"),
                "{\"avis\":[]}").andExpect(status().isOk());

        // Période verrouillée : plus de réclamation possible
        dans(() -> periodes.verrouiller(trimestre));
        envoyer(post(verification + "/reclamations", classe), jeton(kabore, "ENSEIGNANT"), reclamationMaths)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIODE_VERROUILLEE"));
        mvc.perform(post("/api/v1/classes/{c}/periodes/{p}/bulletins", classe, trimestre).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------

    private ResultActions envoyer(MockHttpServletRequestBuilder requete, RequestPostProcessor qui, String corps)
            throws Exception {
        return mvc.perform(requete.with(qui).contentType(MediaType.APPLICATION_JSON).content(corps));
    }

    private UUID matiere(String code, UUID classe) {
        UUID id = dans(() -> matieres.creer(code, code, TypeMatiere.GENERALE)).id();
        dans(() -> classes.definirMatiere(classe, id, BigDecimal.TWO, "Enseignement général", new BigDecimal("4"),
                null));
        return id;
    }

    private UUID enseignant(String nom, String prenoms, UUID classe, UUID matiere) {
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, nom, prenoms, Sexe.M, null,
                TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        if (classe != null) {
            dans(() -> enseignants.affecter(classe, matiere, engagement));
        }
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

    private UUID inscrire(UUID classe, String nom, String prenoms) {
        UUID eleve = dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.F, LocalDate.of(2011, 3, 4),
                null, null, null, null), List.of())).eleve().id();
        return dans(() -> inscriptions.inscrire(eleve, classe, false, null)).id();
    }

    private static SaisieNote note(UUID inscription, String valeur) {
        return new SaisieNote(inscription, new BigDecimal(valeur), false);
    }

    private <T> T commeCenseur(Supplier<T> action) {
        Authentication precedente = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("censeur", null, "ROLE_CENSEUR"));
        try {
            return dans(action);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(precedente);
        }
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
