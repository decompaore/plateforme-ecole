package bf.edutech.plateforme.bulletins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import bf.edutech.plateforme.bulletins.Vues.AvisVue;
import bf.edutech.plateforme.bulletins.Vues.BulletinResumeVue;
import bf.edutech.plateforme.bulletins.Vues.GenerationVue;
import bf.edutech.plateforme.bulletins.Vues.SaisieAppreciation;
import bf.edutech.plateforme.bulletins.Vues.SaisieAvis;
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
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.CompetencesService;
import bf.edutech.plateforme.evaluations.EvaluationsService;
import bf.edutech.plateforme.evaluations.NiveauMaitrise;
import bf.edutech.plateforme.evaluations.TypeEvaluation;
import bf.edutech.plateforme.evaluations.Vues.EvaluationVue;
import bf.edutech.plateforme.evaluations.Vues.SaisieCompetence;
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.NotificationVue;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Tests du domaine Bulletins sur une vraie base PostgreSQL (profil "test").
 * Mêmes données que les tests des évaluations : les moyennes attendues y sont détaillées.
 */
@SpringBootTest
@ActiveProfiles("test")
class BulletinsIntegrationTest {

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
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private EvaluationsService evaluations;
    @Autowired private CompetencesService competences;
    @Autowired private NotificationsService notifications;
    @Autowired private BulletinsService bulletins;
    @Autowired private ConseilService conseil;
    @Autowired private BulletinLigneRepository lignes;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private AnneeVue annee;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
    }

    @Test
    void cycleCompletAppreciationsConseilGenerationPublicationParentEtVerification() throws Exception {
        UUID general = profil("GENERAL");
        UUID classe = classe(general, "6e A", false);
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe, "4", null);
        UUID francais = matiere("FR", TypeMatiere.GENERALE, classe, "2", null);
        UUID trimestre = ouvrirAvecTrimestres(general);
        DossierEleveVue dossierAwa = eleve("OUEDRAOGO", "Awa", telephone());
        UUID awa = inscrire(dossierAwa, classe);
        UUID ali = inscrire(eleve("SAWADOGO", "Ali", null), classe);
        UUID ines = inscrire(eleve("ZONGO", "Ines", null), classe);
        UUID issa = inscrire(eleve("KABORE", "Issa", null), classe);
        EvaluationVue devoirMaths = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir 1",
                TypeEvaluation.DEVOIR, aujourdhui, null, null));
        EvaluationVue compoMaths = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Composition",
                TypeEvaluation.COMPOSITION, aujourdhui, new BigDecimal("40"), new BigDecimal("2")));
        EvaluationVue devoirFr = commeCenseur(() -> evaluations.creer(classe, francais, trimestre, "Dictée",
                TypeEvaluation.DEVOIR, aujourdhui, null, null));
        saisir(devoirMaths, note(awa, "16"), note(ali, "12"), absent(ines), note(issa, "12"));
        saisir(compoMaths, note(awa, "30"), note(ali, "24"), note(ines, "20"), note(issa, "24"));
        saisir(devoirFr, note(awa, "10"), note(ali, "14"), note(issa, "14"));

        // Appréciations : le professeur de maths écrit dans SA matière, pas dans celle d'un collègue
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, "SANOU", "Paul", Sexe.M,
                null, TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        dans(() -> enseignants.affecter(classe, maths, engagement));
        UUID professeur = compte(tel, Role.ENSEIGNANT);
        String appreciation = "{\"matiereId\":\"%s\",\"appreciations\":[{\"inscriptionId\":\"%s\",\"texte\":\"%s\"}]}";
        mvc.perform(put("/api/v1/classes/{c}/periodes/{p}/appreciations", classe, trimestre)
                        .with(jeton(professeur, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                        .content(appreciation.formatted(maths, awa, "Très bon trimestre")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.prenoms == 'Awa')].texte").value("Très bon trimestre"));
        mvc.perform(put("/api/v1/classes/{c}/periodes/{p}/appreciations", classe, trimestre)
                        .with(jeton(professeur, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                        .content(appreciation.formatted(francais, awa, "Bien")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/classes/{c}/periodes/{p}/appreciations", classe, trimestre)
                        .param("matiereId", francais.toString()).with(jeton(professeur, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        // Conseil de classe : distinctions proposées d'après les seuils (12 / 14 / 16, avertissement < 8)
        List<AvisVue> propositions = commeCenseur(() -> conseil.avis(classe, trimestre));
        assertThat(avis(propositions, awa).distinctionProposee()).isEqualTo(Distinction.TABLEAU_HONNEUR);
        assertThat(avis(propositions, ines).distinctionProposee()).isEqualTo(Distinction.AVERTISSEMENT_TRAVAIL);
        commeCenseur(() -> conseil.saisirAvis(classe, trimestre, List.of(
                new SaisieAvis(issa, Distinction.AUCUNE, "Peut mieux faire"),
                new SaisieAvis(awa, null, "Excellent travail, continuez"))));

        // Pas de bulletin tant que la période n'est pas verrouillée
        assertThatThrownBy(() -> commeCenseur(() -> bulletins.generer(classe, trimestre)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("PERIODE_NON_VERROUILLEE");
        dans(() -> periodes.verrouiller(trimestre));

        UUID censeur = membre(Role.CENSEUR);
        mvc.perform(post("/api/v1/classes/{c}/periodes/{p}/bulletins", classe, trimestre)
                        .with(jeton(professeur, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/classes/{c}/periodes/{p}/bulletins", classe, trimestre)
                        .with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("GENEREE"))
                .andExpect(jsonPath("$.bulletins.length()").value(4));
        // Régénération possible avant publication (nouveaux codes, mêmes résultats)
        GenerationVue generation = commeCenseur(() -> bulletins.generer(classe, trimestre));
        assertThat(generation.bulletins()).extracting(BulletinResumeVue::prenoms)
                .containsExactly("Awa", "Issa", "Ali", "Ines");
        assertThat(generation.bulletins()).extracting(BulletinResumeVue::distinction).containsExactly(
                Distinction.TABLEAU_HONNEUR, Distinction.AUCUNE, Distinction.TABLEAU_HONNEUR,
                Distinction.AVERTISSEMENT_TRAVAIL);
        BulletinResumeVue bulletinAwa = generation.bulletins().get(0);
        BulletinResumeVue bulletinInes = generation.bulletins().get(3);
        assertThat(bulletinAwa.moyenne()).isEqualByComparingTo("13.56");

        byte[] pdf = mvc.perform(get("/api/v1/bulletins/{id}/pdf", bulletinAwa.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        assertThat(texte(pdf)).contains("BULLETIN DE NOTES", "OUEDRAOGO Awa", "13,56", "1er / 4",
                "Très bon trimestre", "SANOU Paul", "Tableau d'honneur", "Excellent travail, continuez",
                bulletinAwa.codeVerification().substring(0, 5) + "-" + bulletinAwa.codeVerification().substring(5));
        assertThat(texte(commeCenseur(() -> bulletins.pdf(bulletinInes.id()))))
                .contains("6,67", "Avertissement (travail)", "Non admis(e)", "moyenne comptée zéro");
        byte[] classeEntiere = commeCenseur(() -> bulletins.pdfClasse(classe, trimestre));
        try (PDDocument document = Loader.loadPDF(classeEntiere)) {
            assertThat(document.getNumberOfPages()).isEqualTo(4);
        }

        // Avant publication : rien pour le parent, vérification publique impossible
        UUID parent = dans(() -> espaceParent.ouvrir(dossierAwa.responsables().get(0).responsableId()))
                .utilisateurId();
        mvc.perform(get("/api/v1/espace-parent/enfants/{id}/bulletins", dossierAwa.eleve().id())
                        .with(jeton(parent, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        String code = bulletinAwa.codeVerification();
        String codeImprime = (code.substring(0, 5) + "-" + code.substring(5)).toLowerCase();
        mvc.perform(get("/api/v1/verification/bulletins/{code}", codeImprime)).andExpect(status().isNotFound());

        // Publication : SMS au parent d'Awa (le seul élève avec un contact)
        mvc.perform(post("/api/v1/classes/{c}/periodes/{p}/bulletins/publication", classe, trimestre)
                        .with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("PUBLIEE"));
        List<NotificationVue> sms = dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10));
        assertThat(sms).singleElement().satisfies(n -> assertThat(n.message())
                .contains("bulletin « " + generation.periodeLibelle() + " » de Awa OUEDRAOGO (6e A)")
                .contains("moyenne 13,56/20, rang 1er/4"));

        // Une fois publiés, les bulletins ne changent plus
        assertThatThrownBy(() -> commeCenseur(() -> bulletins.generer(classe, trimestre)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("BULLETINS_PUBLIES");
        assertThatThrownBy(() -> commeCenseur(() -> conseil.saisirAvis(classe, trimestre,
                List.of(new SaisieAvis(ines, Distinction.BLAME, null)))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("BULLETINS_PUBLIES");

        // Le parent voit le bulletin publié de son enfant, et seulement celui-là
        mvc.perform(get("/api/v1/espace-parent/enfants/{id}/bulletins", dossierAwa.eleve().id())
                        .with(jeton(parent, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].moyenne").value(13.56))
                .andExpect(jsonPath("$[0].rang").value(1))
                .andExpect(jsonPath("$[0].distinction").value("TABLEAU_HONNEUR"));
        mvc.perform(get("/api/v1/espace-parent/bulletins/{id}/pdf", bulletinAwa.id()).with(jeton(parent, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/v1/espace-parent/bulletins/{id}/pdf", bulletinInes.id()).with(jeton(parent, "PARENT")))
                .andExpect(status().isNotFound());

        // Vérification publique d'un bulletin papier, sans connexion
        mvc.perform(get("/api/v1/verification/bulletins/{code}", codeImprime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eleve").value("OUEDRAOGO Awa"))
                .andExpect(jsonPath("$.classe").value("6e A"))
                .andExpect(jsonPath("$.moyenne").value(13.56))
                .andExpect(jsonPath("$.rang").value(1))
                .andExpect(jsonPath("$.effectif").value(4));
        mvc.perform(get("/api/v1/verification/bulletins/{code}", "ZZZZZ-ZZZZZ")).andExpect(status().isNotFound());
    }

    @Test
    void enseignementTechniqueRubriquesMatieresGeneralesPuisTechniques() throws Exception {
        UUID technique = profil("TECHNIQUE");
        UUID classe = classe(technique, "2nde F3", false);
        // Définies dans le désordre : le bulletin place toujours l'enseignement général en premier
        UUID atelier = matiere("ATEL", TypeMatiere.PRATIQUE, classe, "4", "Enseignement technique");
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe, "2", "Enseignement général");
        UUID trimestre = ouvrirAvecTrimestres(technique);
        UUID awa = inscrire(eleve("OUEDRAOGO", "Awa", null), classe);
        UUID ali = inscrire(eleve("SAWADOGO", "Ali", null), classe);
        EvaluationVue devoir = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir",
                TypeEvaluation.DEVOIR, aujourdhui, null, null));
        EvaluationVue tp = commeCenseur(() -> evaluations.creer(classe, atelier, trimestre, "TP soudure",
                TypeEvaluation.ATELIER, aujourdhui, null, null));
        saisir(devoir, note(awa, "12"), note(ali, "8"));
        saisir(tp, note(awa, "4"), note(ali, "14"));

        // En-tête et seuils propres à l'établissement ; l'administrateur seul les modifie
        UUID admin = membre(Role.ADMIN_ECOLE);
        String seuils = """
                {"entetePays":"BURKINA FASO","enteteDevise":"La Patrie ou la Mort, nous Vaincrons",
                 "enteteMinistere":"Ministère de l'Enseignement secondaire","seuilTableauHonneur":%s,
                 "seuilEncouragements":14,"seuilFelicitations":16,"seuilAvertissement":8}""";
        mvc.perform(put("/api/v1/parametres/bulletins").with(jeton(membre(Role.CENSEUR), "CENSEUR"))
                        .contentType(MediaType.APPLICATION_JSON).content(seuils.formatted("13")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/parametres/bulletins").with(jeton(admin, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON).content(seuils.formatted("7")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/parametres/bulletins").with(jeton(admin, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON).content(seuils.formatted("13")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seuilTableauHonneur").value(13));

        dans(() -> periodes.verrouiller(trimestre));
        GenerationVue generation = commeCenseur(() -> bulletins.generer(classe, trimestre));

        // Ali : 12,00 (sous le nouveau seuil de 13 : pas de tableau d'honneur) ; Awa : 6,67
        BulletinResumeVue bulletinAli = generation.bulletins().get(0);
        assertThat(bulletinAli.prenoms()).isEqualTo("Ali");
        assertThat(bulletinAli.moyenne()).isEqualByComparingTo("12.00");
        assertThat(bulletinAli.distinction()).isEqualTo(Distinction.AUCUNE);
        assertThat(generation.bulletins().get(1).distinction()).isEqualTo(Distinction.AVERTISSEMENT_TRAVAIL);

        List<LigneBulletin> contenu = dans(() -> new TransactionTemplate(transactions)
                .execute(t -> lignes.findByBulletinIdOrderByOrdreAsc(bulletinAli.id()))).stream()
                .map(BulletinLigne::vers).toList();
        assertThat(contenu).extracting(l -> l.nature() + ":" + l.libelle()).containsExactly(
                "MATIERE:MATH", "GROUPE:Enseignement général", "MATIERE:ATEL", "GROUPE:Enseignement technique");
        assertThat(contenu.get(1).moyenne()).isEqualByComparingTo("8");
        assertThat(contenu.get(3).moyenne()).isEqualByComparingTo("14");

        String texte = texte(commeCenseur(() -> bulletins.pdf(bulletinAli.id())));
        assertThat(texte).contains("BURKINA FASO", "La Patrie ou la Mort, nous Vaincrons",
                "Ministère de l'Enseignement secondaire", "ENSEIGNEMENT GÉNÉRAL", "ENSEIGNEMENT TECHNIQUE",
                "Moyenne Enseignement général", "12,00");
        assertThat(texte.indexOf("ENSEIGNEMENT GÉNÉRAL")).isLessThan(texte.indexOf("ENSEIGNEMENT TECHNIQUE"));
    }

    @Test
    void formationProfessionnelleReleveDeCompetences() throws Exception {
        UUID pro = profil("PROFESSIONNEL");
        UUID classe = classe(pro, "CQP Élec G1", true);
        UUID module = matiere("M1-INST", TypeMatiere.MODULE_COMPETENCES, classe, "1", null);
        PeriodeVue periode = dans(() -> periodes.creer(annee.id(), pro, "Module 1", aujourdhui.minusDays(10),
                aujourdhui.plusDays(60)));
        dans(() -> annees.ouvrir(annee.id()));
        UUID awa = inscrire(eleve("OUEDRAOGO", "Awa", telephone()), classe);
        List<UUID> referentiel = new ArrayList<>();
        for (String code : List.of("C1", "C2", "C3")) {
            referentiel.add(dans(() -> competences.creer(module, code, "Compétence " + code, null)).id());
        }
        commeCenseur(() -> competences.evaluer(classe, periode.id(), module, List.of(
                new SaisieCompetence(awa, referentiel.get(0), NiveauMaitrise.ACQUIS),
                new SaisieCompetence(awa, referentiel.get(1), NiveauMaitrise.ACQUIS),
                new SaisieCompetence(awa, referentiel.get(2), NiveauMaitrise.EN_COURS))));
        commeCenseur(() -> conseil.saisirAppreciations(classe, periode.id(), module,
                List.of(new SaisieAppreciation(awa, "Soigner le câblage"))));
        dans(() -> periodes.verrouiller(periode.id()));

        GenerationVue generation = commeCenseur(() -> bulletins.generer(classe, periode.id()));
        BulletinResumeVue bulletin = generation.bulletins().get(0);
        assertThat(bulletin.moyenne()).isNull();
        assertThat(bulletin.rang()).isNull();
        assertThat(bulletin.tauxMaitrise()).isEqualByComparingTo("66.67");
        assertThat(texte(commeCenseur(() -> bulletins.pdf(bulletin.id()))))
                .contains("RELEVÉ DE COMPÉTENCES", "M1-INST", "66,67 %", "Non acquis", "Soigner le câblage")
                .doesNotContain("Moyenne générale");

        commeCenseur(() -> bulletins.publier(classe, periode.id()));
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10))).singleElement()
                .satisfies(n -> assertThat(n.message()).contains("taux de maîtrise 66,67 %"));
    }

    // ------------------------------------------------------------------

    private static String texte(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static AvisVue avis(List<AvisVue> liste, UUID inscription) {
        return liste.stream().filter(a -> a.inscriptionId().equals(inscription)).findFirst().orElseThrow();
    }

    private UUID profil(String code) {
        return dans(() -> profils.lister()).stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow().id();
    }

    private UUID classe(UUID profil, String code, boolean professionnel) {
        String codeFiliere = "F" + ThreadLocalRandom.current().nextInt(1000, 9999);
        UUID filiere = dans(() -> filieres.creer(codeFiliere, "Filière " + code, "Cycle",
                professionnel ? "CQP" : "BEPC", profil)).id();
        return dans(() -> classes.creer(annee.id(), filiere, code, code, null)).id();
    }

    private UUID matiere(String code, TypeMatiere type, UUID classe, String coefficient, String groupe) {
        UUID id = dans(() -> matieres.creer(code, code, type)).id();
        BigDecimal volumeTotal = type == TypeMatiere.MODULE_COMPETENCES ? new BigDecimal("120") : null;
        dans(() -> classes.definirMatiere(classe, id, new BigDecimal(coefficient), groupe, BigDecimal.ONE,
                volumeTotal));
        return id;
    }

    private UUID ouvrirAvecTrimestres(UUID profil) {
        List<PeriodeVue> trimestres = dans(() -> periodes.generer(annee.id(), profil));
        dans(() -> annees.ouvrir(annee.id()));
        return trimestres.stream().filter(t -> !aujourdhui.isBefore(t.debut()) && !aujourdhui.isAfter(t.fin()))
                .findFirst().orElseThrow().id();
    }

    /** Dossier d'élève, avec un parent (contact des SMS) si un téléphone est donné. */
    private DossierEleveVue eleve(String nom, String prenoms, String telParent) {
        List<DonneesResponsable> responsables = telParent == null ? List.of()
                : List.of(new DonneesResponsable(nom, "Parent", telParent, LienParente.PERE, null, null, true, null));
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.F, LocalDate.of(2012, 3, 4),
                null, null, null, null), responsables));
    }

    private UUID inscrire(DossierEleveVue dossier, UUID classe) {
        return dans(() -> inscriptions.inscrire(dossier.eleve().id(), classe, false, null)).id();
    }

    private void saisir(EvaluationVue evaluation, SaisieNote... saisies) {
        commeCenseur(() -> evaluations.saisir(evaluation.id(), List.of(saisies)));
    }

    private static SaisieNote note(UUID inscription, String valeur) {
        return new SaisieNote(inscription, new BigDecimal(valeur), false);
    }

    private static SaisieNote absent(UUID inscription) {
        return new SaisieNote(inscription, null, true);
    }

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

    /** Exécute l'action dans l'établissement, avec les droits d'un censeur. */
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
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return etablissements.creer("bul-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
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
