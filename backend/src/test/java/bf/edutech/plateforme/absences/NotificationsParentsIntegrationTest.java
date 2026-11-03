package bf.edutech.plateforme.absences;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
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
import bf.edutech.plateforme.notifications.EnvoiNotifications;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.NotificationVue;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.notifications.EnvoiPush;
import bf.edutech.plateforme.notifications.PasserellePush;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Notifications aux parents (v0.50) : dans l'application pour les parents qui l'ont activée sur leur
 * téléphone, SMS pour les autres et pour ceux que l'établissement a mis « toujours par SMS », SMS de
 * secours quand l'appareil n'est plus joignable, retards, délai de correction et centre de
 * notifications de l'espace parent.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NotificationsParentsIntegrationTest.ServicesPushDeTest.class)
class NotificationsParentsIntegrationTest {

    /** Service push simulé : enregistre les remises ; les adresses « expire » sont révoquées. */
    @TestConfiguration
    static class ServicesPushDeTest {
        static final List<String> REMIS = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        PasserellePush passerellePushDeTest() {
            return (appareil, message) -> {
                if (appareil.endpoint().contains("expire")) {
                    return PasserellePush.Resultat.EXPIRE;
                }
                REMIS.add(appareil.endpoint() + " " + new String(message, java.nio.charset.StandardCharsets.UTF_8));
                return PasserellePush.Resultat.REMIS;
            };
        }
    }

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
    @Autowired private EspaceParentService espaceParent;
    @Autowired private NotificationsService notifications;
    @Autowired private EnvoiPush envoiPush;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WebApplicationContext contexte;

