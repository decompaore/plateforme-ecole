package bf.edutech.plateforme.passage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.MatieresService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.EvaluationsService;
import bf.edutech.plateforme.evaluations.TypeEvaluation;
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.passage.DecisionsService.DecisionVue;
import bf.edutech.plateforme.passage.DecisionsService.DecisionsClasseVue;
import bf.edutech.plateforme.passage.DecisionsService.SaisieDecision;
import bf.edutech.plateforme.passage.ParametresPassageService.ParametresPassage;
import bf.edutech.plateforme.passage.ParametresPassageService.PoidsPeriode;
import bf.edutech.plateforme.passage.PassageService.AnneeSuivanteVue;
import bf.edutech.plateforme.passage.PassageService.ReinscriptionsVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.scolarite.FraisService;
import bf.edutech.plateforme.scolarite.Portee;
import bf.edutech.plateforme.scolarite.Vues.DonneesFrais;
import bf.edutech.plateforme.scolarite.Vues.TrancheSaisie;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Passage d'année sur une vraie base PostgreSQL (profil "test").
 * <p>
 * Année N : trois trimestres passés ou en cours, poids 1, 2, 2 ; une seule matière (coef. 1).
 * <ul>
 * <li>Awa : 12, 9, 11 → (12 + 18 + 22) / 5 = 10,40 → ADMIS ;</li>
 * <li>Issa : 9, 9, 9 → 9,00 mais déjà deux années en 6e → EXCLU (redoublé deux fois) ;</li>
 * <li>Ali : 8, 9, 9 → 8,80 → REDOUBLE ;</li>
 * <li>Ines : 6, 8, 8 → 7,60 → EXCLU (sous le seuil de 8,5).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class PassageIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private MembresService membres;
    @Autowired private EvaluationsService evaluations;
    @Autowired private NotificationsService notifications;
    @Autowired private FraisService frais;
    @Autowired private ParametresPassageService parametres;
    @Autowired private DecisionsService decisions;
    @Autowired private PassageService passage;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private final int an = j.getYear();
    private MockMvc mvc;
    private UUID ecole;
    private UUID general;
    private UUID filiere;
    private AnneeVue annee;
    private List<PeriodeVue> trimestres;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        filiere = dans(() -> filieres.creer("F" + ThreadLocalRandom.current().nextInt(1000, 9999),
                "Enseignement général", "Premier cycle", "BEPC", general)).id();
        LocalDate debut = j.minusDays(300);
        annee = dans(() -> annees.creer((an - 1) + "-" + an, debut, j.plusDays(60)));
    }

    @Test
    void decisionsConseilValidationAnneeSuivanteEtReinscriptions() throws Exception {
        UUID classe = classe(annee.id(), "6e A", "6e");
        UUID matiere = matiere(classe);
        ouvrirAvecTrimestres();
        dans(() -> parametres.definirPoids(general, List.of(new PoidsPeriode(1, BigDecimal.ONE),
                new PoidsPeriode(2, new BigDecimal("2")), new PoidsPeriode(3, new BigDecimal("2")))));
        dans(() -> frais.creer(annee.id(), new DonneesFrais("Scolarité", 50_000L, null, null, Portee.CLASSES, null,
                null, List.of(classe), List.of(new TrancheSaisie(annee.debut().plusDays(10), 50_000L)))));

        DossierEleveVue dossierAwa = eleve("OUEDRAOGO", "Awa", telephone());
        UUID awa = inscrire(dossierAwa, classe);
        UUID ali = inscrire(eleve("SAWADOGO", "Ali", null), classe);
        UUID ines = inscrire(eleve("ZONGO", "Ines", null), classe);
        // Issa a déjà passé deux années en 6e dans l'établissement
        DossierEleveVue dossierIssa = eleve("KABORE", "Issa", null);
        for (int k = 3; k >= 2; k--) {
            int debutAnnee = an - k - 1;
            AnneeVue ancienne = dans(() -> annees.creer(debutAnnee + "-" + (debutAnnee + 1),
                    LocalDate.of(debutAnnee, 10, 1), LocalDate.of(debutAnnee + 1, 7, 31)));
            UUID ancienneClasse = classe(ancienne.id(), "6e A", "6e");
            inscrire(dossierIssa, ancienneClasse);
        }
        UUID issa = inscrire(dossierIssa, classe);

        noter(classe, matiere, 0, Map.of(awa, "12", issa, "9", ali, "8", ines, "6"));
        noter(classe, matiere, 1, Map.of(awa, "9", issa, "9", ali, "9", ines, "8"));
        noter(classe, matiere, 2, Map.of(awa, "11", issa, "9", ali, "9", ines, "8"));

        // Pas de décision tant que l'année n'est pas terminée
        assertThatThrownBy(() -> commeCenseur(() -> decisions.proposer(classe)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("PERIODES_NON_VERROUILLEES");
        trimestres.forEach(t -> dans(() -> periodes.verrouiller(t.id())));

        DecisionsClasseVue d = commeCenseur(() -> decisions.proposer(classe));
        assertThat(d.eleves()).extracting(DecisionVue::prenoms).containsExactly("Awa", "Issa", "Ali", "Ines");
        assertThat(d.eleves()).extracting(e -> e.moyenneAnnuelle().toPlainString())
                .containsExactly("10.40", "9.00", "8.80", "7.60");
        assertThat(d.eleves()).extracting(DecisionVue::rang).containsExactly(1, 2, 3, 4);
        assertThat(d.eleves()).extracting(DecisionVue::proposition)
                .containsExactly(Decision.ADMIS, Decision.EXCLU, Decision.REDOUBLE, Decision.EXCLU);
        assertThat(d.eleves().get(1).redoublements()).isEqualTo(2);

        // Le conseil garde Issa en redoublement : motif obligatoire
        assertThatThrownBy(() -> commeCenseur(() -> decisions.modifier(classe,
                List.of(new SaisieDecision(issa, Decision.REDOUBLE, null, null)))))
                .isInstanceOf(IllegalArgumentException.class);
        UUID censeur = membre(Role.CENSEUR);
        String corps = """
                {"decisions":[{"inscriptionId":"%s","decision":"REDOUBLE","motif":"Dérogation : santé"}]}"""
                .formatted(issa);
        mvc.perform(put("/api/v1/classes/{id}/decisions", classe).with(jeton(membre(Role.SECRETARIAT), "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/classes/{id}/decisions", classe).with(jeton(censeur, "CENSEUR"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eleves[1].decision").value("REDOUBLE"))
                .andExpect(jsonPath("$.eleves[1].proposition").value("EXCLU"))
                .andExpect(jsonPath("$.eleves[1].motif").value("Dérogation : santé"));
        // Un recalcul garde la décision du conseil
        assertThat(commeCenseur(() -> decisions.proposer(classe)).eleves().get(1).decision())
                .isEqualTo(Decision.REDOUBLE);

        // Validation : définitive, SMS aux familles
        DecisionsClasseVue validees = commeCenseur(() -> decisions.valider(classe));
        assertThat(validees.validees()).isTrue();
        assertThat(validees.repartition()).containsEntry(Decision.ADMIS, 1L).containsEntry(Decision.REDOUBLE, 2L)
                .containsEntry(Decision.EXCLU, 1L);
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10))).anySatisfy(n ->
                assertThat(n.message()).contains("Awa OUEDRAOGO (6e A) est admis(e) en classe supérieure "
                        + "(moyenne annuelle 10,40/20)"));
        assertThatThrownBy(() -> commeCenseur(() -> decisions.proposer(classe)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("DECISIONS_VALIDEES");
        assertThat(commeCenseur(() -> passage.tableau(annee.id())).classesValidees()).isEqualTo(1);

        // Année suivante : structure et frais repris
        AnneeSuivanteVue suivante = commeCenseur(() -> passage.creerAnneeSuivante(annee.id(), an + "-" + (an + 1),
                annee.debut().plusYears(1), annee.fin().plusYears(1)));
        assertThat(suivante.classesCopiees()).isEqualTo(1);
        assertThat(suivante.periodesCopiees()).isEqualTo(3);
        assertThat(suivante.fraisRepris()).isEqualTo(1);
        UUID sixiemeSuivante = dans(() -> classes.lister(suivante.annee().id())).stream()
                .filter(c -> c.code().equals("6e A")).findFirst().map(ClasseVue::id).orElseThrow();
        assertThat(dans(() -> frais.lister(suivante.annee().id()))).singleElement().satisfies(f -> {
            assertThat(f.classes()).containsExactly(sixiemeSuivante);
            assertThat(f.tranches().get(0).dateLimite()).isEqualTo(annee.debut().plusDays(10).plusYears(1));
        });
        UUID cinquieme = classe(suivante.annee().id(), "5e A", "5e");

        // Réinscriptions en masse : Awa en 5e, Ali et Issa redoublent en 6e, Ines n'est pas réinscrite
        ReinscriptionsVue r = commeCenseur(() -> passage.reinscrire(classe, cinquieme, sixiemeSuivante, false));
        assertThat(r.admis().reinscrits()).isEqualTo(1);
        assertThat(r.redoublants().reinscrits()).isEqualTo(2);
        assertThat(r.nonReinscrits()).isEqualTo(1);
        List<InscriptionVue> redoublants = dans(() -> inscriptions.listerParClasse(sixiemeSuivante, false));
        assertThat(redoublants).extracting(InscriptionVue::prenoms).containsExactlyInAnyOrder("Ali", "Issa");
        assertThat(redoublants).allSatisfy(i -> assertThat(i.redoublant()).isTrue());
        assertThat(dans(() -> inscriptions.listerParClasse(cinquieme, false))).extracting(InscriptionVue::prenoms)
                .containsExactly("Awa");
    }

    @Test
    void classeDExamenAttendLesResultatsEtReglesDesactivables() throws Exception {
        UUID classe = classe(annee.id(), "3e A", "3e");
        UUID matiere = matiere(classe);
        ouvrirAvecTrimestres();
        dans(() -> parametres.modifier(new ParametresPassage(null, null)));   // aucune exclusion automatique
        UUID awa = inscrire(eleve("OUEDRAOGO", "Awa", null), classe);
        UUID ali = inscrire(eleve("SAWADOGO", "Ali", null), classe);
        noter(classe, matiere, 2, Map.of(awa, "12", ali, "3"));
        trimestres.forEach(t -> dans(() -> periodes.verrouiller(t.id())));
        UUID censeur = membre(Role.CENSEUR);
        mvc.perform(put("/api/v1/classes/{id}/examen", classe).with(jeton(censeur, "CENSEUR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"examen\":\"BEPC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examen").value("BEPC"));

        DecisionsClasseVue d = commeCenseur(() -> decisions.proposer(classe));
        assertThat(d.examen()).isEqualTo("BEPC");
        assertThat(d.eleves()).allSatisfy(e -> assertThat(e.proposition()).isEqualTo(Decision.EN_ATTENTE_EXAMEN));
        assertThatThrownBy(() -> commeCenseur(() -> decisions.valider(classe)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("RESULTATS_EXAMEN_MANQUANTS");

        // Résultats du BEPC : admise → ADMIS ; ajourné → REDOUBLE (moyenne très faible, mais exclusion désactivée)
        d = commeCenseur(() -> decisions.modifier(classe, List.of(
                new SaisieDecision(awa, null, null, ResultatExamen.ADMIS),
                new SaisieDecision(ali, null, null, ResultatExamen.AJOURNE))));
        assertThat(d.eleves()).extracting(e -> e.prenoms() + ":" + e.decision())
                .containsExactlyInAnyOrder("Awa:ADMIS", "Ali:REDOUBLE");
        assertThat(commeCenseur(() -> decisions.valider(classe)).validees()).isTrue();
    }

    // ------------------------------------------------------------------

    /** Trois trimestres couvrant l'année (le dernier contient aujourd'hui), puis ouverture de l'année. */
    private void ouvrirAvecTrimestres() {
        LocalDate d0 = annee.debut();
        trimestres = new ArrayList<>();
        trimestres.add(dans(() -> periodes.creer(annee.id(), general, "Trimestre 1", d0, d0.plusDays(99))));
        trimestres.add(dans(() -> periodes.creer(annee.id(), general, "Trimestre 2", d0.plusDays(100),
                d0.plusDays(199))));
        trimestres.add(dans(() -> periodes.creer(annee.id(), general, "Trimestre 3", d0.plusDays(200),
                annee.fin())));
        dans(() -> annees.ouvrir(annee.id()));
    }

    private void noter(UUID classe, UUID matiere, int trimestre, Map<UUID, String> notes) {
        PeriodeVue p = trimestres.get(trimestre);
        UUID evaluation = commeCenseur(() -> evaluations.creer(classe, matiere, p.id(), "Composition",
                TypeEvaluation.COMPOSITION, p.debut().plusDays(1), null, null)).id();
        List<SaisieNote> saisies = notes.entrySet().stream()
                .map(e -> new SaisieNote(e.getKey(), new BigDecimal(e.getValue()), false)).toList();
        commeCenseur(() -> evaluations.saisir(evaluation, saisies));
    }

    private UUID classe(UUID anneeId, String code, String niveau) {
        return dans(() -> classes.creer(anneeId, filiere, code, niveau, null)).id();
    }

    private UUID matiere(UUID classe) {
        String code = "M" + ThreadLocalRandom.current().nextInt(1000, 9999);
        UUID id = dans(() -> matieres.creer(code, "Mathématiques", TypeMatiere.GENERALE)).id();
        dans(() -> classes.definirMatiere(classe, id, BigDecimal.ONE, null, BigDecimal.ONE, null));
        return id;
    }

    private DossierEleveVue eleve(String nom, String prenoms, String telParent) {
        List<DonneesResponsable> responsables = telParent == null ? List.of()
                : List.of(new DonneesResponsable(nom, "Parent", telParent, LienParente.PERE, null, null, true, null));
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.F, LocalDate.of(2013, 5, 6), null,
                null, null, null), responsables));
    }

    private UUID inscrire(DossierEleveVue dossier, UUID classe) {
        return dans(() -> inscriptions.inscrire(dossier.eleve().id(), classe, false, null)).id();
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
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
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return etablissements.creer("pas-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
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
