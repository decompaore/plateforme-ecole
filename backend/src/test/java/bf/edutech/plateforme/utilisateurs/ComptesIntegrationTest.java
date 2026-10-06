package bf.edutech.plateforme.utilisateurs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.notifications.EnvoiNotifications;
import bf.edutech.plateforme.notifications.PasserelleSmsJournal;

/**
 * Contrôle des comptes sur une vraie base PostgreSQL (profil "test") : l'administrateur
 * déverrouille un compte et réinitialise le mot de passe oublié d'un enseignant (sessions fermées,
 * ancien mot de passe refusé, mot de passe provisoire à changer, SMS d'information) ; le super
 * administrateur dépanne l'administrateur d'un établissement.
 */
@SpringBootTest
@ActiveProfiles("test")
class ComptesIntegrationTest {

    private static final String COOKIE = "plateforme_rt";
    private static final String TEL_SUPER_ADMIN = "69000000";
    private static final String MDP_SUPER_ADMIN_INITIAL = "MotDePasseTest-2026";
    private static final String MDP_DEFINITIF = "Definitif-2026";

    private static String jetonSuperAdmin;

    @Autowired private WebApplicationContext contexte;
    @Autowired private EnvoiNotifications envoi;
    @Autowired private PasserelleSmsJournal passerelle;

    private MockMvc mvc;

