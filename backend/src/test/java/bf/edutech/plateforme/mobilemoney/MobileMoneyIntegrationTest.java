package bf.edutech.plateforme.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.mobilemoney.Agregateur.LigneReleve;
import bf.edutech.plateforme.mobilemoney.PaiementsMobileMoneyService.TransactionVue;
import bf.edutech.plateforme.mobilemoney.RapprochementService.EcartVue;
import bf.edutech.plateforme.mobilemoney.RapprochementService.RapprochementVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.scolarite.FraisService;
import bf.edutech.plateforme.scolarite.MoyenPaiement;
import bf.edutech.plateforme.scolarite.SituationsService;
import bf.edutech.plateforme.scolarite.Vues.DonneesFrais;
import bf.edutech.plateforme.scolarite.Vues.SituationVue;
import bf.edutech.plateforme.scolarite.Vues.TrancheSaisie;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Paiement Mobile Money avec l'agrégateur simulé, sur une vraie base PostgreSQL.
 * Awa doit 30 000 FCFA de scolarité (échus) ; son parent paie depuis l'espace parent.
 */
@SpringBootTest
@ActiveProfiles("test")
class MobileMoneyIntegrationTest {

    private static final String SECRET = "secret-de-notification-test";

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EspaceParentService espaceParent;
    @Autowired private MembresService membres;
    @Autowired private NotificationsService notifications;
    @Autowired private FraisService frais;
    @Autowired private SituationsService situations;
    @Autowired private PaiementsMobileMoneyService paiements;
    @Autowired private RapprochementService rapprochement;
    @Autowired private AgregateurSimule simulateur;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private UUID classe;
    private String marchand;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        int an = j.getYear();
        AnneeVue annee = dans(() -> annees.creer(an + "-" + (an + 1), j.minusDays(30), j.plusDays(300)));
        UUID filiere = dans(() -> filieres.creer("F" + ThreadLocalRandom.current().nextInt(1000, 9999),
                "Enseignement général", "Premier cycle", "BEPC", general)).id();
        classe = dans(() -> classes.creer(annee.id(), filiere, "3e B", "3e", null)).id();
        dans(() -> frais.creer(annee.id(), new DonneesFrais("Scolarité", 30_000L, null, null, null, null, null, null,
                List.of(new TrancheSaisie(j.minusDays(5), 30_000L)))));
        marchand = "MARCHAND-" + UUID.randomUUID();
    }

    @AfterEach
    void reparerSimulateur() {
        simulateur.panne(false);
    }

    @Test
    void parcoursCompletDuPaiementEnLigne() throws Exception {
        UUID admin = membre(Role.ADMIN_ECOLE);
        UUID intendant = membre(Role.INTENDANT);
        configurer(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agregateur").value("SIMULATEUR"))
                .andExpect(jsonPath("$.cleApiDefinie").value(true))
                .andExpect(jsonPath("$.urlNotification").value("/api/v1/webhooks/mobile-money/" + ecole));
        String reponse = mvc.perform(get("/api/v1/parametres/mobile-money").with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(reponse).doesNotContain(SECRET).doesNotContain("cle-api-test");

        DossierEleveVue dossierAwa = eleve("OUEDRAOGO", "Awa", telephone());
        UUID awa = dans(() -> inscriptions.inscrire(dossierAwa.eleve().id(), classe, false, StatutBourse.NON_BOURSIER))
                .id();
        DossierEleveVue dossierAli = eleve("SAWADOGO", "Ali", telephone());
        UUID ali = dans(() -> inscriptions.inscrire(dossierAli.eleve().id(), classe, false, StatutBourse.NON_BOURSIER))
                .id();
        UUID parent = dans(() -> espaceParent.ouvrir(dossierAwa.responsables().get(0).responsableId()))
                .utilisateurId();

        // 1. Le parent demande 10 000 FCFA ; la même demande rejouée ne crée rien de plus
        String cle = UUID.randomUUID().toString();
        TransactionVue t1 = payer(parent, awa, 10_000, cle);
        assertThat(t1.statut()).isEqualTo(StatutTransaction.EN_ATTENTE);
        assertThat(t1.telephone()).isEqualTo("+22670112233");
        assertThat(payer(parent, awa, 10_000, cle).id()).isEqualTo(t1.id());
        demande(parent, awa, 5_000, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_EN_COURS"));
        demande(parent, ali, 5_000, UUID.randomUUID().toString()).andExpect(status().isForbidden());

        // 2. Il confirme sur son téléphone ; notification signée (une fausse est refusée, un doublon ignoré)
        simulateur.confirmer(marchand, t1.reference());
        notifier(t1.reference(), "0000").andExpect(status().isUnauthorized());
        notifier(t1.reference(), null).andExpect(status().isOk());
        notifier(t1.reference(), null).andExpect(status().isOk());
        mvc.perform(get("/api/v1/espace-parent/mobile-money/{id}", t1.id()).with(jeton(parent, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("CONFIRMEE"))
                .andExpect(jsonPath("$.paiementId").isNotEmpty());
        SituationVue s = commeIntendant(() -> situations.situation(awa));
        assertThat(s.payeFamille()).isEqualTo(10_000);
        assertThat(s.paiements()).singleElement().satisfies(p -> {
            assertThat(p.moyen()).isEqualTo(MoyenPaiement.ORANGE_MONEY);
            assertThat(p.recuNumero()).isNotNull();
        });
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10)))
                .anySatisfy(n -> assertThat(n.message()).contains("10 000 FCFA reçus pour Awa OUEDRAOGO"));

        // 3. Montant reçu différent du montant demandé : rien n'est enregistré, l'intendance vérifie
        TransactionVue t2 = payer(parent, awa, 5_000, UUID.randomUUID().toString());
        simulateur.confirmer(marchand, t2.reference(), 4_000);
        notifier(t2.reference(), null).andExpect(status().isOk());
        assertThat(commeIntendant(() -> paiements.aVerifier())).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(t2.id());
            assertThat(t.montantRecu()).isEqualTo(4_000);
        });
        assertThat(commeIntendant(() -> situations.situation(awa)).payeFamille()).isEqualTo(10_000);

        // 4. Notification perdue : la tâche planifiée consulte l'agrégateur et confirme ; sinon, expiration
        TransactionVue t3 = payer(parent, awa, 8_000, UUID.randomUUID().toString());
        simulateur.confirmer(marchand, t3.reference());
        paiements.traiterEchues(Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(suivre(t3.id()).statut()).isEqualTo(StatutTransaction.CONFIRMEE);
        TransactionVue t4 = payer(parent, awa, 1_000, UUID.randomUUID().toString());
        paiements.traiterEchues(Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(suivre(t4.id()).statut()).isEqualTo(StatutTransaction.EXPIREE);

        // 5. Agrégateur en panne : 503 et invitation à payer à l'intendance
        simulateur.panne(true);
        demande(parent, awa, 1_000, UUID.randomUUID().toString())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MOBILE_MONEY_INDISPONIBLE"));
        simulateur.panne(false);

        // 6. Plus que le reste dû (30 000 − 10 000 − 8 000 = 12 000) : refusé
        demande(parent, awa, 12_001, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONTANT_SUPERIEUR_AU_RESTE"));

        // 7. Rapprochement du jour : un encaissement inconnu et le montant différent de la transaction 2
        simulateur.ajouterAuReleve(marchand, j, new LigneReleve("MM-INCONNUE", "SIM-HORS-PLATEFORME", 2_000));
        RapprochementVue r = commeIntendant(() -> rapprochement.rapprocher(j));
        assertThat(r.lignes()).isEqualTo(4);
        assertThat(r.statut()).isEqualTo("ECARTS");
        List<EcartVue> ecarts = commeIntendant(() -> rapprochement.ecarts(r.id()));
        assertThat(ecarts).extracting(EcartVue::type).containsExactly("MONTANT_DIFFERENT", "NON_ENREGISTRE");
        assertThat(ecarts.get(0).transactionId()).isEqualTo(t2.id());
        commeIntendant(() -> rapprochement.traiter(ecarts.get(1).id(), "Versement d'un autre établissement"));
        assertThatThrownBy(() -> commeIntendant(() -> rapprochement.rapprocher(j)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("RAPPROCHEMENT_TRAITE");

        // 8. L'intendance régularise la transaction 2 (paiement saisi au guichet ou remboursement)
        mvc.perform(post("/api/v1/mobile-money/transactions/{id}/regularisation", t2.id())
                        .with(jeton(intendant, "INTENDANT")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motif\":\"4 000 FCFA encaissés au guichet, reçu 2026-000042\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("REGULARISEE"));
        mvc.perform(get("/api/v1/mobile-money/transactions").param("du", j.toString()).param("au", j.toString())
                        .with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void configurationReserveeALAdministrateurEtSecretsObligatoires() throws Exception {
        UUID admin = membre(Role.ADMIN_ECOLE);
        mvc.perform(put("/api/v1/parametres/mobile-money").with(jeton(membre(Role.INTENDANT), "INTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agregateur\":\"SIMULATEUR\",\"identifiantMarchand\":\"M1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/parametres/mobile-money").with(jeton(admin, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agregateur\":\"SIMULATEUR\",\"identifiantMarchand\":\"M1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/parametres/mobile-money").with(jeton(admin, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agregateur\":\"INCONNU\",\"identifiantMarchand\":\"M1\",\"cleApi\":\"a\","
                                + "\"secretWebhook\":\"b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGREGATEUR_INCONNU"));
        // Sans configuration, pas de paiement en ligne ni de notification acceptée
        DossierEleveVue dossier = eleve("KABORE", "Issa", telephone());
        UUID issa = dans(() -> inscriptions.inscrire(dossier.eleve().id(), classe, false, StatutBourse.NON_BOURSIER))
                .id();
        UUID parent = dans(() -> espaceParent.ouvrir(dossier.responsables().get(0).responsableId())).utilisateurId();
        demande(parent, issa, 1_000, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOBILE_MONEY_INACTIF"));
        notifier("MM-QUELCONQUE", "abcd").andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------

    private ResultActions configurer(UUID admin) throws Exception {
        return mvc.perform(put("/api/v1/parametres/mobile-money").with(jeton(admin, "ADMIN_ECOLE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agregateur":"SIMULATEUR","identifiantMarchand":"%s","cleApi":"cle-api-test",
                         "secretWebhook":"%s"}""".formatted(marchand, SECRET)));
    }

    private ResultActions demande(UUID parent, UUID inscription, long montant, String cle) throws Exception {
        return mvc.perform(post("/api/v1/espace-parent/inscriptions/{id}/mobile-money", inscription)
                .with(jeton(parent, "PARENT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"montant\":%d,\"operateur\":\"ORANGE_MONEY\",\"telephone\":\"70 11 22 33\"%s}"
                        .formatted(montant, cle == null ? "" : ",\"cleIdempotence\":\"" + cle + "\"")));
    }

    private TransactionVue payer(UUID parent, UUID inscription, long montant, String cle) throws Exception {
        String json = demande(parent, inscription, montant, cle).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1"));
        return suivre(id);
    }

    /** État de la transaction, tel que l'intendance le voit. */
    private TransactionVue suivre(UUID id) {
        return commeIntendant(() -> paiements.lister(j, j)).stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow();
    }

    /** Notification de l'agrégateur ; signature calculée avec le bon secret si {@code signature} est null. */
    private ResultActions notifier(String reference, String signature) throws Exception {
        byte[] corps = ("{\"reference\":\"" + reference + "\",\"statut\":\"SUCCES\"}").getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/api/v1/webhooks/mobile-money/{ecole}", ecole)
                .contentType(MediaType.APPLICATION_JSON).content(corps)
                .header(AgregateurSimule.ENTETE_SIGNATURE,
                        signature != null ? signature : AgregateurSimule.signer(SECRET, corps)));
    }

    private DossierEleveVue eleve(String nom, String prenoms, String telParent) {
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.F, LocalDate.of(2011, 2, 3), null,
                null, null, null),
                List.of(new DonneesResponsable(nom, "Parent", telParent, LienParente.MERE, null, null, true, null))));
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T commeIntendant(Supplier<T> action) {
        Authentication precedente = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("intendant", null, "ROLE_INTENDANT"));
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

    private UUID nouvelleEcole() {
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        return etablissements.creer("mm-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
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
