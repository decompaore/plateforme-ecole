package bf.edutech.plateforme.ateliers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Circuit des besoins sur une vraie base PostgreSQL (profil "test") : le chef des travaux ouvre la
 * campagne ; les enseignants techniques de l'atelier expriment les besoins, le responsable les
 * transmet ; la direction arbitre, valide et transmet à la direction régionale ; l'intendant
 * enregistre la commande ; le chef des travaux réceptionne (conformité aux spécifications et
 * normes) puis répartit entre les ateliers, ce qui alimente leur stock et leurs équipements.
 * Les exports Excel et PDF sont produits pour chaque étape.
 */
@SpringBootTest
@ActiveProfiles("test")
class BesoinsIntegrationTest {

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
        ecole = etablissements.creer("bes-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
    }

    @Test
    void lesBesoinsRemontentSontCommandesPuisRepartisEntreLesAteliers() throws Exception {
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
        UUID intendant = membre(Role.INTENDANT, "Kafando", "Marc");

        String atelier = atelier(chef, "elec", "Atelier d'électricité", filiere);
        String bobinage = atelier(chef, "bob", "Atelier de bobinage", filiere);
        mvc.perform(post("/api/v1/ateliers/{a}/responsable", atelier).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"engagementId\":\"%s\"}".formatted(sanou.engagement())))
                .andExpect(status().isOk());
        String fil = id(mvc.perform(post("/api/v1/catalogue").with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"fil-2.5","designation":"Fil rigide 2,5 mm²","nature":"MATIERE_OEUVRE",
                         "unite":"rouleau","normes":"NF C 32-201","prixReference":15000}"""))
                .andExpect(status().isCreated()).andReturn());
        String perceuse = id(mvc.perform(post("/api/v1/catalogue").with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"PERC","designation":"Perceuse à colonne","nature":"EQUIPEMENT","unite":"pièce",
                         "prixReference":250000}"""))
                .andExpect(status().isCreated()).andReturn());

        // Ouverture de la campagne : un besoin par atelier ouvert ; l'enseignant n'ouvre pas de campagne
        String demandeCampagne = "{\"type\":\"ANNEE_EN_COURS\"}";
        mvc.perform(post("/api/v1/campagnes-besoins").with(jeton(sanou.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content(demandeCampagne)).andExpect(status().isForbidden());
        String campagne = id(mvc.perform(post("/api/v1/campagnes-besoins").with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content(demandeCampagne))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("OUVERTE"))
                .andExpect(jsonPath("$.besoins.length()").value(2))
                .andReturn());
        mvc.perform(post("/api/v1/campagnes-besoins").with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content(demandeCampagne))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAGNE_EXISTANTE"));
        mvc.perform(get("/api/v1/campagnes-besoins").with(jeton(kabore.compte(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        // Expression des besoins par les enseignants techniques de l'atelier
        String besoin = JsonPath.read(mvc.perform(get("/api/v1/ateliers/{a}/besoins", atelier)
                .with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].statut").value("BROUILLON"))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        String ligne = "/api/v1/besoins-ateliers/{b}/lignes/{art}";
        mvc.perform(put(ligne, besoin, fil).with(jeton(kabore.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantite\":20}")).andExpect(status().isForbidden());
        mvc.perform(put(ligne, besoin, fil).with(jeton(traore.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantite\":20,\"justification\":\"TP de câblage domestique\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lignes[0].proposePar").value("TRAORE Ali"));
        mvc.perform(put(ligne, besoin, perceuse).with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantite\":2.5}")).andExpect(status().isBadRequest());
        mvc.perform(put(ligne, besoin, perceuse).with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantite\":3}")).andExpect(status().isOk());
        mvc.perform(delete(ligne, besoin, perceuse).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(put(ligne, besoin, perceuse).with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantite\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.montant").value(20 * 15000 + 2 * 250000))
                .andExpect(jsonPath("$.droits.transmettre").value(true));

        // Transmission par le responsable ; le chef des travaux ne transmet pas encore la campagne
        mvc.perform(post("/api/v1/besoins-ateliers/{b}/transmission", besoin).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/besoins-ateliers/{b}/transmission", besoin).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("TRANSMIS"));
        mvc.perform(put(ligne, besoin, fil).with(jeton(traore.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantite\":25}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/campagnes-besoins/{c}/transmission", campagne).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BESOINS_NON_VALIDES"));

        // Arbitrage et validation par la direction : quantité retenue, prix du catalogue figé
        mvc.perform(post("/api/v1/besoins-ateliers/{b}/renvoi", besoin).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/besoins-ateliers/{b}/arbitrage", besoin).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lignes\":[{\"articleId\":\"%s\",\"quantiteRetenue\":15}]}".formatted(fil)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lignes[0].quantiteRetenue").value(15));
        mvc.perform(post("/api/v1/besoins-ateliers/{b}/validation", besoin).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("VALIDE"))
                .andExpect(jsonPath("$.lignes[1].quantiteRetenue").value(2))
                .andExpect(jsonPath("$.montant").value(15 * 15000 + 2 * 250000));
        mvc.perform(post("/api/v1/campagnes-besoins/{c}/commandes", campagne).with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content(commande("DR-01", fil, perceuse)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAGNE_NON_TRANSMISE"));
        mvc.perform(post("/api/v1/campagnes-besoins/{c}/transmission", campagne).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("TRANSMISE"))
                .andExpect(jsonPath("$.filieres[0].lignes.length()").value(2))
                .andExpect(jsonPath("$.montant").value(725000));

        // Commande enregistrée par l'intendant (passée par la direction régionale)
        String commande = id(mvc.perform(post("/api/v1/campagnes-besoins/{c}/commandes", campagne)
                .with(jeton(intendant, "INTENDANT")).contentType(MediaType.APPLICATION_JSON)
                .content(commande("dr-01", fil, perceuse)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("DR-01"))
                .andExpect(jsonPath("$.montant").value(725000))
                .andReturn());
        mvc.perform(post("/api/v1/campagnes-besoins/{c}/commandes", campagne).with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content(commande("DR-01", fil, perceuse)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCE_EXISTANTE"));

        // Réception : vérification des normes et spécifications ; une partie non conforme exige un motif
        String reception = """
                {"bonLivraison":"BL-778","lignes":[
                  {"articleId":"%s","quantiteRecue":15,"quantiteConforme":12%s},
                  {"articleId":"%s","quantiteRecue":2}]}""";
        mvc.perform(post("/api/v1/commandes/{c}/livraisons", commande).with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content(reception.formatted(fil, "", perceuse)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/commandes/{c}/livraisons", commande).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content(reception.formatted(fil, "", perceuse)))
                .andExpect(status().isBadRequest());
        String livraison = id(mvc.perform(post("/api/v1/commandes/{c}/livraisons", commande).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reception.formatted(fil, ",\"motifNonConformite\":\"Section non conforme à la norme\"", perceuse)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("A_REPARTIR"))
                .andExpect(jsonPath("$.lignes[0].conforme").value(12))
                .andExpect(jsonPath("$.lignes[0].repartition.length()").value(1))
                .andExpect(jsonPath("$.lignes[0].repartition[0].atelierCode").value("ELEC"))
                .andExpect(jsonPath("$.lignes[0].repartition[0].proposee").value(12))
                .andReturn());
        mvc.perform(get("/api/v1/commandes/{c}", commande).with(jeton(intendant, "INTENDANT")))
                .andExpect(jsonPath("$.statut").value("LIVREE_PARTIELLEMENT"))
                .andExpect(jsonPath("$.lignes[0].reste").value(3));
        mvc.perform(post("/api/v1/commandes/{c}/annulation", commande).with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"motif\":\"Budget\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMANDE_LIVREE"));

        // Répartition entre les ateliers : excédent refusé, toute la quantité conforme doit être attribuée
        String repartition = "/api/v1/livraisons/{l}/repartition";
        String part = "{\"articleId\":\"%s\",\"atelierId\":\"%s\",\"quantite\":%s}";
        mvc.perform(put(repartition, livraison).with(jeton(chef, "CHEF_TRAVAUX")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lignes\":[" + part.formatted(fil, atelier, 13) + "]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPARTITION_EXCEDENTAIRE"));
        mvc.perform(put(repartition, livraison).with(jeton(chef, "CHEF_TRAVAUX")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lignes\":[" + part.formatted(fil, atelier, 10) + "]}"))
                .andExpect(status().isOk());
        mvc.perform(post(repartition + "/validation", livraison).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPARTITION_INCOMPLETE"));
        mvc.perform(put(repartition, livraison).with(jeton(chef, "CHEF_TRAVAUX")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lignes\":[" + part.formatted(fil, atelier, 10) + "," + part.formatted(fil, bobinage, 2) + ","
                        + part.formatted(perceuse, atelier, 2) + "]}"))
                .andExpect(status().isOk());
        mvc.perform(post(repartition + "/validation", livraison).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("REPARTIE"));

        // Mise à jour du stock et des équipements de chaque atelier
        mvc.perform(get("/api/v1/ateliers/{a}/stock", atelier).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$[0].quantite").value(10));
        mvc.perform(get("/api/v1/ateliers/{a}/stock", bobinage).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(jsonPath("$[0].quantite").value(2));
        mvc.perform(get("/api/v1/ateliers/{a}/equipements", atelier).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].numeroInventaire", containsString("ELEC-" + aujourdhui.getYear() + "-00")));

        // Exports Excel et PDF des pages du chef des travaux et du chef d'atelier
        exporter("/api/v1/ateliers/export", chef, "CHEF_TRAVAUX");
        exporter("/api/v1/catalogue/export", intendant, "INTENDANT");
        exporter("/api/v1/ateliers/stock-par-filiere/export", chef, "CHEF_TRAVAUX");
        exporter("/api/v1/ateliers/" + atelier + "/equipements/export", sanou.compte(), "ENSEIGNANT");
        exporter("/api/v1/ateliers/" + atelier + "/stock/export", sanou.compte(), "ENSEIGNANT");
        exporter("/api/v1/besoins-ateliers/" + besoin + "/export", sanou.compte(), "ENSEIGNANT");
        exporter("/api/v1/campagnes-besoins/" + campagne + "/export", chef, "CHEF_TRAVAUX");
        exporter("/api/v1/commandes/" + commande + "/export", intendant, "INTENDANT");
        exporter("/api/v1/livraisons/" + livraison + "/export", chef, "CHEF_TRAVAUX");
        mvc.perform(get("/api/v1/campagnes-besoins/{c}/export", campagne).param("format", "pdf")
                .with(jeton(kabore.compte(), "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ateliers/export").param("format", "docx").with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/campagnes-besoins/{c}/cloture", campagne).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("CLOSE"));
    }

    private void exporter(String url, UUID utilisateur, String role) throws Exception {
        byte[] excel = mvc.perform(get(url).with(jeton(utilisateur, role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(excel, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).as(url).isEqualTo("PK");
        byte[] pdf = mvc.perform(get(url).param("format", "pdf").with(jeton(utilisateur, role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).as(url).isEqualTo("%PDF");
    }

    private String atelier(UUID chef, String code, String nom, UUID filiere) throws Exception {
        return id(mvc.perform(post("/api/v1/ateliers").with(jeton(chef, "CHEF_TRAVAUX")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\",\"nom\":\"%s\",\"filieres\":[\"%s\"]}".formatted(code, nom, filiere)))
                .andExpect(status().isCreated()).andReturn());
    }

    private static String commande(String reference, String fil, String perceuse) {
        return """
                {"reference":"%s","fournisseur":"Quincaillerie du Faso","passeePar":"DIRECTION_REGIONALE",
                 "lignes":[{"articleId":"%s","quantite":15},{"articleId":"%s","quantite":2}]}"""
                .formatted(reference, fil, perceuse);
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
