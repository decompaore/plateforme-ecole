package bf.edutech.plateforme.statistiques;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.enseignants.EnseignantsService.DonneesEngagement;
import bf.edutech.plateforme.enseignants.TypeEngagement;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.scolarite.EncaissementsService;
import bf.edutech.plateforme.scolarite.EncaissementsService.DonneesPaiement;
import bf.edutech.plateforme.scolarite.FraisService;
import bf.edutech.plateforme.scolarite.MoyenPaiement;
import bf.edutech.plateforme.scolarite.Payeur;
import bf.edutech.plateforme.scolarite.Vues.DonneesFrais;
import bf.edutech.plateforme.scolarite.Vues.TrancheSaisie;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.statistiques.StatistiquesService.RapportVue;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Statistiques sur une vraie base PostgreSQL : deux 6e (filière générale) et une 2nde (Électricité).
 * Frais : 20 000 FCFA pour tous ; Ali boursier (100 %), Ines semi-boursière (50 %), Awa a payé 5 000.
 */
@SpringBootTest
@ActiveProfiles("test")
class StatistiquesIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private FraisService frais;
    @Autowired private EncaissementsService encaissements;
    @Autowired private StatistiquesService statistiques;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private UUID ecole;

    @Test
    void statistiquesDeRentreeRecouvrementEtClasseurExcel() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        int an = j.getYear();
        AnneeVue annee = dans(() -> annees.creer(an + "-" + (an + 1), j.minusDays(30), j.plusDays(300)));
        UUID filiereG = dans(() -> filieres.creer("G" + hasard(), "Enseignement général", "Premier cycle", "BEPC",
                general)).id();
        UUID filiereE = dans(() -> filieres.creer("E" + hasard(), "Électricité", "Second cycle", "BAC F3",
                general)).id();
        UUID sixiemeA = dans(() -> classes.creer(annee.id(), filiereG, "6e A", "6e", null)).id();
        UUID sixiemeB = dans(() -> classes.creer(annee.id(), filiereG, "6e B", "6e", null)).id();
        UUID seconde = dans(() -> classes.creer(annee.id(), filiereE, "2nde E", "2nde", null)).id();
        dans(() -> frais.creer(annee.id(), new DonneesFrais("Scolarité", 20_000L, null, null, null, null, null, null,
                List.of(new TrancheSaisie(j.minusDays(5), 20_000L)))));

        UUID awa = inscrire("OUEDRAOGO", "Awa", Sexe.F, LocalDate.of(2014, 3, 1), sixiemeA, false,
                StatutBourse.NON_BOURSIER);
        inscrire("KABORE", "Issa", Sexe.M, LocalDate.of(2013, 5, 1), sixiemeA, true, StatutBourse.NON_BOURSIER);
        inscrire("SAWADOGO", "Ali", Sexe.M, LocalDate.of(2014, 6, 1), sixiemeB, false, StatutBourse.BOURSIER);
        inscrire("ZONGO", "Ines", Sexe.F, LocalDate.of(2010, 1, 10), seconde, false, StatutBourse.SEMI_BOURSIER);
        commeIntendant(() -> encaissements.encaisser(awa, new DonneesPaiement(5_000L, MoyenPaiement.ESPECES,
                Payeur.FAMILLE, null, null, null, null, null)));

        dans(() -> enseignants.engager(new DonneesEngagement(telephone(), null, "SANOU", "Paul", Sexe.M, null,
                TypeEngagement.TITULAIRE, j.minusDays(30), null, null)));
        dans(() -> enseignants.engager(new DonneesEngagement(telephone(), null, "TRAORE", "Mariam", Sexe.F, null,
                TypeEngagement.VACATAIRE, j.minusDays(30), null, new BigDecimal("2500"))));
        dans(() -> membres.ajouter(telephone(), "KONE", "Adama", Role.INTENDANT));
        UUID secretaire = membre(Role.SECRETARIAT);

        RapportVue r = commeIntendant(() -> statistiques.rapport(annee.id()));

        assertThat(r.classes()).isEqualTo(3);
        assertThat(r.effectifTotal().garcons()).isEqualTo(2);
        assertThat(r.effectifTotal().filles()).isEqualTo(2);
        assertThat(r.effectifs()).extracting(e -> e.niveau() + ":" + e.classes() + ":" + e.effectif().garcons() + "G"
                + e.effectif().filles() + "F:" + e.redoublants().garcons() + "R")
                .containsExactly("6e:2:2G1F:1R", "2nde:1:0G1F:0R");
        int reference = annee.debut().getYear();                     // âge au 31 décembre de l'année de rentrée
        assertThat(r.ages()).anySatisfy(a -> {
            assertThat(a.niveau()).isEqualTo("2nde");
            assertThat(a.age()).isEqualTo(reference - 2010);
            assertThat(a.effectif().filles()).isEqualTo(1);
        });
        assertThat(r.bourses()).extracting(b -> b.filiere() + ":" + b.boursiers().garcons() + "/"
                + b.semiBoursiers().filles() + "/" + (b.nonBoursiers().garcons() + b.nonBoursiers().filles()))
                .containsExactly("Enseignement général:1/0/2", "Électricité:0/1/0");
        assertThat(r.personnel().titulaires().garcons()).isEqualTo(1);
        assertThat(r.personnel().vacataires().filles()).isEqualTo(1);
        assertThat(r.personnel().administratif()).containsEntry(Role.INTENDANT, 1).containsEntry(Role.SECRETARIAT, 1)
                .containsKey(Role.ADMIN_ECOLE).doesNotContainKey(Role.ENSEIGNANT);

        // Recouvrement : familles 20 000 + 20 000 (6e A) + 0 (Ali boursier) + 10 000 (Ines) = 50 000, 5 000 payés
        assertThat(r.recouvrementTotal().duFamilles()).isEqualTo(50_000);
        assertThat(r.recouvrementTotal().payeFamilles()).isEqualTo(5_000);
        assertThat(r.recouvrementTotal().tauxFamilles()).isEqualByComparingTo("10.0");
        assertThat(r.recouvrementTotal().duOrganismes()).isEqualTo(30_000);
        assertThat(r.recouvrementTotal().tauxOrganismes()).isEqualByComparingTo("0");
        assertThat(r.resultats()).isEmpty();                          // aucune décision de fin d'année encore

        // Classeur Excel ; interdit aux enseignants
        byte[] classeur = mvc.perform(get("/api/v1/annees/{id}/statistiques/excel", annee.id())
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook x = new XSSFWorkbook(new ByteArrayInputStream(classeur))) {
            assertThat(x.getNumberOfSheets()).isEqualTo(6);
            assertThat(x.getSheet("Effectifs").getRow(3).getCell(0).getStringCellValue()).isEqualTo("6e");
            assertThat(x.getSheet("Effectifs").getRow(3).getCell(4).getNumericCellValue()).isEqualTo(3);
        }
        mvc.perform(get("/api/v1/annees/{id}/statistiques", annee.id()).with(jeton(membre(Role.ENSEIGNANT),
                        "ENSEIGNANT")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private UUID inscrire(String nom, String prenoms, Sexe sexe, LocalDate naissance, UUID classe,
            boolean redoublant, StatutBourse bourse) {
        UUID eleve = dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, sexe, naissance, null, null, null,
                null), List.of())).eleve().id();
        return dans(() -> inscriptions.inscrire(eleve, classe, redoublant, bourse)).id();
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

    private static String hasard() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    private static String telephone() {
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        return etablissements.creer("st-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
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
