package bf.edutech.plateforme.evaluations;

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
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
import bf.edutech.plateforme.evaluations.Vues.EvaluationVue;
import bf.edutech.plateforme.evaluations.Vues.ModuleVue;
import bf.edutech.plateforme.evaluations.Vues.MoyenneMatiereVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatEleveVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatsPeriodeVue;
import bf.edutech.plateforme.evaluations.Vues.SaisieCompetence;
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.pedagogie.ProfilsService.DonneesProfil;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Tests du domaine Évaluations sur une vraie base PostgreSQL (profil "test").
 * Les résultats attendus ont été calculés à la main (voir les commentaires).
 */
@SpringBootTest
@ActiveProfiles("test")
class EvaluationsIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private EvaluationsService evaluations;
    @Autowired private CompetencesService competences;
    @Autowired private ResultatsService resultats;
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
    void enseignementGeneralMoyennesCoefficientsEtRangsAvecExAequo() {
        UUID general = profil("GENERAL");
        UUID classe = classe(general, "6e A", false);
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe, "4", null);
        UUID francais = matiere("FR", TypeMatiere.GENERALE, classe, "2", null);
        UUID trimestre = ouvrirAvecTrimestres(general);
        UUID awa = inscrire(classe, "OUEDRAOGO", "Awa");
        UUID ali = inscrire(classe, "SAWADOGO", "Ali");
        UUID ines = inscrire(classe, "ZONGO", "Ines");
        UUID issa = inscrire(classe, "KABORE", "Issa");

        EvaluationVue devoirMaths = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir 1",
                TypeEvaluation.DEVOIR, aujourdhui, null, null));
        EvaluationVue compoMaths = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Composition",
                TypeEvaluation.COMPOSITION, aujourdhui, new BigDecimal("40"), new BigDecimal("2")));
        EvaluationVue devoirFr = commeCenseur(() -> evaluations.creer(classe, francais, trimestre, "Dictée",
                TypeEvaluation.DEVOIR, aujourdhui, null, null));
        saisir(devoirMaths, note(awa, "16"), note(ali, "12"), absent(ines), note(issa, "12"));
        saisir(compoMaths, note(awa, "30"), note(ali, "24"), note(ines, "20"), note(issa, "24"));
        saisir(devoirFr, note(awa, "10"), note(ali, "14"), note(issa, "14")); // Ines : aucune note de français

        ResultatsPeriodeVue r = commeCenseur(() -> resultats.calculer(classe, trimestre));

        // Awa : maths (16×1 + 15×2)/3 = 15,33 ; français 10 ; (15,33×4 + 10×2)/6 = 13,56
        // Ali et Issa : maths 12, français 14 → 12,67 (ex-æquo, rang 2) ; Ines : maths 10, français 0 → 6,67 (rang 4)
        assertThat(r.eleves()).extracting(ResultatEleveVue::prenoms).containsExactly("Awa", "Issa", "Ali", "Ines");
        assertThat(r.eleves()).extracting(ResultatEleveVue::rang).containsExactly(1, 2, 2, 4);
        assertThat(r.eleves()).extracting(e -> e.moyenne().toPlainString())
                .containsExactly("13.56", "12.67", "12.67", "6.67");
        ResultatEleveVue resultatInes = r.eleves().get(3);
        MoyenneMatiereVue francaisInes = resultatInes.matieres().stream().filter(m -> m.code().equals("FR"))
                .findFirst().orElseThrow();
        assertThat(francaisInes.sansNote()).isTrue();
        assertThat(francaisInes.moyenne()).isEqualByComparingTo("0");
        assertThat(resultatInes.admis()).isFalse();
        MoyenneMatiereVue mathsAwa = r.eleves().get(0).matieres().stream().filter(m -> m.code().equals("MATH"))
                .findFirst().orElseThrow();
        assertThat(mathsAwa.moyenne().toPlainString()).isEqualTo("15.33");
        assertThat(mathsAwa.rang()).isEqualTo(1);

        assertThat(r.moyenneClasse().toPlainString()).isEqualTo("11.39");
        assertThat(r.plusForte().toPlainString()).isEqualTo("13.56");
        assertThat(r.plusFaible().toPlainString()).isEqualTo("6.67");
        assertThat(r.tauxReussite()).isEqualByComparingTo("75");
        assertThat(r.alertes()).extracting(a -> a.code()).containsExactly("NOTES_MANQUANTES");
    }

    @Test
    void enseignementTechniqueMoyennesParGroupesEtNoteEliminatoire() {
        UUID technique = profil("TECHNIQUE");
        ProfilVue p = dans(() -> profils.trouver(technique));
        dans(() -> profils.modifier(technique, new DonneesProfil(p.libelle(), p.ordre(), p.modele(), p.decoupage(),
                p.gabaritDocument(), p.seuilAdmission(), p.decimalesMoyenne(), new BigDecimal("5"), p.seuilMaitrise(),
                p.vocabulaire())));
        UUID classe = classe(technique, "2nde F3", false);
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe, "2", "Enseignement général");
        UUID atelier = matiere("ATEL", TypeMatiere.PRATIQUE, classe, "4", "Pratique professionnelle");
        UUID trimestre = ouvrirAvecTrimestres(technique);
        UUID awa = inscrire(classe, "OUEDRAOGO", "Awa");
        UUID ali = inscrire(classe, "SAWADOGO", "Ali");

        EvaluationVue devoir = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir",
                TypeEvaluation.DEVOIR, aujourdhui, null, null));
        EvaluationVue tp = commeCenseur(() -> evaluations.creer(classe, atelier, trimestre, "TP soudure",
                TypeEvaluation.ATELIER, aujourdhui, null, null));
        saisir(devoir, note(awa, "12"), note(ali, "8"));
        saisir(tp, note(awa, "4"), note(ali, "14"));

        ResultatsPeriodeVue r = commeCenseur(() -> resultats.calculer(classe, trimestre));

        // Ali : (8×2 + 14×4)/6 = 12,00 ; Awa : (12×2 + 4×4)/6 = 6,67 et 4 < 5 en atelier (éliminatoire)
        ResultatEleveVue premier = r.eleves().get(0);
        ResultatEleveVue second = r.eleves().get(1);
        assertThat(premier.prenoms()).isEqualTo("Ali");
        assertThat(premier.moyenne().toPlainString()).isEqualTo("12.00");
        assertThat(premier.admis()).isTrue();
        assertThat(second.moyenne().toPlainString()).isEqualTo("6.67");
        assertThat(second.matieresEliminatoires()).containsExactly("ATEL");
        assertThat(second.admis()).isFalse();
        assertThat(second.groupes()).extracting(g -> g.groupe() + "=" + g.moyenne().toPlainString())
                .containsExactlyInAnyOrder("Enseignement général=12.00", "Pratique professionnelle=4.00");
    }

    @Test
    void formationProfessionnelleNiveauxDeMaitriseParModule() {
        UUID pro = profil("PROFESSIONNEL");
        UUID classe = classe(pro, "CQP Élec G1", true);
        UUID module = matiere("M1-INST", TypeMatiere.MODULE_COMPETENCES, classe, "1", null);
        PeriodeVue periode = dans(() -> periodes.creer(annee.id(), pro, "Module 1", aujourdhui.minusDays(10),
                aujourdhui.plusDays(60)));
        dans(() -> annees.ouvrir(annee.id()));
        UUID awa = inscrire(classe, "OUEDRAOGO", "Awa");
        UUID ali = inscrire(classe, "SAWADOGO", "Ali");
        List<UUID> referentiel = new ArrayList<>();
        for (String code : List.of("C1", "C2", "C3")) {
            referentiel.add(dans(() -> competences.creer(module, code, "Compétence " + code, null)).id());
        }

        commeCenseur(() -> competences.evaluer(classe, periode.id(), module, List.of(
                new SaisieCompetence(awa, referentiel.get(0), NiveauMaitrise.ACQUIS),
                new SaisieCompetence(awa, referentiel.get(1), NiveauMaitrise.ACQUIS),
                new SaisieCompetence(awa, referentiel.get(2), NiveauMaitrise.EN_COURS),
                new SaisieCompetence(ali, referentiel.get(0), NiveauMaitrise.ACQUIS),
                new SaisieCompetence(ali, referentiel.get(1), NiveauMaitrise.ACQUIS),
                new SaisieCompetence(ali, referentiel.get(2), NiveauMaitrise.ACQUIS))));

        ResultatsPeriodeVue r = commeCenseur(() -> resultats.calculer(classe, periode.id()));

        // Seuil de maîtrise du profil : 70 %. Awa 2/3 = 66,67 % (non acquis) ; Ali 3/3 (acquis)
        assertThat(r.eleves()).allSatisfy(e -> {
            assertThat(e.moyenne()).isNull();
            assertThat(e.rang()).isNull();
        });
        ModuleVue moduleAwa = r.eleves().stream().filter(e -> e.prenoms().equals("Awa")).findFirst().orElseThrow()
                .modules().get(0);
        assertThat(moduleAwa.taux()).isEqualByComparingTo("66.67");
        assertThat(moduleAwa.statut()).isEqualTo(StatutModule.NON_ACQUIS);
        ResultatEleveVue resultatAli = r.eleves().stream().filter(e -> e.prenoms().equals("Ali")).findFirst()
                .orElseThrow();
        assertThat(resultatAli.modules().get(0).statut()).isEqualTo(StatutModule.ACQUIS);
        assertThat(resultatAli.admis()).isTrue();
        assertThat(r.tauxReussite()).isEqualByComparingTo("50");
        assertThat(r.alertes()).isEmpty();
    }

    @Test
    void lEnseignantNeSaisitQueDansSesMatieresEtLaPeriodeVerrouilleeEstClose() throws Exception {
        UUID general = profil("GENERAL");
        UUID classe = classe(general, "5e B", false);
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe, "4", null);
        UUID francais = matiere("FR", TypeMatiere.GENERALE, classe, "2", null);
        UUID trimestre = ouvrirAvecTrimestres(general);
        UUID awa = inscrire(classe, "OUEDRAOGO", "Awa");
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, "Sanou", "Paul", Sexe.M,
                null, TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        dans(() -> enseignants.affecter(classe, maths, engagement));
        UUID compte = dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == Role.ENSEIGNANT && m.telephone().endsWith(tel)).findFirst().orElseThrow()
                .utilisateurId();

        String corps = """
                {"matiereId":"%s","periodeId":"%s","libelle":"Interro","type":"INTERROGATION","date":"%s"}""";
        String reponse = mvc.perform(post("/api/v1/classes/{id}/evaluations", classe).with(jeton(compte, "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps.formatted(maths, trimestre, aujourdhui)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String evaluationId = reponse.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
        mvc.perform(post("/api/v1/classes/{id}/evaluations", classe).with(jeton(compte, "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps.formatted(francais, trimestre, aujourdhui)))
                .andExpect(status().isForbidden());

        // Saisie idempotente (renvoi depuis un appareil), note hors barème refusée
        String notes = "{\"notes\":[{\"inscriptionId\":\"%s\",\"valeur\":14.5}]}".formatted(awa);
        for (int i = 0; i < 2; i++) {
            mvc.perform(put("/api/v1/evaluations/{id}/notes", evaluationId).with(jeton(compte, "ENSEIGNANT"))
                            .contentType(MediaType.APPLICATION_JSON).content(notes))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evaluation.notesSaisies").value(1))
                    .andExpect(jsonPath("$.lignes[0].valeur").value(14.5));
        }
        mvc.perform(put("/api/v1/evaluations/{id}/notes", evaluationId).with(jeton(compte, "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":[{\"inscriptionId\":\"%s\",\"valeur\":21}]}".formatted(awa)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/classes/{id}/resultats", classe).param("periodeId", trimestre.toString())
                        .with(jeton(compte, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eleves[0].moyenne").value(9.67)); // (14,5×4 + 0×2)/6

        // Période verrouillée : plus aucune saisie
        dans(() -> periodes.verrouiller(trimestre));
        assertThatThrownBy(() -> commeCenseur(() -> evaluations.saisir(UUID.fromString(evaluationId),
                List.of(new SaisieNote(awa, new BigDecimal("15"), false)))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("PERIODE_VERROUILLEE");
    }

    @Test
    void uneEvaluationCreeeHorsConnexionNeSeDupliqueJamais() {
        UUID general = profil("GENERAL");
        UUID classe = classe(general, "4e C", false);
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe, "4", null);
        UUID francais = matiere("FR", TypeMatiere.GENERALE, classe, "2", null);
        UUID trimestre = ouvrirAvecTrimestres(general);
        UUID idClient = UUID.randomUUID();

        EvaluationVue creee = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir surprise",
                TypeEvaluation.DEVOIR, aujourdhui, null, null, idClient));
        // Coupure réseau : l'appareil renvoie la même création
        EvaluationVue renvoi = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir surprise",
                TypeEvaluation.DEVOIR, aujourdhui, null, null, idClient));
        assertThat(creee.id()).isEqualTo(idClient);
        assertThat(renvoi.id()).isEqualTo(idClient);
        assertThat(commeCenseur(() -> evaluations.lister(classe, trimestre))).hasSize(1);

        // Période verrouillée entre la création et le renvoi : le renvoi reste reconnu, pas refusé
        dans(() -> periodes.verrouiller(trimestre));
        assertThat(commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir surprise",
                TypeEvaluation.DEVOIR, aujourdhui, null, null, idClient)).id()).isEqualTo(idClient);

        // Le même identifiant ne peut pas désigner une évaluation d'une autre matière
        assertThatThrownBy(() -> commeCenseur(() -> evaluations.creer(classe, francais, trimestre, "Dictée",
                TypeEvaluation.DEVOIR, aujourdhui, null, null, idClient)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("IDENTIFIANT_DEJA_UTILISE");
    }

    // ------------------------------------------------------------------

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

    /** Génère les trimestres du profil, ouvre l'année et renvoie le trimestre en cours. */
    private UUID ouvrirAvecTrimestres(UUID profil) {
        List<PeriodeVue> trimestres = dans(() -> periodes.generer(annee.id(), profil));
        dans(() -> annees.ouvrir(annee.id()));
        return trimestres.stream().filter(t -> !aujourdhui.isBefore(t.debut()) && !aujourdhui.isAfter(t.fin()))
                .findFirst().orElseThrow().id();
    }

    private UUID inscrire(UUID classe, String nom, String prenoms) {
        UUID eleve = dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.F, LocalDate.of(2012, 3, 4),
                null, null, null, null), List.of())).eleve().id();
        return dans(() -> inscriptions.inscrire(eleve, classe, false, null)).id();
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
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        return etablissements.creer("eval-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
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
