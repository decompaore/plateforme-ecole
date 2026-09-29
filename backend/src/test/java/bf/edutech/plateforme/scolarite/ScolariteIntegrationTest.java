package bf.edutech.plateforme.scolarite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.NotificationVue;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.scolarite.EncaissementsService.DonneesPaiement;
import bf.edutech.plateforme.scolarite.Vues.DonneesFrais;
import bf.edutech.plateforme.scolarite.Vues.EtatClasseVue;
import bf.edutech.plateforme.scolarite.Vues.PaiementVue;
import bf.edutech.plateforme.scolarite.Vues.ResultatRelancesVue;
import bf.edutech.plateforme.scolarite.Vues.SituationVue;
import bf.edutech.plateforme.scolarite.Vues.TrancheSaisie;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Tests du domaine Scolarité sur une vraie base PostgreSQL (profil "test").
 * <p>
 * Jeu de frais (J = aujourd'hui) :
 * <ul>
 * <li>Inscription 10 000, toutes les classes, non couverte par la bourse, échue (J-20) ;</li>
 * <li>Scolarité 75 000 pour la filière, 3 tranches de 25 000 : J-10 (échue), J+30, J+90 ;</li>
 * <li>Cantine 15 000, facultative, pour la classe (J+60) ;</li>
 * <li>Atelier 20 000 pour une autre filière (ne s'applique pas).</li>
 * </ul>
 * Awa est semi-boursière (50 % par défaut) avec 5 000 d'exonération sur la scolarité :
 * la 3e tranche passe à 20 000. Part organisme : 12 500 + 12 500 + 10 000 = 35 000 ;
 * part famille : 10 000 + 12 500 + 12 500 + 10 000 = 45 000, dont 22 500 échus.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScolariteIntegrationTest {

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
    @Autowired private OrganismesService organismes;
    @Autowired private BoursesService bourses;
    @Autowired private SituationsService situations;
    @Autowired private EncaissementsService encaissements;
    @Autowired private RelancesService relances;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private AnneeVue annee;
    private UUID filiere;
    private UUID autreFiliere;
    private UUID classe;
    private UUID scolarite;
    private UUID cantine;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        int an = j.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), j.minusDays(30), j.plusDays(300)));
        filiere = dans(() -> filieres.creer("F" + hasard(), "Enseignement général", "Premier cycle", "BEPC", general))
                .id();
        autreFiliere = dans(() -> filieres.creer("G" + hasard(), "Électricité", "Premier cycle", "CAP", general)).id();
        classe = dans(() -> classes.creer(annee.id(), filiere, "6e A", "6e", null)).id();

        dans(() -> frais.creer(annee.id(), new DonneesFrais("Inscription", 10_000L, true, false, null, null, null,
                null, List.of(new TrancheSaisie(j.minusDays(20), 10_000L)))));
        scolarite = dans(() -> frais.creer(annee.id(), new DonneesFrais("Scolarité", 75_000L, null, null,
                Portee.FILIERES, List.of(filiere), null, null, List.of(new TrancheSaisie(j.minusDays(10), 25_000L),
                        new TrancheSaisie(j.plusDays(30), 25_000L), new TrancheSaisie(j.plusDays(90), 25_000L)))))
                .id();
        cantine = dans(() -> frais.creer(annee.id(), new DonneesFrais("Cantine", 15_000L, false, false,
                Portee.CLASSES, null, null, List.of(classe), List.of(new TrancheSaisie(j.plusDays(60), 15_000L)))))
                .id();
        dans(() -> frais.creer(annee.id(), new DonneesFrais("Atelier", 20_000L, true, true, Portee.FILIERES,
                List.of(autreFiliere), null, null, List.of())));
    }

    @Test
    void semiBoursierEncaissementRecuRelanceAnnulationParentEtVerification() throws Exception {
        DossierEleveVue dossierAwa = eleve("OUEDRAOGO", "Awa", telephone());
        UUID awa = inscrire(dossierAwa, StatutBourse.SEMI_BOURSIER);
        UUID ali = inscrire(eleve("SAWADOGO", "Ali", telephone()), StatutBourse.NON_BOURSIER);
        UUID etat = dans(() -> organismes.creer("État burkinabè", TypeOrganisme.ETAT, null)).id();
        commeIntendant(() -> bourses.definirPriseEnCharge(awa, etat, null, "Arrêté 2026-045", j.minusDays(40)));
        commeIntendant(() -> bourses.accorderExoneration(awa, scolarite, 5_000L, "Enfant du personnel"));
        commeIntendant(() -> {
            frais.souscrire(ali, cantine);
            return null;
        });

        SituationVue s = commeIntendant(() -> situations.situation(awa));
        assertThat(s.tauxPriseEnCharge()).isEqualByComparingTo("50");
        assertThat(s.organisme()).isEqualTo("État burkinabè");
        assertThat(s.total()).isEqualTo(85_000);
        assertThat(s.exonere()).isEqualTo(5_000);
        assertThat(s.totalFamille()).isEqualTo(45_000);
        assertThat(s.totalOrganisme()).isEqualTo(35_000);
        assertThat(s.retardFamille()).isEqualTo(22_500);
        assertThat(s.echeances()).extracting(e -> e.libelle() + e.numero() + ":" + e.partFamille() + "/"
                + e.partOrganisme()).containsExactly("Inscription1:10000/0", "Scolarité1:12500/12500",
                        "Scolarité2:12500/12500", "Scolarité3:10000/10000");
        // Ali : inscription + scolarité + cantine souscrite, sans l'atelier d'une autre filière
        assertThat(commeIntendant(() -> situations.situation(ali)).totalFamille()).isEqualTo(100_000);

        // Encaissement au guichet par l'intendant, rejoué (double clic) : un seul paiement
        UUID intendant = membre(Role.INTENDANT);
        String corps = """
                {"montant":15000,"moyen":"ESPECES","payeur":"FAMILLE","deposant":"M. Ouédraogo","cleIdempotence":"%s"}""";
        String cle = UUID.randomUUID().toString();
        String premier = mvc.perform(post("/api/v1/inscriptions/{id}/paiements", awa).with(jeton(intendant, "INTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps.formatted(cle)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recuNumero").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{6}")))
                .andReturn().getResponse().getContentAsString();
        String paiementAwa = premier.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
        mvc.perform(post("/api/v1/inscriptions/{id}/paiements", awa).with(jeton(intendant, "INTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps.formatted(cle)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(paiementAwa));
        s = commeIntendant(() -> situations.situation(awa));
        assertThat(s.payeFamille()).isEqualTo(15_000);
        assertThat(s.retardFamille()).isEqualTo(7_500);           // l'inscription est soldée, 7 500 sur la 1re tranche
        assertThat(s.resteFamille()).isEqualTo(30_000);
        assertThat(s.paiements()).hasSize(1);
        List<NotificationVue> sms = dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10));
        assertThat(sms).singleElement().satisfies(n -> assertThat(n.message())
                .contains("15 000 FCFA reçus pour Awa OUEDRAOGO (6e A). Reste à payer : 30 000 FCFA."));

        // Plus que le reste dû : refusé ; versement de l'organisme par virement
        mvc.perform(post("/api/v1/inscriptions/{id}/paiements", awa).with(jeton(intendant, "INTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montant\":30001,\"moyen\":\"ESPECES\",\"payeur\":\"FAMILLE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONTANT_SUPERIEUR_AU_RESTE"));
        commeIntendant(() -> encaissements.encaisser(awa, new DonneesPaiement(12_500L, MoyenPaiement.VIREMENT,
                Payeur.ORGANISME, null, "VIR-2026-001", null, null, null)));
        s = commeIntendant(() -> situations.situation(awa));
        assertThat(s.payeOrganisme()).isEqualTo(12_500);
        assertThat(s.retardOrganisme()).isZero();
        assertThat(s.resteOrganisme()).isEqualTo(22_500);

        // Reçu PDF
        byte[] recu = mvc.perform(get("/api/v1/paiements/{id}/recu", paiementAwa).with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(texte(recu)).contains("REÇU N°", "OUEDRAOGO Awa", "15 000 FCFA", "quinze mille",
                "Espèces", "M. Ouédraogo", "Reste à payer par la famille");

        // Relances : Awa (7 500 échus) et Ali (35 000 échus) ; pas deux fois dans le délai
        ResultatRelancesVue relance = commeIntendant(() -> relances.relancer(classe));
        assertThat(relance.envoyees()).isEqualTo(2);
        assertThat(commeIntendant(() -> relances.relancer(classe)).dejaRelancees()).isEqualTo(2);
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10)))
                .anySatisfy(n -> assertThat(n.message()).contains("Ali SAWADOGO (6e A) présente un retard de 35 000 FCFA"));

        // État de la classe et liste des retards (Excel)
        EtatClasseVue etatClasse = commeIntendant(() -> situations.etatClasse(classe));
        assertThat(etatClasse.eleves()).hasSize(2);
        assertThat(etatClasse.totalFamille()).isEqualTo(145_000);
        assertThat(etatClasse.payeFamille()).isEqualTo(15_000);
        assertThat(etatClasse.retardFamille()).isEqualTo(42_500);
        byte[] excel = mvc.perform(get("/api/v1/classes/{id}/scolarite/retards", classe)
                        .with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel))) {
            var feuille = classeur.getSheetAt(0);
            assertThat(feuille.getRow(1).getCell(1).getStringCellValue()).isEqualTo("SAWADOGO"); // plus gros retard
            assertThat(feuille.getRow(1).getCell(7).getNumericCellValue()).isEqualTo(35_000);
            assertThat(feuille.getRow(3).getCell(7).getNumericCellValue()).isEqualTo(42_500);  // total
        }

        // Annulation : le paiement ne compte plus, le reçu est marqué « annulé » ; pas deux fois
        mvc.perform(post("/api/v1/paiements/{id}/annulation", paiementAwa).with(jeton(intendant, "INTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motif\":\"Billet contrefait\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annule").value(true));
        assertThatThrownBy(() -> commeIntendant(() -> encaissements.annuler(UUID.fromString(paiementAwa), "encore")))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("PAIEMENT_DEJA_ANNULE");
        assertThat(commeIntendant(() -> situations.situation(awa)).retardFamille()).isEqualTo(22_500);
        assertThat(texte(commeIntendant(() -> encaissements.recuPdf(UUID.fromString(paiementAwa)))))
                .contains("REÇU ANNULÉ", "Billet contrefait");
        mvc.perform(get("/api/v1/paiements").param("du", j.toString()).param("au", j.toString())
                        .with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12_500));

        // Espace parent : la situation de son enfant et ses reçus, rien d'autre
        UUID parent = dans(() -> espaceParent.ouvrir(dossierAwa.responsables().get(0).responsableId()))
                .utilisateurId();
        mvc.perform(get("/api/v1/espace-parent/enfants/{id}/scolarite", dossierAwa.eleve().id())
                        .with(jeton(parent, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resteFamille").value(45_000))
                .andExpect(jsonPath("$[0].echeances.length()").value(4));
        mvc.perform(get("/api/v1/espace-parent/paiements/{id}/recu", paiementAwa).with(jeton(parent, "PARENT")))
                .andExpect(status().isOk());
        PaiementVue paiementAli = commeIntendant(() -> encaissements.encaisser(ali, new DonneesPaiement(5_000L,
                MoyenPaiement.ORANGE_MONEY, Payeur.FAMILLE, null, "OM-778899", null, null, null)));
        mvc.perform(get("/api/v1/espace-parent/paiements/{id}/recu", paiementAli.id()).with(jeton(parent, "PARENT")))
                .andExpect(status().isNotFound());

        // Vérification publique du reçu papier, sans connexion
        String code = paiementAli.recuCode();
        mvc.perform(get("/api/v1/verification/recus/{code}", code.substring(0, 5) + "-" + code.substring(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eleve").value("SAWADOGO Ali"))
                .andExpect(jsonPath("$.montant").value(5_000))
                .andExpect(jsonPath("$.annule").value(false));

        // La base refuse toute modification d'un paiement
        assertThatThrownBy(() -> dans(() -> new TransactionTemplate(transactions).execute(t ->
                jdbc.update("update paiement set montant = 1 where id = ?", paiementAli.id()))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reglesDeGestionEtDroits() throws Exception {
        UUID boursier = inscrire(eleve("KABORE", "Issa", null), StatutBourse.BOURSIER);
        UUID nonBoursier = inscrire(eleve("ZONGO", "Ines", null), StatutBourse.NON_BOURSIER);
        UUID etat = dans(() -> organismes.creer("Conseil régional", TypeOrganisme.COLLECTIVITE, null)).id();

        // Tranches incohérentes, portée sans cible
        assertThatThrownBy(() -> commeIntendant(() -> frais.creer(annee.id(), new DonneesFrais("APE", 5_000L, null,
                null, null, null, null, null, List.of(new TrancheSaisie(j, 2_000L))))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("TRANCHES_INCOHERENTES");
        assertThatThrownBy(() -> commeIntendant(() -> frais.creer(annee.id(), new DonneesFrais("Tenue", 5_000L, null,
                null, Portee.CLASSES, null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        // Boursier à 100 % : la famille ne paie que l'inscription (non couverte), l'organisme la scolarité
        SituationVue s = commeIntendant(() -> situations.situation(boursier));
        assertThat(s.tauxPriseEnCharge()).isEqualByComparingTo("100");
        assertThat(s.totalFamille()).isEqualTo(10_000);
        assertThat(s.totalOrganisme()).isEqualTo(75_000);
        commeIntendant(() -> encaissements.encaisser(boursier, new DonneesPaiement(10_000L, MoyenPaiement.ESPECES,
                Payeur.FAMILLE, null, null, null, null, null)));
        assertThatThrownBy(() -> commeIntendant(() -> encaissements.encaisser(boursier, new DonneesPaiement(1L,
                MoyenPaiement.ESPECES, Payeur.FAMILLE, null, null, null, null, null))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("RIEN_A_PAYER");
        // Sans prise en charge enregistrée, pas de versement d'organisme
        assertThatThrownBy(() -> commeIntendant(() -> encaissements.encaisser(boursier, new DonneesPaiement(1_000L,
                MoyenPaiement.VIREMENT, Payeur.ORGANISME, null, "VIR-1", null, null, null))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("SANS_PRISE_EN_CHARGE");
        // Pas de prise en charge pour un non-boursier ; chèque sans numéro refusé
        assertThatThrownBy(() -> commeIntendant(() -> bourses.definirPriseEnCharge(nonBoursier, etat, null, null,
                null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("ELEVE_NON_BOURSIER");
        assertThatThrownBy(() -> commeIntendant(() -> encaissements.encaisser(nonBoursier, new DonneesPaiement(
                1_000L, MoyenPaiement.CHEQUE, Payeur.FAMILLE, null, null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        // Taux fixé par la décision : 80 % de la scolarité à l'organisme
        commeIntendant(() -> bourses.definirPriseEnCharge(boursier, etat, new java.math.BigDecimal("80"), null, null));
        assertThat(commeIntendant(() -> situations.situation(boursier)).totalFamille()).isEqualTo(25_000);

        // Droits : le secrétariat consulte mais n'encaisse pas et ne crée pas de frais
        UUID secretaire = membre(Role.SECRETARIAT);
        mvc.perform(get("/api/v1/inscriptions/{id}/scolarite", nonBoursier).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/inscriptions/{id}/paiements", nonBoursier).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montant\":1000,\"moyen\":\"ESPECES\",\"payeur\":\"FAMILLE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/annees/{id}/frais", annee.id()).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"libelle\":\"X\",\"montant\":1000}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/inscriptions/{id}/scolarite", nonBoursier).with(jeton(membre(Role.ENSEIGNANT),
                        "ENSEIGNANT")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private static String texte(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private DossierEleveVue eleve(String nom, String prenoms, String telParent) {
        List<DonneesResponsable> responsables = telParent == null ? List.of()
                : List.of(new DonneesResponsable(nom, "Parent", telParent, LienParente.PERE, null, null, true, null));
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.F, LocalDate.of(2013, 5, 6), null,
                null, null, null), responsables));
    }

    private UUID inscrire(DossierEleveVue dossier, StatutBourse bourse) {
        return dans(() -> inscriptions.inscrire(dossier.eleve().id(), classe, false, bourse)).id();
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    /** Exécute l'action dans l'établissement, avec les droits d'un intendant. */
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

    private static String hasard() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    private static String telephone() {
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        return etablissements.creer("sco-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
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
