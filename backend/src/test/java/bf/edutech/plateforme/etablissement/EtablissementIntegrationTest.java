package bf.edutech.plateforme.etablissement;

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

import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.etablissement.Vues.ResultatCopie;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Tests du domaine Établissement sur une vraie base PostgreSQL (profil "test").
 * Chaque test crée son propre établissement : les tests sont indépendants.
 */
@SpringBootTest
@ActiveProfiles("test")
class EtablissementIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private WebApplicationContext contexte;

    private MockMvc mvc;
    private UUID ecole;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
    }

    @Test
    void lesProfilsTypesSontCreesUneSeuleFois() {
        List<ProfilVue> premiers = dans(ecole, () -> profils.initialiserProfilsTypes());
        List<ProfilVue> seconds = dans(ecole, () -> profils.initialiserProfilsTypes());

        assertThat(premiers).hasSize(3);
        assertThat(seconds).hasSize(3);
        ProfilVue pro = premiers.stream().filter(p -> p.code().equals("PROFESSIONNEL")).findFirst().orElseThrow();
        assertThat(pro.vocabulaire().apprenant()).isEqualTo("Apprenant");
        assertThat(pro.seuilMaitrise()).isEqualByComparingTo("70");
    }

    @Test
    void cycleDeVieCompletDUneAnneeGeneraleEtTechnique() {
        dans(ecole, () -> profils.initialiserProfilsTypes());
        UUID general = profil("GENERAL");
        UUID technique = profil("TECHNIQUE");
        AnneeVue annee = dans(ecole, () -> annees.creer("2026-2027", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 7, 31)));

        FiliereVue d = dans(ecole, () -> filieres.creer("D", "Série D", "Second cycle", "BAC D", general));
        FiliereVue f3 = dans(ecole, () -> filieres.creer("F3", "Électrotechnique", "Second cycle", "BAC F3", technique));
        MatiereVue maths = dans(ecole, () -> matieres.creer("MATH", "Mathématiques", TypeMatiere.GENERALE));
        ClasseVue tleD = dans(ecole, () -> classes.creer(annee.id(), d.id(), "Tle D", "Terminale", (short) 60));
        ClasseVue tleF3 = dans(ecole, () -> classes.creer(annee.id(), f3.id(), "Tle F3", "Terminale", (short) 40));

        // Général : pas de groupe exigé ; technique : groupe obligatoire
        dans(ecole, () -> classes.definirMatiere(tleD.id(), maths.id(), new BigDecimal("4"), null, null, null));
        assertThatThrownBy(() -> dans(ecole,
                () -> classes.definirMatiere(tleF3.id(), maths.id(), new BigDecimal("3"), null, null, null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("GROUPE_OBLIGATOIRE");
        dans(ecole, () -> classes.definirMatiere(tleF3.id(), maths.id(), new BigDecimal("3"),
                "Enseignement général", null, null));

        // Ouverture refusée tant que le profil technique n'a pas de périodes
        dans(ecole, () -> periodes.generer(annee.id(), general));
        assertThatThrownBy(() -> dans(ecole, () -> annees.ouvrir(annee.id())))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("PERIODES_MANQUANTES");
        List<PeriodeVue> trimestres = dans(ecole, () -> periodes.generer(annee.id(), technique));
        assertThat(trimestres).hasSize(3);
        assertThat(dans(ecole, () -> annees.ouvrir(annee.id())).etat()).isEqualTo(EtatAnnee.ACTIVE);

        // Une seule année active
        AnneeVue suivante = dans(ecole, () -> annees.creer("2027-2028", LocalDate.of(2027, 10, 1), LocalDate.of(2028, 7, 31)));
        assertThatThrownBy(() -> dans(ecole, () -> annees.ouvrir(suivante.id())))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("ANNEE_ACTIVE_EXISTE");

        // Clôture refusée tant que des périodes ne sont pas verrouillées
        assertThatThrownBy(() -> dans(ecole, () -> annees.cloturer(annee.id())))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("PERIODES_NON_VERROUILLEES");
        dans(ecole, () -> periodes.lister(annee.id())).forEach(p -> dans(ecole, () -> periodes.verrouiller(p.id())));
        assertThat(dans(ecole, () -> annees.cloturer(annee.id())).etat()).isEqualTo(EtatAnnee.CLOTUREE);

        // Une année clôturée ne se modifie plus
        assertThatThrownBy(() -> dans(ecole, () -> classes.creer(annee.id(), d.id(), "1re D", "Première", null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("ANNEE_FIGEE");
        assertThat(dans(ecole, () -> annees.archiver(annee.id())).etat()).isEqualTo(EtatAnnee.ARCHIVEE);
    }

    @Test
    void laCopieCreeLAnneeSuivanteAvecSaStructure() {
        dans(ecole, () -> profils.initialiserProfilsTypes());
        UUID general = profil("GENERAL");
        AnneeVue annee = dans(ecole, () -> annees.creer("2026-2027", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 7, 31)));
        FiliereVue d = dans(ecole, () -> filieres.creer("D", "Série D", "Second cycle", "BAC D", general));
        MatiereVue maths = dans(ecole, () -> matieres.creer("MATH", "Mathématiques", TypeMatiere.GENERALE));
        MatiereVue francais = dans(ecole, () -> matieres.creer("FR", "Français", TypeMatiere.GENERALE));
        ClasseVue tleD = dans(ecole, () -> classes.creer(annee.id(), d.id(), "Tle D", "Terminale", null));
        dans(ecole, () -> classes.definirMatiere(tleD.id(), maths.id(), new BigDecimal("4"), null, null, null));
        dans(ecole, () -> classes.definirMatiere(tleD.id(), francais.id(), new BigDecimal("2"), null, null, null));
        dans(ecole, () -> periodes.generer(annee.id(), general));

        ResultatCopie copie = dans(ecole, () -> annees.copier(annee.id(), "2027-2028",
                LocalDate.of(2027, 10, 1), LocalDate.of(2028, 7, 31)));

        assertThat(copie.annee().etat()).isEqualTo(EtatAnnee.PREPARATION);
        assertThat(copie.classesCopiees()).isEqualTo(1);
        assertThat(copie.matieresCopiees()).isEqualTo(2);
        assertThat(copie.periodesCopiees()).isEqualTo(3);
        List<PeriodeVue> nouvelles = dans(ecole, () -> periodes.lister(copie.annee().id()));
        assertThat(nouvelles.get(0).debut()).isEqualTo(LocalDate.of(2027, 10, 1));
        ClasseVue copieTle = dans(ecole, () -> classes.lister(copie.annee().id())).get(0);
        assertThat(dans(ecole, () -> classes.matieres(copieTle.id())))
                .extracting(m -> m.coefficient().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("4", "2");
    }

    @Test
    void laFormationProfessionnelleSOrganiseEnModules() {
        dans(ecole, () -> profils.initialiserProfilsTypes());
        UUID pro = profil("PROFESSIONNEL");
        AnneeVue annee = dans(ecole, () -> annees.creer("2026-2027", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 7, 31)));
        FiliereVue elec = dans(ecole, () -> filieres.creer("CQP-ELEC", "Électricité bâtiment", "CQP", "CQP", pro));
        MatiereVue module = dans(ecole, () -> matieres.creer("M1-INST", "Installation domestique",
                TypeMatiere.MODULE_COMPETENCES));
        ClasseVue groupe = dans(ecole, () -> classes.creer(annee.id(), elec.id(), "CQP Élec G1", "1re année", (short) 25));

        // Pas de génération automatique : les modules se saisissent un à un, sans chevauchement
        assertThatThrownBy(() -> dans(ecole, () -> periodes.generer(annee.id(), pro)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("DECOUPAGE_MANUEL");
        dans(ecole, () -> periodes.creer(annee.id(), pro, "Module 1", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 30)));
        assertThatThrownBy(() -> dans(ecole, () -> periodes.creer(annee.id(), pro, "Module 2",
                LocalDate.of(2026, 11, 15), LocalDate.of(2027, 1, 31))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("CHEVAUCHEMENT");
        PeriodeVue module2 = dans(ecole, () -> periodes.creer(annee.id(), pro, "Module 2",
                LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 31)));
        assertThat(module2.ordre()).isEqualTo(2);

        // La durée du module est obligatoire
        assertThatThrownBy(() -> dans(ecole,
                () -> classes.definirMatiere(groupe.id(), module.id(), BigDecimal.ONE, null, null, null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("VOLUME_TOTAL_OBLIGATOIRE");
        dans(ecole, () -> classes.definirMatiere(groupe.id(), module.id(), BigDecimal.ONE, null, null,
                new BigDecimal("120")));
    }

    @Test
    void uneEcoleNeVoitPasLaStructureDUneAutre() {
        dans(ecole, () -> profils.initialiserProfilsTypes());
        AnneeVue annee = dans(ecole, () -> annees.creer("2026-2027", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 7, 31)));
        UUID autre = nouvelleEcole();

        assertThat(dans(autre, () -> annees.lister())).isEmpty();
        assertThat(dans(autre, () -> profils.lister())).isEmpty();
        assertThatThrownBy(() -> dans(autre, () -> annees.trouver(annee.id())))
                .hasMessageContaining("introuvable");
    }

    @Test
    void unEnseignantConsulteMaisNeModifiePas() throws Exception {
        dans(ecole, () -> profils.initialiserProfilsTypes());
        mvc.perform(get("/api/v1/profils").with(jeton(ecole, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(post("/api/v1/annees").with(jeton(ecole, "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"libelle\":\"2026-2027\",\"debut\":\"2026-10-01\",\"fin\":\"2027-07-31\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/annees").with(jeton(ecole, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"libelle\":\"2026-2027\",\"debut\":\"2026-10-01\",\"fin\":\"2027-07-31\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.etat").value("PREPARATION"));
    }

    // ------------------------------------------------------------------

    /** Exécute l'action dans le contexte de l'établissement (la transaction s'ouvre à l'intérieur). */
    private static <T> T dans(UUID etablissement, Supplier<T> action) {
        return TenantContext.executerPour(etablissement, action);
    }

    private UUID profil(String code) {
        return dans(ecole, () -> profils.lister()).stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow().id();
    }

    private UUID nouvelleEcole() {
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String telephone = "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        return etablissements.creer("etab-" + suffixe, "École " + suffixe, telephone, "ADMIN", "Test")
                .etablissement().id();
    }

    /** Jeton d'accès simulé pour un rôle dans un établissement. */
    private static RequestPostProcessor jeton(UUID etablissement, String role) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", etablissement.toString())
                        .claim("typ_jeton", "ACCES")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