    private record Session(String jeton, Cookie cookie) {
    }

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    @Test
    void lAdministrateurDeverrouilleEtReinitialiseUnCompte() throws Exception {
        String telAdmin = telephone();
        MvcResult creation = creerEtablissement("comptes", telAdmin);
        String idEtablissement = lire(creation, "$.etablissement.id");
        String adminId = lire(creation, "$.administrateur.utilisateurId");
        Session admin = finaliserMotDePasse(connecterUnique(telAdmin, lire(creation, "$.motDePasseTemporaire")),
                lire(creation, "$.motDePasseTemporaire"));

        String telEnseignant = telephone();
        MvcResult ajout = ajouterMembre(admin, telEnseignant, "ENSEIGNANT");
        String enseignantId = lire(ajout, "$.membre.utilisateurId");
        finaliserMotDePasse(connecterUnique(telEnseignant, lire(ajout, "$.motDePasseTemporaire")),
                lire(ajout, "$.motDePasseTemporaire"));
        String telSecretaire = telephone();
        MvcResult ajoutSecretaire = ajouterMembre(admin, telSecretaire, "SECRETARIAT");
        Session secretaire = finaliserMotDePasse(connecterUnique(telSecretaire, lire(ajoutSecretaire, "$.motDePasseTemporaire")),
                lire(ajoutSecretaire, "$.motDePasseTemporaire"));

        // Cinq essais manqués : le compte est verrouillé, l'administrateur le voit et le déverrouille
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                    .content(identifiants(telEnseignant, "oubli-1234"))).andExpect(status().isUnauthorized());
        }
        String compte = "$[?(@.utilisateurId=='" + enseignantId + "')]";
        mvc.perform(get("/api/v1/comptes").header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(compte + ".verrouilleJusqua", hasItem(notNullValue())))
                .andExpect(jsonPath(compte + ".roles[0]", hasItem("ENSEIGNANT")))
                .andExpect(jsonPath(compte + ".motDePasseProvisoire", hasItem(false)))
                .andExpect(jsonPath("$[?(@.utilisateurId=='" + adminId + "')].moi", hasItem(true)));
        mvc.perform(post("/api/v1/comptes/{id}/deverrouillage", enseignantId).header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verrouilleJusqua", nullValue()));
        Session enseignant = connecterUnique(telEnseignant, MDP_DEFINITIF);

        // Mot de passe oublié : réinitialisation par l'administrateur
        mvc.perform(get("/api/v1/comptes").header("Authorization", "Bearer " + secretaire.jeton()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/comptes/{id}/reinitialisation", adminId).header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MON_PROPRE_COMPTE"));
        MvcResult reinit = mvc.perform(post("/api/v1/comptes/{id}/reinitialisation", enseignantId)
                        .header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telephone").value("+226" + telEnseignant))
                .andExpect(jsonPath("$.autresEtablissements").value(0))
                .andReturn();
        String provisoire = lire(reinit, "$.motDePasseTemporaire");
        assertThat(provisoire).hasSize(10);

        // Les sessions ouvertes sont fermées, l'ancien mot de passe ne marche plus
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(enseignant.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                .content(identifiants(telEnseignant, MDP_DEFINITIF))).andExpect(status().isUnauthorized());

        // Le mot de passe provisoire ouvre une session qui exige d'abord un nouveau mot de passe
        mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                        .content(identifiants(telEnseignant, provisoire)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doitChangerMotDePasse").value(true));
        Session apres = finaliserMotDePasse(connecterUnique(telEnseignant, provisoire), provisoire);
        mvc.perform(get("/api/v1/moi").header("Authorization", "Bearer " + apres.jeton()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doitChangerMotDePasse").value(false));

        // La personne est informée par SMS, sans le mot de passe
        // (le worker traite les écoles par lots ; la base de test peut contenir des messages d'autres tests)
        String numero = "+226" + telEnseignant;
        for (int passage = 0; passage < 200
                && passerelle.derniers().stream().noneMatch(sms -> sms.telephone().equals(numero)); passage++) {
            envoi.traiterLot();
        }
        assertThat(passerelle.derniers()).anySatisfy(sms -> {
            assertThat(sms.telephone()).isEqualTo("+226" + telEnseignant);
            assertThat(sms.message()).contains("réinitialisé").doesNotContain(provisoire);
        });

        // Un compte d'un autre établissement n'est pas accessible
        String telAutre = telephone();
        MvcResult autre = creerEtablissement("autre", telAutre);
        mvc.perform(post("/api/v1/comptes/{id}/reinitialisation", lire(autre, "$.administrateur.utilisateurId"))
                .header("Authorization", "Bearer " + admin.jeton())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/comptes").header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(jsonPath("$[*].telephone", not(hasItem("+226" + telAutre))));

        // L'administrateur n'accède pas aux routes du super administrateur
        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/administrateurs", idEtablissement)
                .header("Authorization", "Bearer " + admin.jeton())).andExpect(status().isForbidden());
    }

    @Test
    void leSuperAdministrateurReinitialiseLAdministrateurDUnEtablissement() throws Exception {
        String telAdmin = telephone();
        MvcResult creation = creerEtablissement("depannage", telAdmin);
        String id = lire(creation, "$.etablissement.id");
        String adminId = lire(creation, "$.administrateur.utilisateurId");
        Session admin = finaliserMotDePasse(connecterUnique(telAdmin, lire(creation, "$.motDePasseTemporaire")),
                lire(creation, "$.motDePasseTemporaire"));
        String enseignantId = lire(ajouterMembre(admin, telephone(), "ENSEIGNANT"), "$.membre.utilisateurId");

        mvc.perform(get("/api/v1/plateforme/etablissements/{id}/administrateurs", id)
                        .header("Authorization", "Bearer " + superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].telephone").value("+226" + telAdmin));
        // Seuls les administrateurs se dépannent depuis la plateforme
        mvc.perform(post("/api/v1/plateforme/etablissements/{id}/administrateurs/{u}/reinitialisation", id, enseignantId)
                .header("Authorization", "Bearer " + superAdmin())).andExpect(status().isNotFound());
        MvcResult reinit = mvc.perform(post("/api/v1/plateforme/etablissements/{id}/administrateurs/{u}/reinitialisation", id, adminId)
                        .header("Authorization", "Bearer " + superAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        String provisoire = lire(reinit, "$.motDePasseTemporaire");
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(admin.cookie())).andExpect(status().isUnauthorized());
        finaliserMotDePasse(connecterUnique(telAdmin, provisoire), provisoire);
    }

    @Test
    void unTelephonePerduEstDeconnecteADistanceEtEfface() throws Exception {
        String telAdmin = telephone();
        MvcResult creation = creerEtablissement("appareils", telAdmin);
        Session admin = finaliserMotDePasse(connecterUnique(telAdmin, lire(creation, "$.motDePasseTemporaire")),
                lire(creation, "$.motDePasseTemporaire"));
        String tel = telephone();
        MvcResult ajout = ajouterMembre(admin, tel, "ENSEIGNANT");
        String enseignantId = lire(ajout, "$.membre.utilisateurId");
        finaliserMotDePasse(connecterUnique(tel, lire(ajout, "$.motDePasseTemporaire")), lire(ajout, "$.motDePasseTemporaire"));
        Session telephone = connecter(tel, MDP_DEFINITIF,
                "Mozilla/5.0 (Linux; Android 13; TECNO) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36");
        Session ordinateur = connecter(tel, MDP_DEFINITIF,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36 Edg/129.0");

        // Mes appareils, vus depuis l'ordinateur : le téléphone, l'ordinateur (courant) et la première session
        MvcResult liste = mvc.perform(get("/api/v1/auth/appareils").header("Authorization", "Bearer " + ordinateur.jeton())
                        .cookie(ordinateur.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.appareil=='Ordinateur Windows · Edge')].courant", hasItem(true)))
                .andExpect(jsonPath("$[?(@.appareil=='Téléphone Android · Chrome')].courant", hasItem(false)))
                .andReturn();
        String idTelephone = JsonPath.<java.util.List<String>>read(liste.getResponse().getContentAsString(),
                "$[?(@.appareil=='Téléphone Android · Chrome')].id").get(0);

        // Un autre compte ne peut pas fermer cet appareil
        mvc.perform(post("/api/v1/auth/appareils/{id}/fermeture", idTelephone).header("Authorization", "Bearer " + admin.jeton())
                .contentType(MediaType.APPLICATION_JSON).content("{\"effacer\":true}")).andExpect(status().isNotFound());
        // Téléphone perdu : déconnecté et effacé au prochain contact
        mvc.perform(post("/api/v1/auth/appareils/{id}/fermeture", idTelephone).header("Authorization", "Bearer " + ordinateur.jeton())
                .contentType(MediaType.APPLICATION_JSON).content("{\"effacer\":true}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(telephone.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("APPAREIL_A_EFFACER"));
        MvcResult suite = mvc.perform(post("/api/v1/auth/rafraichir").cookie(ordinateur.cookie()))
                .andExpect(status().isOk()).andReturn();

        // L'administrateur voit les appareils connectés et les ferme tous (téléphone perdu signalé à l'école)
        mvc.perform(get("/api/v1/comptes").header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(jsonPath("$[?(@.utilisateurId=='" + enseignantId + "')].appareilsConnectes", hasItem(2)));
        mvc.perform(post("/api/v1/comptes/{id}/appareils/fermeture", lire(creation, "$.administrateur.utilisateurId"))
                        .header("Authorization", "Bearer " + admin.jeton()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effacer\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MON_PROPRE_COMPTE"));
        mvc.perform(post("/api/v1/comptes/{id}/appareils/fermeture", enseignantId)
                        .header("Authorization", "Bearer " + admin.jeton()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effacer\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appareils").value(2));
        mvc.perform(post("/api/v1/auth/rafraichir").cookie(suite.getResponse().getCookie(COOKIE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(get("/api/v1/comptes").header("Authorization", "Bearer " + admin.jeton()))
                .andExpect(jsonPath("$[?(@.utilisateurId=='" + enseignantId + "')].appareilsConnectes", hasItem(0)));
    }

    private Session connecter(String tel, String mdp, String navigateur) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/connexion").header("User-Agent", navigateur)
                        .contentType(MediaType.APPLICATION_JSON).content(identifiants(tel, mdp)))
                .andExpect(status().isOk()).andReturn();
        return new Session(lire(r, "$.jetonAcces"), r.getResponse().getCookie(COOKIE));
    }

    // ------------------------------------------------------------------ outils

    private String superAdmin() throws Exception {
        if (jetonSuperAdmin != null) {
            return jetonSuperAdmin;
        }
        MvcResult resultat = mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                .content(identifiants(TEL_SUPER_ADMIN, MDP_SUPER_ADMIN_INITIAL))).andReturn();
        String mdpActuel = MDP_SUPER_ADMIN_INITIAL;
        if (resultat.getResponse().getStatus() != 200) {
            mdpActuel = MDP_DEFINITIF;
            resultat = mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                    .content(identifiants(TEL_SUPER_ADMIN, mdpActuel))).andExpect(status().isOk()).andReturn();
        }
        Session session = new Session(lire(resultat, "$.jetonAcces"), resultat.getResponse().getCookie(COOKIE));
        if (Boolean.TRUE.equals(JsonPath.read(resultat.getResponse().getContentAsString(), "$.doitChangerMotDePasse"))) {
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

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private static String suffixe() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
