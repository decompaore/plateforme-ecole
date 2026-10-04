package bf.edutech.plateforme.ateliers;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

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
 * Ateliers sur une vraie base PostgreSQL (profil "test") : le chef des travaux crée l'atelier et
 * désigne un enseignant technique de la filière comme responsable ; le responsable tient le stock,
 * les équipements et l'inventaire ; un enseignant de l'atelier signale une panne ; le mandat se
 * clôt quand l'engagement de l'enseignant prend fin.
 */
@SpringBootTest
@ActiveProfiles("test")
class AteliersIntegrationTest {

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

    private record Prof(UUID compte, UUID engagement) {
    }

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        ecole = etablissements.creer("atel-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
    }

    @Test
    void leChefDesTravauxOrganiseLAtelierEtLeResponsableLeTient() throws Exception {
        UUID technique = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("TECHNIQUE")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F3" + ThreadLocalRandom.current().nextInt(100, 999),
                "Électrotechnique", "Second cycle", "BAC F3", technique)).id();
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "2nde F3", "2nde", null)).id();
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe);
        UUID elec = matiere("ELEC", TypeMatiere.TECHNIQUE, classe);
        UUID tp = matiere("TP-ELEC", TypeMatiere.PRATIQUE, classe);
        dans(() -> periodes.generer(annee.id(), technique));
        dans(() -> annees.ouvrir(annee.id()));

        Prof sanou = enseignant("Sanou", "Paul", classe, elec);
        Prof kabore = enseignant("Kabore", "Awa", classe, maths);
        Prof traore = enseignant("Traore", "Ali", classe, tp);
        UUID chef = membre(Role.CHEF_TRAVAUX, "Ouedraogo", "Salif");
        UUID censeur = membre(Role.CENSEUR, "Zongo", "Ines");
        UUID intendant = membre(Role.INTENDANT, "Kafando", "Marc");

        // Création de l'atelier : chef des travaux oui, censeur non (un chef des travaux est en fonction)
        String donnees = "{\"code\":\"elec\",\"nom\":\"Atelier d'électricité\",\"postes\":24,\"filieres\":[\"%s\"]}"
                .formatted(filiere);
        mvc.perform(post("/api/v1/ateliers").with(jeton(censeur, "CENSEUR")).contentType(MediaType.APPLICATION_JSON)
                .content(donnees)).andExpect(status().isForbidden());
        String atelier = id(mvc.perform(post("/api/v1/ateliers").with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content(donnees))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ELEC"))
                .andExpect(jsonPath("$.alertes.sansResponsable").value(true))
                .andReturn());

        // Responsable : seuls les enseignants techniques de la filière sont proposés
        mvc.perform(get("/api/v1/ateliers/{a}/candidats", atelier).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].enseignant").value("SANOU Paul"));
        mvc.perform(post("/api/v1/ateliers/{a}/responsable", atelier).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"engagementId\":\"%s\"}".formatted(kabore.engagement())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESPONSABLE_NON_ELIGIBLE"));
        mvc.perform(post("/api/v1/ateliers/{a}/responsable", atelier).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"engagementId\":\"%s\"}".formatted(sanou.engagement())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsable.enseignant").value("SANOU Paul"))
                .andExpect(jsonPath("$.responsable.finPrevue").value(aujourdhui.plusMonths(24).toString()));

        // Catalogue : l'intendant et le responsable oui, un enseignant sans atelier non
        mvc.perform(post("/api/v1/catalogue").with(jeton(kabore.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X\",\"designation\":\"X\",\"nature\":\"MATIERE_OEUVRE\",\"unite\":\"pièce\"}"))
                .andExpect(status().isForbidden());
        String fil = id(mvc.perform(post("/api/v1/catalogue").with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"fil-2.5","designation":"Fil rigide 2,5 mm²","nature":"MATIERE_OEUVRE",
                         "unite":"rouleau","specifications":"Cuivre, rouleau de 100 m","normes":"NF C 32-201",
                         "prixReference":15000}"""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("FIL-2.5"))
                .andExpect(jsonPath("$.photo").value(false)).andReturn());
        String perceuse = id(mvc.perform(post("/api/v1/catalogue").with(jeton(sanou.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"PERC","designation":"Perceuse à colonne","nature":"EQUIPEMENT","unite":"pièce"}"""))
                .andExpect(status().isCreated()).andReturn());
        byte[] image = { (byte) 0x89, 'P', 'N', 'G' };
        mvc.perform(multipart("/api/v1/catalogue/{id}/photo", fil)
                .file(new MockMultipartFile("fichier", "fil.png", "image/png", image)).with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photo").value(true));
        mvc.perform(get("/api/v1/catalogue/{id}/photo", fil).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(content().bytes(image));

        // Stock de l'atelier : entrée, sortie refusée au-delà du stock, seuil d'alerte
        String mouvements = "/api/v1/ateliers/{a}/mouvements";
        mvc.perform(post(mouvements, atelier).with(jeton(traore.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\",\"type\":\"ENTREE\",\"quantite\":10}".formatted(fil)))
                .andExpect(status().isForbidden());
        mvc.perform(post(mouvements, atelier).with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\",\"type\":\"ENTREE\",\"quantite\":10,\"motif\":\"Dotation\"}".formatted(fil)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stockApres").value(10));
        mvc.perform(post(mouvements, atelier).with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\",\"type\":\"SORTIE\",\"quantite\":12}".formatted(fil)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_INSUFFISANT"));
        mvc.perform(post(mouvements, atelier).with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\",\"type\":\"SORTIE\",\"quantite\":3,\"motif\":\"TP câblage\"}".formatted(fil)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stockApres").value(7));
        mvc.perform(put("/api/v1/ateliers/{a}/stock/{art}/seuil", atelier, fil).with(jeton(sanou.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"seuil\":8}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sousLeSeuil").value(true));

        // Équipement : numéro attribué automatiquement ; panne signalée par un enseignant de l'atelier
        String equipement = id(mvc.perform(post("/api/v1/ateliers/{a}/equipements", atelier)
                .with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\",\"marque\":\"Bosch\"}".formatted(perceuse)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.designation").value("Perceuse à colonne"))
                .andExpect(jsonPath("$.numeroInventaire").value("ELEC-" + aujourdhui.getYear() + "-001"))
                .andReturn());
        mvc.perform(post("/api/v1/equipements/{e}/pannes", equipement).with(jeton(kabore.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Ne démarre plus\"}"))
                .andExpect(status().isForbidden());
        String panne = id(mvc.perform(post("/api/v1/equipements/{e}/pannes", equipement).with(jeton(traore.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Ne démarre plus\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.signaleePar").value("TRAORE Ali")).andReturn());
        mvc.perform(post("/api/v1/equipements/{e}/pannes", equipement).with(jeton(traore.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Encore\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PANNE_DEJA_SIGNALEE"));
        mvc.perform(get("/api/v1/ateliers").with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertes.equipementsEnPanne").value(1))
                .andExpect(jsonPath("$[0].alertes.articlesSousSeuil").value(1))
                .andExpect(jsonPath("$[0].alertes.sansResponsable").value(false));
        mvc.perform(post("/api/v1/pannes/{p}/cloture", panne).with(jeton(traore.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"REPAREE\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pannes/{p}/cloture", panne).with(jeton(sanou.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statut\":\"REPAREE\",\"intervention\":\"Charbons remplacés\",\"cout\":7500}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("REPAREE"));

        // Inventaire : le stock est figé, toutes les lignes doivent être renseignées, la clôture corrige
        String inventaire = id(mvc.perform(post("/api/v1/ateliers/{a}/inventaires", atelier).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libelle", startsWith(aujourdhui.getMonthValue() >= 8 || aujourdhui.getMonthValue() <= 2
                        ? "1er semestre" : "2e semestre")))
                .andExpect(jsonPath("$.matieres[0].quantiteTheorique").value(7))
                .andExpect(jsonPath("$.equipements[0].etatTheorique").value("BON"))
                .andExpect(jsonPath("$.restantes").value(2)).andReturn());
        mvc.perform(post(mouvements, atelier).with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\",\"type\":\"SORTIE\",\"quantite\":1}".formatted(fil)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTAIRE_EN_COURS"));
        mvc.perform(post("/api/v1/inventaires/{i}/cloture", inventaire).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTAIRE_INCOMPLET"));
        mvc.perform(put("/api/v1/inventaires/{i}/lignes", inventaire).with(jeton(sanou.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"matieres":[{"articleId":"%s","quantiteConstatee":6.5}],
                         "equipements":[{"equipementId":"%s","etatConstate":"EN_PANNE","observation":"Mandrin cassé"}]}"""
                        .formatted(fil, equipement)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.restantes").value(0))
                .andExpect(jsonPath("$.matieres[0].ecart").value(-0.5));
        mvc.perform(post("/api/v1/inventaires/{i}/cloture", inventaire).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("CLOS"));
        mvc.perform(get("/api/v1/ateliers/{a}/stock", atelier).with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].quantite").value(6.5));
        mvc.perform(get("/api/v1/ateliers/{a}/equipements", atelier).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$[0].etat").value("EN_PANNE"))
                .andExpect(jsonPath("$[0].panneOuverte.description", startsWith("Constatée à l'inventaire")));
        mvc.perform(get(mouvements, atelier).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(jsonPath("$[0].type").value("INVENTAIRE")).andExpect(jsonPath("$[0].quantite").value(-0.5));

        // Visibilité : l'enseignant de mathématiques ne voit aucun atelier, celui des TP le voit
        mvc.perform(get("/api/v1/ateliers").with(jeton(kabore.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/ateliers").with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].droits.signaler").value(true))
                .andExpect(jsonPath("$[0].droits.responsable").value(false));
        mvc.perform(get("/api/v1/ateliers/{a}", atelier).with(jeton(kabore.compte(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        // Fin de l'engagement du responsable : son mandat se clôt, l'atelier est signalé sans responsable
        dans(() -> enseignants.terminer(sanou.engagement(), aujourdhui.minusDays(1), "Mutation"));
        mvc.perform(get("/api/v1/ateliers/{a}", atelier).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsable").doesNotExist())
                .andExpect(jsonPath("$.alertes.sansResponsable").value(true))
                .andExpect(jsonPath("$.historique[0].enseignant").value("SANOU Paul"))
                .andExpect(jsonPath("$.historique[0].motifFin").value("Fin d'engagement de l'enseignant"));

        // Paramètres : mandat de 3 ans, inventaire annuel
        mvc.perform(put("/api/v1/parametres/ateliers").with(jeton(chef, "CHEF_TRAVAUX")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dureeMandatMois\":36,\"frequenceInventaire\":\"ANNUELLE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dureeMandatMois").value(36));
        mvc.perform(post("/api/v1/ateliers/{a}/responsable", atelier).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"engagementId\":\"%s\"}".formatted(traore.engagement())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsable.enseignant").value("TRAORE Ali"))
                .andExpect(jsonPath("$.responsable.finPrevue").value(aujourdhui.plusMonths(36).toString()));
    }

    // ------------------------------------------------------------------ outils

    private static String id(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private UUID matiere(String code, TypeMatiere type, UUID classe) {
        UUID id = dans(() -> matieres.creer(code, code, type)).id();
        String groupe = type == TypeMatiere.GENERALE ? "Enseignement général" : "Enseignement technique";
        dans(() -> classes.definirMatiere(classe, id, BigDecimal.TWO, groupe, new BigDecimal("4"), null));
        return id;
    }

    private Prof enseignant(String nom, String prenoms, UUID classe, UUID matiere) {
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, nom, prenoms, Sexe.M, null,
                TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        dans(() -> enseignants.affecter(classe, matiere, engagement));
        return new Prof(compte(tel, Role.ENSEIGNANT), engagement);
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
