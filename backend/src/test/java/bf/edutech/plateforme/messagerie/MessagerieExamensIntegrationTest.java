package bf.edutech.plateforme.messagerie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
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
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.passage.DecisionsService;
import bf.edutech.plateforme.passage.DecisionsService.SaisieDecision;
import bf.edutech.plateforme.passage.ParametresPassageService;
import bf.edutech.plateforme.passage.ResultatExamen;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * v0.46 : messagerie de l'établissement (groupes, parents avec ou sans compte, SMS, lu / non lu) et taux de
 * réussite aux examens nationaux (calculés et saisis), sur une vraie base PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
class MessagerieExamensIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EspaceParentService espaceParent;
    @Autowired private MembresService membres;
    @Autowired private EvaluationsService evaluations;
    @Autowired private ParametresPassageService parametres;
    @Autowired private DecisionsService decisions;
    @Autowired private NotificationsService notifications;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private final int an = j.getYear();
    private UUID ecole;

    @Test
    void messagerieEtExamens() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("msg-" + suffixe, "CEG " + suffixe, "7" + telephone().substring(1), "ADMIN", "Test")
                .etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F" + ThreadLocalRandom.current().nextInt(1000, 9999), "Général",
                "Premier cycle", "BEPC", general)).id();
        AnneeVue annee = dans(() -> annees.creer((an - 1) + "-" + an, j.minusDays(300), j.plusDays(60)));
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "3e A", "3e", null)).id();
        UUID matiere = dans(() -> matieres.creer("M" + ThreadLocalRandom.current().nextInt(1000, 9999), "Maths",
                TypeMatiere.GENERALE)).id();
        dans(() -> classes.definirMatiere(classe, matiere, BigDecimal.ONE, null, BigDecimal.ONE, null));
        LocalDate d0 = annee.debut();
        List<PeriodeVue> trimestres = new ArrayList<>();
        trimestres.add(dans(() -> periodes.creer(annee.id(), general, "Trimestre 1", d0, d0.plusDays(99))));
        trimestres.add(dans(() -> periodes.creer(annee.id(), general, "Trimestre 2", d0.plusDays(100), d0.plusDays(199))));
        trimestres.add(dans(() -> periodes.creer(annee.id(), general, "Trimestre 3", d0.plusDays(200), annee.fin())));
        dans(() -> annees.ouvrir(annee.id()));

        // Deux familles : la mère d'Awa a un espace parent, le père d'Ali non
        String telMere = telephone();
        DossierEleveVue awa = eleve("OUEDRAOGO", "Awa", Sexe.F, "OUEDRAOGO", "Mariam", telMere);
        DossierEleveVue ali = eleve("SAWADOGO", "Ali", Sexe.M, "SAWADOGO", "Issa", telephone());
        UUID insAwa = dans(() -> inscriptions.inscrire(awa.eleve().id(), classe, false, null)).id();
        UUID insAli = dans(() -> inscriptions.inscrire(ali.eleve().id(), classe, false, null)).id();
        UUID mere = commeAdmin(() -> espaceParent.ouvrir(awa.responsables().get(0).responsableId())).utilisateurId();
        UUID chef = membre(Role.ADMIN_ECOLE);
        UUID prof = membre(Role.ENSEIGNANT);
        UUID surveillant = membre(Role.SURVEILLANT);

        // ---------------- Messagerie
        String parents = "{\"objet\":\"Réunion des parents\",\"corps\":\"Samedi à 9 h, salle des fêtes.\",\"sms\":%s,"
                + "\"groupes\":[\"PARENTS\",\"ENSEIGNANTS\"]}";
        mvc.perform(post("/api/v1/messages/apercu").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content(parents.formatted(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comptes").value(2))          // la mère et l'enseignant
                .andExpect(jsonPath("$.injoignables").value(1))     // le père, sans compte ni SMS
                .andExpect(jsonPath("$.sms").value(0));
        mvc.perform(post("/api/v1/messages/apercu").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content(parents.formatted(true)))
                .andExpect(jsonPath("$.destinataires").value(3))
                .andExpect(jsonPath("$.smsSeulement").value(1))
                .andExpect(jsonPath("$.sms").value(3));
        mvc.perform(post("/api/v1/messages").with(jeton(surveillant, "SURVEILLANT")).contentType(MediaType.APPLICATION_JSON)
                .content(parents.formatted(true))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/messages").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"objet\":\" \",\"corps\":\"x\",\"groupes\":[\"PARENTS\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/messages").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objet\":\"Test\",\"corps\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCUN_DESTINATAIRE"));
        String envoi = mvc.perform(post("/api/v1/messages").with(jeton(chef, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON).content(parents.formatted(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinataires").value(3))
                .andExpect(jsonPath("$.sms").value(3))
                .andReturn().getResponse().getContentAsString();
        String messageId = JsonPath.read(envoi, "$.id");
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 20)))
                .filteredOn(n -> n.message().contains("Réunion des parents. Samedi à 9 h")).hasSize(3);

        // L'enseignant lit le message : non lu, puis lu
        mvc.perform(get("/api/v1/messages/non-lus").with(jeton(prof, "ENSEIGNANT"))).andExpect(jsonPath("$.nombre").value(1));
        mvc.perform(get("/api/v1/messages").with(jeton(prof, "ENSEIGNANT")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.messages[0].objet").value("Réunion des parents"))
                .andExpect(jsonPath("$.messages[0].lu").value(false));
        mvc.perform(get("/api/v1/messages/{id}", messageId).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corps").value("Samedi à 9 h, salle des fêtes."))
                .andExpect(jsonPath("$.luLe").isNotEmpty());
        mvc.perform(get("/api/v1/messages/non-lus").with(jeton(prof, "ENSEIGNANT"))).andExpect(jsonPath("$.nombre").value(0));
        // La mère le reçoit aussi dans son espace ; le surveillant, pas visé, ne le voit pas
        mvc.perform(get("/api/v1/messages").with(jeton(mere, "PARENT"))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/messages").with(jeton(surveillant, "SURVEILLANT"))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/messages/{id}", messageId).with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(status().isNotFound());
        // Le chef suit la lecture
        mvc.perform(get("/api/v1/messages/envoyes").with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(jsonPath("$[0].destinataires").value(3))
                .andExpect(jsonPath("$[0].comptes").value(2))
                .andExpect(jsonPath("$[0].lus").value(1))
                .andExpect(jsonPath("$[0].cibles").value("Tous les parents · Enseignants"));
        mvc.perform(get("/api/v1/messages/{id}/destinataires", messageId).with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.nom == 'SAWADOGO Issa')].compte").value(false));
        // Message à une personne choisie dans l'annuaire
        mvc.perform(get("/api/v1/messages/annuaire").with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(jsonPath("$[?(@.categorie == 'PARENT_SANS_COMPTE')].nom").value("SAWADOGO Issa"));
        mvc.perform(post("/api/v1/messages").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objet\":\"Surveillance\",\"corps\":\"Merci de passer au bureau.\",\"utilisateurs\":[\"%s\"]}"
                                .formatted(surveillant)))
                .andExpect(jsonPath("$.destinataires").value(1)).andExpect(jsonPath("$.sms").value(0));
        mvc.perform(get("/api/v1/messages/non-lus").with(jeton(surveillant, "SURVEILLANT"))).andExpect(jsonPath("$.nombre").value(1));
        mvc.perform(get("/api/v1/messages/non-lus").with(jeton(prof, "ENSEIGNANT"))).andExpect(jsonPath("$.nombre").value(0));

        // ---------------- Examens : BEPC de la classe de 3e, résultats saisis au conseil
        dans(() -> parametres.definirExamen(classe, "bepc"));
        UUID evaluation = commeAdmin(() -> evaluations.creer(classe, matiere, trimestres.get(2).id(), "Composition",
                TypeEvaluation.COMPOSITION, trimestres.get(2).debut().plusDays(1), null, null)).id();
        commeAdmin(() -> evaluations.saisir(evaluation, List.of(new SaisieNote(insAwa, new BigDecimal("13"), false),
                new SaisieNote(insAli, new BigDecimal("9"), false))));
        trimestres.forEach(t -> dans(() -> periodes.verrouiller(t.id())));
        commeAdmin(() -> decisions.proposer(classe));
        UUID censeur = membre(Role.CENSEUR);
        mvc.perform(get("/api/v1/examens/resultats").with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$.resultats[0].examen").value("BEPC"))
                .andExpect(jsonPath("$.resultats[0].inscrits").value(2))
                .andExpect(jsonPath("$.resultats[0].resultatsConnus").value(0))
                .andExpect(jsonPath("$.resultats[0].taux").doesNotExist());
        commeAdmin(() -> decisions.modifier(classe, List.of(new SaisieDecision(insAwa, null, null, ResultatExamen.ADMIS),
                new SaisieDecision(insAli, null, null, ResultatExamen.AJOURNE))));
        mvc.perform(get("/api/v1/examens/resultats").with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$.resultats[0].source").value("CALCULE"))
                .andExpect(jsonPath("$.resultats[0].resultatsConnus").value(2))
                .andExpect(jsonPath("$.resultats[0].admis").value(1))
                .andExpect(jsonPath("$.resultats[0].taux").value(50.0))
                .andExpect(jsonPath("$.resultats[0].tauxFilles").value(100.0))
                .andExpect(jsonPath("$.resultats[0].tauxGarcons").value(0.0))
                .andExpect(jsonPath("$.resultats[0].classes[0].classeCode").value("3e A"));
        mvc.perform(get("/api/v1/examens/resultats").with(jeton(prof, "ENSEIGNANT"))).andExpect(status().isForbidden());

        // Années antérieures saisies à la main
        String saisie = "{\"annee\":\"%s\",\"examen\":\"BEPC\",\"candidats\":%d,\"admis\":%d,\"candidatesFilles\":40,\"admisesFilles\":30}";
        String ancienne = (an - 3) + "-" + (an - 2);
        mvc.perform(post("/api/v1/examens/historique").with(jeton(censeur, "CENSEUR")).contentType(MediaType.APPLICATION_JSON)
                .content(saisie.formatted(ancienne, 100, 60))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/examens/historique").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                .content(saisie.formatted(ancienne, 50, 60))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/examens/historique").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content(saisie.formatted(annee.libelle(), 100, 60)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXAMEN_SUIVI_DANS_LA_PLATEFORME"));
        String apres = mvc.perform(post("/api/v1/examens/historique").with(jeton(chef, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON).content(saisie.formatted(ancienne, 100, 60)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annees.length()").value(2))
                .andExpect(jsonPath("$.resultats[1].source").value("SAISI"))
                .andExpect(jsonPath("$.resultats[1].taux").value(60.0))
                .andExpect(jsonPath("$.resultats[1].tauxFilles").value(75.0))
                .andExpect(jsonPath("$.resultats[1].tauxGarcons").value(50.0))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/examens/historique").with(jeton(chef, "ADMIN_ECOLE")).contentType(MediaType.APPLICATION_JSON)
                        .content(saisie.formatted(ancienne, 90, 50)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXAMEN_DEJA_SAISI"));
        mvc.perform(get("/api/v1/examens/resultats/export").param("format", "xlsx").with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk());
        String historiqueId = JsonPath.read(apres, "$.resultats[1].historiqueId");
        mvc.perform(delete("/api/v1/examens/historique/{id}", historiqueId).with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultats.length()").value(1));

        // Adresse inconnue : message en français (v0.46)
        mvc.perform(get("/api/v1/route-qui-n-existe-pas").with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROUTE_INCONNUE"));
    }

    // ------------------------------------------------------------------

    private DossierEleveVue eleve(String nom, String prenoms, Sexe sexe, String nomParent, String prenomsParent, String tel) {
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, sexe, LocalDate.of(2011, 5, 6), null, null,
                null, null), List.of(new DonneesResponsable(nomParent, prenomsParent, tel, LienParente.MERE, null, null,
                        true, true))));
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE" + role.name().charAt(0), "Ali", role));
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T commeAdmin(Supplier<T> action) {
        Authentication precedente = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN_ECOLE", "ROLE_CENSEUR"));
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
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private RequestPostProcessor jeton(UUID utilisateur, String role) {
        return jwt()
                .jwt(t -> t.subject(utilisateur.toString())
                        .claim("tenant_id", ecole.toString())
                        .claim("typ_jeton", "ACCES")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