    private MockMvc mvc;
    private UUID ecole;
    private UUID classe;
    private UUID enseignant;
    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private DossierEleveVue awa;
    private DossierEleveVue ali;
    private DossierEleveVue ines;
    private UUID inscriptionAwa;
    private UUID inscriptionAli;
    private UUID inscriptionInes;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        int an = aujourdhui.getYear();
        AnneeVue annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30),
                aujourdhui.plusDays(300)));
        UUID filiere = dans(() -> filieres.creer("GEN", "Général", "Premier cycle", "BEPC", general)).id();
        UUID maths = dans(() -> matieres.creer("MATH", "Mathématiques", TypeMatiere.GENERALE)).id();
        classe = dans(() -> classes.creer(annee.id(), filiere, "6e A", "6e", null)).id();
        dans(() -> classes.definirMatiere(classe, maths, new BigDecimal("4"), null, new BigDecimal("5"), null));
        dans(() -> periodes.generer(annee.id(), general));
        dans(() -> annees.ouvrir(annee.id()));
        String telEnseignant = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(telEnseignant, null, "Sanou", "Paul",
                Sexe.M, null, TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant()
                .engagementId();
        dans(() -> enseignants.affecter(classe, maths, engagement));
        enseignant = compte(telEnseignant, Role.ENSEIGNANT);

        awa = eleve("OUEDRAOGO", "Awa", Sexe.F, telephone());
        ali = eleve("SAWADOGO", "Ali", Sexe.M, telephone());
        ines = eleve("ZONGO", "Ines", Sexe.F, telephone());
        inscriptionAwa = dans(() -> inscriptions.inscrire(awa.eleve().id(), classe, false, null)).id();
        inscriptionAli = dans(() -> inscriptions.inscrire(ali.eleve().id(), classe, false, null)).id();
        inscriptionInes = dans(() -> inscriptions.inscrire(ines.eleve().id(), classe, false, null)).id();
    }

    @Test
    void applicationPourLesParentsEquipesSmsPourLesAutresEtCorrection() throws Exception {
        // Le parent d'Awa active les notifications sur son téléphone ; celui d'Ines n'a pas de smartphone
        UUID parentAwa = dans(() -> espaceParent.ouvrir(awa.responsables().get(0).responsableId())).utilisateurId();
        UUID parentInes = dans(() -> espaceParent.ouvrir(ines.responsables().get(0).responsableId())).utilisateurId();
        UUID secretariat = membre(Role.SECRETARIAT);
        mvc.perform(get("/api/v1/push/configuration").with(jeton(parentAwa, "PARENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disponible").value(true))
                .andExpect(jsonPath("$.clePublique").isNotEmpty());
        String endpoint = "https://push.exemple.bf/awa-" + UUID.randomUUID();
        abonner(parentAwa, endpoint).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(put("/api/v1/responsables/{id}/canal-notification", ines.responsables().get(0).responsableId())
                        .with(jeton(secretariat, "SECRETARIAT")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canal\":\"SMS\"}"))
                .andExpect(status().isNoContent());
        abonner(parentInes, "https://push.exemple.bf/ines-" + UUID.randomUUID()).andExpect(status().isOk());

        UUID idClient = UUID.randomUUID();
        synchroniser(lot(appel(idClient, aujourdhui, "08:00", "10:00", marque(inscriptionAwa, "ABSENCE", null),
                marque(inscriptionAli, "RETARD", 10), marque(inscriptionInes, "ABSENCE", null))), enseignant, "ENSEIGNANT");

        // SMS : Ines seulement (toujours par SMS). Awa est prévenue dans l'application ; le retard d'Ali
        // ne part pas par SMS (l'établissement ne l'a pas choisi) et son parent n'a pas de compte.
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10))).singleElement()
                .satisfies(n -> assertThat(n.message()).contains("Ines ZONGO"));

        for (int i = 0; i < 50 && ServicesPushDeTest.REMIS.stream().noneMatch(r -> r.startsWith(endpoint)); i++) {
            envoiPush.traiterLot();
        }
        assertThat(ServicesPushDeTest.REMIS).anySatisfy(r -> assertThat(r).startsWith(endpoint)
                .contains("\"title\":\"Absence de Awa\"").contains("Awa OUEDRAOGO (6e A) était absente"));

        // Centre de notifications : Awa (reçue sur le téléphone) ; le parent d'Ines la voit aussi dans l'application
        mvc.perform(get("/api/v1/espace-parent/notifications").with(jeton(parentAwa, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nonLues").value(1))
                .andExpect(jsonPath("$.notifications[0].type").value("ABSENCE"))
                .andExpect(jsonPath("$.notifications[0].lien").value("/parent/enfants/" + awa.eleve().id()));
        mvc.perform(get("/api/v1/espace-parent/notifications").with(jeton(parentInes, "PARENT")))
                .andExpect(jsonPath("$.nonLues").value(1));
        assertThat(statutPush(parentInes)).isEqualTo("NON_DEMANDE");
        mvc.perform(post("/api/v1/espace-parent/notifications/lues").with(jeton(parentAwa, "PARENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nonLues").value(0));

        // L'enseignant corrige : Awa était là. Sa notification est marquée corrigée.
        synchroniser(lot(appel(idClient, aujourdhui, "08:00", "10:00", marque(inscriptionAli, "RETARD", 10),
                marque(inscriptionInes, "ABSENCE", null))), enseignant, "ENSEIGNANT");
        mvc.perform(get("/api/v1/espace-parent/notifications").with(jeton(parentAwa, "PARENT")))
                .andExpect(jsonPath("$.notifications[0].corrigeeLe").isNotEmpty());

        // Le téléphone a été réinitialisé : l'abonnement est révoqué, le SMS de secours part
        mvc.perform(post("/api/v1/push/abonnements/retrait").with(jeton(parentAwa, "PARENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + endpoint + "\"}"))
                .andExpect(jsonPath("$.length()").value(0));
        abonner(parentAwa, "https://push.exemple.bf/expire-" + UUID.randomUUID()).andExpect(status().isOk());
        synchroniser(lot(appel(UUID.randomUUID(), aujourdhui, "10:00", "12:00", marque(inscriptionAwa, "ABSENCE", null))),
                enseignant, "ENSEIGNANT");
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10)))
                .noneSatisfy(n -> assertThat(n.message()).contains("Awa OUEDRAOGO"));
        for (int i = 0; i < 50 && !"SANS_ABONNEMENT".equals(statutPush(parentAwa)); i++) {
            envoiPush.traiterLot();
        }
        assertThat(statutPush(parentAwa)).isEqualTo("SANS_ABONNEMENT");
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10)))
                .anySatisfy(n -> assertThat(n.message()).contains("Awa OUEDRAOGO").contains("de 10h00 à 12h00"));
        mvc.perform(get("/api/v1/push/abonnements").with(jeton(parentAwa, "PARENT")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void retardsDelaiDeCorrectionEtPreferencesDuParent() throws Exception {
        UUID surveillant = membre(Role.SURVEILLANT);
        mvc.perform(put("/api/v1/notifications/reglages").with(jeton(surveillant, "SURVEILLANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delaiCorrectionMinutes\":10,\"smsRetards\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delaiCorrectionMinutes").value(10))
                .andExpect(jsonPath("$.pushDisponible").value(true));
        mvc.perform(put("/api/v1/notifications/reglages").with(jeton(surveillant, "SURVEILLANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delaiCorrectionMinutes\":90,\"smsRetards\":true}"))
                .andExpect(status().isConflict());

        // Le parent d'Awa ne veut pas être prévenu des retards
        UUID parentAwa = dans(() -> espaceParent.ouvrir(awa.responsables().get(0).responsableId())).utilisateurId();
        mvc.perform(put("/api/v1/espace-parent/preferences-notifications").with(jeton(parentAwa, "PARENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notifierRetards\":false,\"discret\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canal").value("AUTO"))
                .andExpect(jsonPath("$.notifierRetards").value(false))
                .andExpect(jsonPath("$.discret").value(true));

        UUID idClient = UUID.randomUUID();
        synchroniser(lot(appel(idClient, aujourdhui, "08:00", "10:00", marque(inscriptionAwa, "RETARD", 5),
                marque(inscriptionAli, "RETARD", 20), marque(inscriptionInes, "ABSENCE", null))), enseignant, "ENSEIGNANT");
        // Retard d'Ali par SMS (choisi par l'établissement), absence d'Ines ; rien pour le retard d'Awa.
        // Les deux SMS attendent le délai de correction.
        List<NotificationVue> enAttente = dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10));
        assertThat(enAttente).hasSize(2)
                .anySatisfy(n -> assertThat(n.message()).contains("Ali SAWADOGO (6e A) est arrivé en retard de 20 min"))
                .anySatisfy(n -> assertThat(n.message()).contains("Ines ZONGO"));
        Integer differes = dans(() -> new TransactionTemplate(transactions).execute(s -> jdbc.queryForObject(
                "select count(*) from notification where statut = 'EN_ATTENTE' and prochaine_tentative > now() + interval '9 minutes'",
                Integer.class)));
        assertThat(differes).isEqualTo(2);

        // Correction dans le délai : Ines était là, rien ne part pour elle
        synchroniser(lot(appel(idClient, aujourdhui, "08:00", "10:00", marque(inscriptionAwa, "RETARD", 5),
                marque(inscriptionAli, "RETARD", 20))), enseignant, "ENSEIGNANT");
        assertThat(dans(() -> notifications.dernieres(StatutNotification.ANNULE, 10))).singleElement()
                .satisfies(n -> assertThat(n.message()).contains("Ines ZONGO"));
        // Pendant le délai, le centre du parent d'Awa est vide
        mvc.perform(get("/api/v1/espace-parent/notifications").with(jeton(parentAwa, "PARENT")))
                .andExpect(jsonPath("$.nonLues").value(0));
    }

    private ResultActions abonner(UUID parent, String endpoint) throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var pub = (java.security.interfaces.ECPublicKey) g.generateKeyPair().getPublic();
        byte[] point = new byte[65];
        point[0] = 4;
        byte[] x = pub.getW().getAffineX().toByteArray();
        byte[] y = pub.getW().getAffineY().toByteArray();
        System.arraycopy(x, Math.max(0, x.length - 32), point, 1 + 32 - Math.min(32, x.length), Math.min(32, x.length));
        System.arraycopy(y, Math.max(0, y.length - 32), point, 33 + 32 - Math.min(32, y.length), Math.min(32, y.length));
        var b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        return mvc.perform(post("/api/v1/push/abonnements").with(jeton(parent, "PARENT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"endpoint":"%s","p256dh":"%s","auth":"%s","appareil":"Android · Chrome"}"""
                        .formatted(endpoint, b64.encodeToString(point), b64.encodeToString(new byte[16]))));
    }

    private String statutPush(UUID parent) {
        return dans(() -> new TransactionTemplate(transactions).execute(s -> jdbc.queryForObject(
                "select push_statut from notification_parent where utilisateur_id = ? order by cree_le desc limit 1",
                String.class, parent)));
    }

    private ResultActions synchroniser(String lot, UUID utilisateur, String role) throws Exception {
        return mvc.perform(post("/api/v1/appels/lot").with(jeton(utilisateur, role))
                        .contentType(MediaType.APPLICATION_JSON).content(lot))
                .andExpect(status().isOk());
    }

    private static String lot(String... appels) {
        return "{\"appels\":[" + String.join(",", appels) + "]}";
    }

    private String appel(UUID idClient, LocalDate date, String debut, String fin, String... marques) {
        return """
                {"idClient":"%s","classeId":"%s","date":"%s","heureDebut":"%s","heureFin":"%s","marques":[%s]}"""
                .formatted(idClient, classe, date, debut, fin, String.join(",", marques));
    }

    private static String marque(UUID inscriptionId, String type, Integer minutes) {
        return "{\"inscriptionId\":\"%s\",\"type\":\"%s\"%s}".formatted(inscriptionId, type,
                minutes != null ? ",\"minutesRetard\":" + minutes : "");
    }

    private DossierEleveVue eleve(String nom, String prenoms, Sexe sexe, String telParent) {
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, sexe, LocalDate.of(2014, 5, 12),
                "Ouagadougou", null, null, null),
                List.of(new DonneesResponsable(nom, "Parent", telParent, LienParente.PERE, null, null, true, null))));
    }

    /** Ajoute un membre du personnel et renvoie son compte. */
    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return compte(tel, role);
    }

    private UUID compte(String tel, Role role) {
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "6" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return etablissements.creer("np-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
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
