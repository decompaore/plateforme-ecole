package bf.edutech.plateforme.enseignants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import bf.edutech.plateforme.enseignants.EnseignantsService.DonneesEngagement;
import bf.edutech.plateforme.enseignants.Vues.EnseignantVue;
import bf.edutech.plateforme.enseignants.Vues.FicheEnseignantVue;
import bf.edutech.plateforme.enseignants.Vues.ResultatEngagement;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.MatieresService;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.AffectationVue;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.MembreVue;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Tests du domaine Enseignants sur une vraie base PostgreSQL (profil "test").
 * Règle centrale : titulaire dans un seul établissement à la fois, vacataire ailleurs,
 * sans qu'aucune école ne voie où l'enseignant travaille par ailleurs.
 */
@SpringBootTest
@ActiveProfiles("test")
class EnseignantsIntegrationTest {

    private static final LocalDate RENTREE = LocalDate.of(2026, 10, 1);

    @Autowired private EtablissementsService etablissements;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private WebApplicationContext contexte;

    private MockMvc mvc;
    private UUID lycee;
    private UUID cfp;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        lycee = nouvelleEcole();
        cfp = nouvelleEcole();
    }

    @Test
    void unNouvelEnseignantEstCreeAvecSonCompteEtUnEngagementActif() {
        String tel = telephone();
        ResultatEngagement resultat = dans(lycee, () -> enseignants.engager(titulaire(tel, RENTREE)));

        assertThat(resultat.invitation()).isFalse();
        assertThat(resultat.motDePasseTemporaire()).isNotBlank();
        assertThat(resultat.enseignant().statut()).isEqualTo(StatutEngagement.ACTIF);
        assertThat(resultat.enseignant().nom()).isEqualTo("SANOU");
        assertThat(dans(lycee, () -> enseignants.lister(null))).hasSize(1);
        assertThat(utilisateur(lycee, tel)).isNotNull();

        // Une autre école apprend seulement que l'enseignant existe
        assertThat(dans(cfp, () -> enseignants.existe(tel, null))).isTrue();
        assertThat(dans(cfp, () -> enseignants.lister(null))).isEmpty();
    }

    @Test
    void titulaireDansUneSeuleEcoleEtVacataireAilleursAvecSonAccord() throws Exception {
        String tel = telephone();
        dans(lycee, () -> enseignants.engager(titulaire(tel, RENTREE)));

        // Second poste de titulaire refusé, avec un message neutre
        assertThatThrownBy(() -> dans(cfp, () -> enseignants.engager(titulaire(tel, RENTREE))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("POSTE_TITULAIRE_OCCUPE");

        // Vacation : invitation, identité masquée tant qu'elle n'est pas acceptée
        ResultatEngagement invitation = dans(cfp, () -> enseignants.engager(vacataire(tel, RENTREE)));
        assertThat(invitation.invitation()).isTrue();
        assertThat(invitation.enseignant().statut()).isEqualTo(StatutEngagement.INVITE);
        assertThat(invitation.enseignant().nom()).isNull();
        assertThat(invitation.enseignant().telephone()).isNull();
        assertThatThrownBy(() -> dans(cfp, () -> enseignants.engager(vacataire(tel, RENTREE))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("DEJA_ENGAGE");

        // L'enseignant voit l'invitation depuis sa session au lycée et l'accepte
        UUID compte = utilisateur(lycee, tel);
        mvc.perform(get("/api/v1/moi/invitations").with(jeton(lycee, compte, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("VACATAIRE"))
                .andExpect(jsonPath("$[0].tauxHoraire").value(2500));
        mvc.perform(post("/api/v1/moi/invitations/{id}/acceptation", invitation.enseignant().engagementId())
                        .with(jeton(lycee, compte, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("ACTIF"))
                .andExpect(jsonPath("$.etablissementId").value(cfp.toString()));

        // Le CFP voit maintenant son identité, et seulement son propre engagement
        List<EnseignantVue> auCfp = dans(cfp, () -> enseignants.lister(null));
        assertThat(auCfp).singleElement().satisfies(v -> {
            assertThat(v.nom()).isEqualTo("SANOU");
            assertThat(v.type()).isEqualTo(TypeEngagement.VACATAIRE);
        });
        assertThat(utilisateur(cfp, tel)).isEqualTo(compte);

        // Une troisième école invite ; l'enseignant refuse
        UUID college = nouvelleEcole();
        ResultatEngagement autre = dans(college, () -> enseignants.engager(vacataire(tel, RENTREE)));
        mvc.perform(post("/api/v1/moi/invitations/{id}/refus", autre.enseignant().engagementId())
                        .with(jeton(lycee, compte, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("REFUSE"));
        assertThat(dans(college, () -> enseignants.lister(StatutEngagement.REFUSE)).get(0).nom()).isNull();
    }

    @Test
    void lAffectationDonneLaChargeHoraireEtLimiteLesListesDeClasse() throws Exception {
        String tel = telephone();
        UUID engagement = dans(lycee, () -> enseignants.engager(titulaire(tel, RENTREE))).enseignant().engagementId();
        UUID compte = utilisateur(lycee, tel);
        AnneeVue annee = dans(lycee, () -> annees.creer("2026-2027", RENTREE, LocalDate.of(2027, 7, 31)));
        dans(lycee, () -> profils.initialiserProfilsTypes());
        UUID general = dans(lycee, () -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL"))
                .findFirst().orElseThrow().id();
        UUID filiere = dans(lycee, () -> filieres.creer("GEN", "Général", "Premier cycle", "BEPC", general)).id();
        UUID maths = dans(lycee, () -> matieres.creer("MATH", "Mathématiques", TypeMatiere.GENERALE)).id();
        UUID francais = dans(lycee, () -> matieres.creer("FR", "Français", TypeMatiere.GENERALE)).id();
        ClasseVue sixiemeA = dans(lycee, () -> classes.creer(annee.id(), filiere, "6e A", "6e", null));
        ClasseVue sixiemeB = dans(lycee, () -> classes.creer(annee.id(), filiere, "6e B", "6e", null));
        dans(lycee, () -> classes.definirMatiere(sixiemeA.id(), maths, new BigDecimal("4"), null, new BigDecimal("5"), null));
        dans(lycee, () -> classes.definirMatiere(sixiemeB.id(), maths, new BigDecimal("4"), null, new BigDecimal("5"), null));
        dans(lycee, () -> classes.definirMatiere(sixiemeA.id(), francais, new BigDecimal("3"), null, new BigDecimal("4"), null));

        dans(lycee, () -> enseignants.affecter(sixiemeA.id(), maths, engagement));
        dans(lycee, () -> enseignants.affecter(sixiemeB.id(), maths, engagement));

        FicheEnseignantVue fiche = dans(lycee, () -> enseignants.fiche(engagement, annee.id()));
        assertThat(fiche.affectations()).extracting(AffectationVue::classeCode).containsExactly("6e A", "6e B");
        assertThat(fiche.chargeHebdomadaire()).isEqualByComparingTo("10");
        assertThat(dans(lycee, () -> classes.matieresSansEnseignant(annee.id())))
                .extracting(AffectationVue::matiereCode).containsExactly("FR");

        // Un engagement d'une autre école n'existe pas ici ; une invitation ne s'affecte pas
        UUID engagementCfp = dans(cfp, () -> enseignants.engager(titulaire(telephone(), RENTREE)))
                .enseignant().engagementId();
        assertThatThrownBy(() -> dans(lycee, () -> enseignants.affecter(sixiemeA.id(), francais, engagementCfp)))
                .hasMessageContaining("introuvable");
        UUID invite = dans(lycee, () -> enseignants.engager(vacataire(utilisateurTelephone(cfp), RENTREE)))
                .enseignant().engagementId();
        assertThatThrownBy(() -> dans(lycee, () -> enseignants.affecter(sixiemeA.id(), francais, invite)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("ENGAGEMENT_INACTIF");

        // L'enseignant consulte ses classes, pas les autres
        ClasseVue sixiemeC = dans(lycee, () -> classes.creer(annee.id(), filiere, "6e C", "6e", null));
        mvc.perform(get("/api/v1/classes/{id}/inscriptions", sixiemeA.id()).with(jeton(lycee, compte, "ENSEIGNANT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/classes/{id}/inscriptions", sixiemeC.id()).with(jeton(lycee, compte, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/espace-enseignant/affectations").param("anneeId", annee.id().toString())
                        .with(jeton(lycee, compte, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeHebdomadaire").value(10.0))
                .andExpect(jsonPath("$.affectations.length()").value(2));

        // Fin d'engagement programmée : rien ne change avant la date
        int libres = dans(lycee, () -> classes.matieresSansEnseignant(annee.id())).size();
        EnseignantVue programmee = dans(lycee,
                () -> enseignants.terminer(engagement, LocalDate.of(2027, 6, 30), "Mutation"));
        assertThat(programmee.statut()).isEqualTo(StatutEngagement.ACTIF);
        assertThat(programmee.finProgrammee()).isTrue();
        assertThat(dans(lycee, () -> classes.matieresSansEnseignant(annee.id()))).hasSize(libres);

        // Le lendemain, le traitement quotidien clôt l'engagement : accès retiré, matières libérées
        assertThat(dans(lycee, () -> enseignants.terminerEchus(LocalDate.of(2027, 7, 1)))).isEqualTo(1);
        assertThat(dans(lycee, () -> classes.matieresSansEnseignant(annee.id()))).hasSize(3);
        assertThat(dans(lycee, () -> membres.lister()).stream()
                .filter(m -> m.utilisateurId().equals(compte) && m.role() == Role.ENSEIGNANT))
                .singleElement().extracting(MembreVue::actif).isEqualTo(false);
    }

    @Test
    void uneMutationProgrammeeLaisseLePosteActifEtLibereLeTitulariatDesLeLendemain() throws Exception {
        String tel = telephone();
        ResultatEngagement premier = dans(lycee, () -> enseignants.engager(titulaire(tel, RENTREE)));
        UUID auLycee = premier.enseignant().engagementId();

        // Mutation au 30 juin : l'engagement reste actif jusque-là
        EnseignantVue programmee = dans(lycee,
                () -> enseignants.terminer(auLycee, LocalDate.of(2027, 6, 30), "Mutation"));
        assertThat(programmee.statut()).isEqualTo(StatutEngagement.ACTIF);
        assertThat(programmee.finProgrammee()).isTrue();
        assertThat(programmee.fin()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(programmee.motifFin()).isEqualTo("Mutation");

        // Le nouvel établissement l'invite comme titulaire dès le lendemain, pas avant
        assertThatThrownBy(() -> dans(cfp, () -> enseignants.engager(titulaire(tel, LocalDate.of(2027, 6, 1)))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("POSTE_TITULAIRE_OCCUPE");
        ResultatEngagement nouveau = dans(cfp, () -> enseignants.engager(titulaire(tel, LocalDate.of(2027, 7, 1))));
        assertThat(nouveau.invitation()).isTrue();

        // L'ancien établissement ne peut plus annuler la mutation : le poste est promis ailleurs
        assertThatThrownBy(() -> dans(lycee, () -> enseignants.annulerFin(auLycee)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("ANNULATION_FIN_IMPOSSIBLE");

        // Jusqu'à la date de fin, l'enseignant travaille toujours au lycée
        mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telephone\":\"%s\",\"motDePasse\":\"%s\"}"
                                .formatted(tel, premier.motDePasseTemporaire())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jetonAcces").isNotEmpty())
                .andExpect(jsonPath("$.etablissements.length()").value(1));

        // Rien à clore le dernier jour ; le lendemain, clôture automatique
        assertThat(dans(lycee, () -> enseignants.terminerEchus(LocalDate.of(2027, 6, 30)))).isZero();
        assertThat(dans(lycee, () -> enseignants.terminerEchus(LocalDate.of(2027, 7, 1)))).isEqualTo(1);
        EnseignantVue close = dans(lycee, () -> enseignants.lister(null)).getFirst();
        assertThat(close.statut()).isEqualTo(StatutEngagement.TERMINE);
        assertThat(close.finProgrammee()).isFalse();
        assertThat(close.motifFin()).isEqualTo("Mutation");

        // Sans aucun établissement actif, l'enseignant peut quand même se connecter pour répondre
        mvc.perform(post("/api/v1/auth/connexion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telephone\":\"%s\",\"motDePasse\":\"%s\"}"
                                .formatted(tel, premier.motDePasseTemporaire())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequise").value(false))
                .andExpect(jsonPath("$.etablissements").isEmpty());
    }

    @Test
    void uneFinProgrammeeSAnnuleEtUnContratDeVacataireSeClotDeLuiMeme() {
        String tel = telephone();
        UUID vacation = dans(lycee, () -> enseignants.engager(vacataire(tel, RENTREE))).enseignant().engagementId();

        // Une fin d'engagement ne prolonge jamais un contrat
        assertThatThrownBy(() -> dans(lycee, () -> enseignants.terminer(vacation, LocalDate.of(2027, 8, 15), null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("FIN_APRES_CONTRAT");

        EnseignantVue programmee = dans(lycee,
                () -> enseignants.terminer(vacation, LocalDate.of(2027, 3, 31), "Démission"));
        assertThat(programmee.fin()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(programmee.finProgrammee()).isTrue();

        // Annulation : la fin de contrat d'origine revient
        EnseignantVue annulee = dans(lycee, () -> enseignants.annulerFin(vacation));
        assertThat(annulee.statut()).isEqualTo(StatutEngagement.ACTIF);
        assertThat(annulee.fin()).isEqualTo(LocalDate.of(2027, 7, 31));
        assertThat(annulee.finProgrammee()).isFalse();
        assertThat(annulee.motifFin()).isNull();
        assertThatThrownBy(() -> dans(lycee, () -> enseignants.annulerFin(vacation)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("AUCUNE_FIN_PROGRAMMEE");

        // Le contrat arrive à son terme : clôture automatique, motif par défaut
        assertThat(dans(lycee, () -> enseignants.terminerEchus(LocalDate.of(2027, 7, 31)))).isZero();
        assertThat(dans(lycee, () -> enseignants.terminerEchus(LocalDate.of(2027, 8, 1)))).isEqualTo(1);
        EnseignantVue close = dans(lycee, () -> enseignants.lister(null)).getFirst();
        assertThat(close.statut()).isEqualTo(StatutEngagement.TERMINE);
        assertThat(close.motifFin()).isEqualTo("Fin de contrat");
        assertThat(dans(lycee, () -> membres.lister()).stream().filter(m -> m.role() == Role.ENSEIGNANT))
                .singleElement().extracting(MembreVue::actif).isEqualTo(false);
    }

    @Test
    void uneDateDeFinDejaPasseeTermineImmediatement() {
        String tel = telephone();
        UUID engagement = dans(lycee, () -> enseignants.engager(titulaire(tel, RENTREE.minusYears(1))))
                .enseignant().engagementId();

        EnseignantVue terminee = dans(lycee,
                () -> enseignants.terminer(engagement, RENTREE.minusYears(1).plusMonths(9), "Retraite"));
        assertThat(terminee.statut()).isEqualTo(StatutEngagement.TERMINE);
        assertThat(terminee.finProgrammee()).isFalse();
        assertThat(dans(lycee, () -> membres.lister()).stream().filter(m -> m.role() == Role.ENSEIGNANT))
                .singleElement().extracting(MembreVue::actif).isEqualTo(false);
        assertThatThrownBy(() -> dans(lycee, () -> enseignants.annulerFin(engagement)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("STATUT_ENGAGEMENT");
    }

    @Test
    void seulLEtablissementDuTitulaireCorrigeLIdentite() {
        String tel = telephone();
        UUID auLycee = dans(lycee, () -> enseignants.engager(titulaire(tel, RENTREE))).enseignant().engagementId();
        UUID auCfp = dans(cfp, () -> enseignants.engager(vacataire(tel, RENTREE))).enseignant().engagementId();

        assertThatThrownBy(() -> dans(cfp, () -> enseignants.modifierIdentite(auCfp, null, "X", "Y", Sexe.M, null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("IDENTITE_NON_MODIFIABLE");
        String matricule = "fp-" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        EnseignantVue corrige = dans(lycee,
                () -> enseignants.modifierIdentite(auLycee, matricule, "Sanou", "Paul Marie", Sexe.M, "Physique"));
        assertThat(corrige.prenoms()).isEqualTo("Paul Marie");
        assertThat(corrige.matriculeFp()).isEqualTo(matricule.toUpperCase());

        // L'invitation en attente peut être annulée par l'école qui l'a envoyée
        dans(cfp, () -> {
            enseignants.annulerInvitation(auCfp);
            return null;
        });
        assertThat(dans(cfp, () -> enseignants.lister(null))).isEmpty();
    }

    // ------------------------------------------------------------------

    private static <T> T dans(UUID etablissement, Supplier<T> action) {
        return TenantContext.executerPour(etablissement, action);
    }

    private static DonneesEngagement titulaire(String tel, LocalDate debut) {
        return new DonneesEngagement(tel, null, "Sanou", "Paul", Sexe.M, "Mathématiques", TypeEngagement.TITULAIRE,
                debut, null, null);
    }

    private static DonneesEngagement vacataire(String tel, LocalDate debut) {
        return new DonneesEngagement(tel, null, "Sanou", "Paul", Sexe.M, null, TypeEngagement.VACATAIRE, debut,
                LocalDate.of(2027, 7, 31), new BigDecimal("2500"));
    }

    /** Compte de l'enseignant, retrouvé parmi les membres de l'établissement. */
    private UUID utilisateur(UUID etablissement, String tel) {
        return dans(etablissement, () -> membres.lister()).stream()
                .filter(m -> m.role() == Role.ENSEIGNANT && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    /** Téléphone (national) du premier enseignant de l'établissement. */
    private String utilisateurTelephone(UUID etablissement) {
        String international = dans(etablissement, () -> membres.lister()).stream()
                .filter(m -> m.role() == Role.ENSEIGNANT).findFirst().orElseThrow().telephone();
        return international.substring(international.length() - 8);
    }

    private static String telephone() {
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        return etablissements.creer("ens-" + suffixe, "École " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
    }

    private static RequestPostProcessor jeton(UUID etablissement, UUID utilisateur, String role) {
        return jwt()
                .jwt(j -> j.subject(utilisateur.toString())
                        .claim("tenant_id", etablissement.toString())
                        .claim("typ_jeton", "ACCES")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
