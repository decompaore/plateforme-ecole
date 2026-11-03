package bf.edutech.plateforme.progression;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Bilan des heures dues et réalisées (v0.50) : volume annuel du référentiel reporté sur les
 * classes d'une filière, dû à ce jour au prorata de l'année, réalisé tiré du cahier de textes,
 * cumuls par enseignant, par matière et par classe ; vue de l'enseignant ; export.
 */
@SpringBootTest
@ActiveProfiles("test")
class BilanHeuresIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private AnneeVue annee;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("bh-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        // 331 jours dans l'année, dont 31 écoulés aujourd'hui inclus
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
    }

    @Test
    void duRealiseEtCumulsParEnseignantMatiereEtClasse() throws Exception {
        UUID technique = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("TECHNIQUE")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F3" + ThreadLocalRandom.current().nextInt(100, 999),
                "Électrotechnique", "Second cycle", "BAC F3", technique)).id();
        UUID classeA = dans(() -> classes.creer(annee.id(), filiere, "2nde F3 A", "2nde", null)).id();
        UUID classeB = dans(() -> classes.creer(annee.id(), filiere, "2nde F3 B", "2nde", null)).id();
        UUID maths = dans(() -> matieres.creer("MATH", "Mathématiques", TypeMatiere.GENERALE)).id();
        UUID elec = dans(() -> matieres.creer("ELEC", "Électricité", TypeMatiere.TECHNIQUE)).id();
        UUID dessin = dans(() -> matieres.creer("DESS", "Dessin technique", TypeMatiere.TECHNIQUE)).id();
        for (UUID c : List.of(classeA, classeB)) {
            dans(() -> classes.definirMatiere(c, maths, BigDecimal.TWO, "Enseignement général", new BigDecimal("4"), null));
        }
        dans(() -> classes.definirMatiere(classeA, elec, BigDecimal.TWO, "Enseignement technique", new BigDecimal("4"), null));
        dans(() -> classes.definirMatiere(classeA, dessin, BigDecimal.ONE, "Enseignement technique", null, null));
        // Deux périodes séparées par 20 jours de vacances : 311 jours de cours, dont 31 écoulés
        dans(() -> periodes.creer(annee.id(), technique, "Semestre 1", annee.debut(), annee.debut().plusDays(59)));
        dans(() -> periodes.creer(annee.id(), technique, "Semestre 2", annee.debut().plusDays(80), annee.fin()));
        dans(() -> annees.ouvrir(annee.id()));
        UUID kabore = enseignant("Kabore", "Awa", classeA, maths);
        UUID sanou = enseignant("Sanou", "Paul", classeA, elec);
        UUID censeur = membre(Role.CENSEUR, "Zongo", "Ines");

        // Le volume annuel du référentiel est reporté sur les deux classes de 2nde F3
        mvc.perform(put("/api/v1/annees/{a}/volumes-annuels", annee.id()).with(jeton(censeur, "CENSEUR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filiereId\":\"%s\",\"niveau\":\"2nde\",\"matiereId\":\"%s\",\"volumeAnnuel\":120}"
                        .formatted(filiere, maths)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.classes").value(2));
        mvc.perform(put("/api/v1/annees/{a}/volumes-annuels", annee.id()).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filiereId\":\"%s\",\"matiereId\":\"%s\",\"volumeAnnuel\":100}".formatted(filiere, maths)))
                .andExpect(status().isForbidden());

        String seance = """
                {"classeId":"%s","matiereId":"%s","date":"%s","heureDebut":"%s","heureFin":"%s","contenu":"%s"}""";
        mvc.perform(put("/api/v1/cahier-textes/{id}", UUID.randomUUID()).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classeA, maths, aujourdhui.minusDays(2), "08:00", "10:00", "Fonctions")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/cahier-textes/{id}", UUID.randomUUID()).with(jeton(sanou, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classeA, elec, aujourdhui.minusDays(1), "10:00", "13:00", "Loi d'Ohm")))
                .andExpect(status().isOk());

        String m = "$.lignes[?(@.classeCode == '2nde F3 A' && @.matiereCode == '%s')]";
        mvc.perform(get("/api/v1/annees/{a}/bilan-heures", annee.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avancementAnnee").value(9))
                .andExpect(jsonPath("$.lignes.length()").value(4))
                .andExpect(jsonPath("$.sansVolume").value(1))
                .andExpect(jsonPath("$.sansEnseignant").value(2))
                // 120 h dues dans l'année, 12 h à ce jour (31/311), 2 h faites
                .andExpect(jsonPath(m.formatted("MATH") + ".sourceVolume").value("REFERENTIEL"))
                .andExpect(jsonPath(m.formatted("MATH") + ".cumul.volumeAnnuel").value(120.0))
                .andExpect(jsonPath(m.formatted("MATH") + ".cumul.duADate").value(12.0))
                .andExpect(jsonPath(m.formatted("MATH") + ".cumul.realise").value(2.0))
                .andExpect(jsonPath(m.formatted("MATH") + ".cumul.reste").value(118.0))
                .andExpect(jsonPath(m.formatted("MATH") + ".cumul.ecart").value(-10.0))
                .andExpect(jsonPath(m.formatted("MATH") + ".etat").value("EN_RETARD"))
                .andExpect(jsonPath(m.formatted("MATH") + ".enseignant").value("KABORE Awa"))
                // Sans volume annuel : estimé à 4 h × 44 semaines de cours
                .andExpect(jsonPath(m.formatted("ELEC") + ".sourceVolume").value("ESTIME"))
                .andExpect(jsonPath(m.formatted("ELEC") + ".cumul.volumeAnnuel").value(176.0))
                .andExpect(jsonPath(m.formatted("ELEC") + ".cumul.realise").value(3.0))
                .andExpect(jsonPath(m.formatted("DESS") + ".sourceVolume").value("AUCUN"))
                .andExpect(jsonPath(m.formatted("DESS") + ".etat").value("SANS_VOLUME"))
                .andExpect(jsonPath("$.enseignants.length()").value(2))
                .andExpect(jsonPath("$.enseignants[0].enseignant").value("KABORE Awa"))
                .andExpect(jsonPath("$.enseignants[0].cumul.realise").value(2.0))
                .andExpect(jsonPath("$.matieres[?(@.matiereCode == 'MATH')].classes").value(2))
                .andExpect(jsonPath("$.matieres[?(@.matiereCode == 'MATH')].cumul.volumeAnnuel").value(240.0))
                .andExpect(jsonPath("$.classes.length()").value(2))
                .andExpect(jsonPath("$.total.realise").value(5.0));

        // L'enseignant ne voit que ses matières, et pas le bilan de la direction
        mvc.perform(get("/api/v1/espace-enseignant/bilan-heures").with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lignes.length()").value(1))
                .andExpect(jsonPath("$.lignes[0].matiereCode").value("MATH"))
                .andExpect(jsonPath("$.enseignants.length()").value(1))
                .andExpect(jsonPath("$.enseignants[0].cumul.duADate").value(12.0));
        mvc.perform(get("/api/v1/annees/{a}/bilan-heures", annee.id()).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/annees/{a}/bilan-heures/export", annee.id()).param("format", "xlsx")
                .with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mvc.perform(get("/api/v1/espace-enseignant/bilan-heures/export").param("format", "pdf")
                .with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.APPLICATION_PDF));
    }

    private UUID enseignant(String nom, String prenoms, UUID classe, UUID matiere) {
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, nom, prenoms, Sexe.M, null,
                TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        dans(() -> enseignants.affecter(classe, matiere, engagement));
        return compte(tel, Role.ENSEIGNANT);
    }

    private UUID membre(Role role, String nom, String prenoms) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, nom, prenoms, role));
        return compte(tel, role);
    }

    private UUID compte(String tel, Role role) {
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel)).findFirst().orElseThrow()
                .utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
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
