package bf.edutech.plateforme.moyens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Besoins et dotations (v0.48). Une direction régionale (DR) et deux directions provinciales (DP1, DP2), un lycée
 * dans chacune. Le censeur et le chef des travaux du lycée A expriment les besoins de leur domaine, le proviseur
 * transmet ; DP1 arbitre, renvoie pour correction, puis transmet ; la DR ne voit un état qu'une fois transmis par la
 * direction provinciale, arbitre dans la limite retenue en dessous et transmet au niveau central. Les dotations
 * descendent : niveau central → DR → DP → lycée, chaque répartition bornée par les quantités conformes reçues, et
 * chaque destinataire accuse réception.
 */
@SpringBootTest
@ActiveProfiles("test")
class BesoinsDotationsIntegrationTest {

    @Autowired private WebApplicationContext contexte;
    @Autowired private EtablissementsService etablissements;
    @Autowired private AnneesService annees;
    @Autowired private MembresService membres;

    private static final RequestPostProcessor SUPER_ADMIN = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
            .claim("typ_jeton", "ACCES"))
            .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;

    @Test
    void lesBesoinsRemontentEtLesDotationsDescendent() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String pays = mvc.perform(get("/api/v1/plateforme/territoire/pays").with(SUPER_ADMIN))
                .andReturn().getResponse().getContentAsString();
        UUID bf = UUID.fromString(((List<String>) JsonPath.read(pays, "$[?(@.code == 'BF')].id")).get(0));
        String ministere = JsonPath.read(mvc.perform(post("/api/v1/plateforme/territoire/pays/{id}/ministeres", bf)
                .with(SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sigle\":\"BD" + suffixe + "\",\"nom\":\"Ministère " + suffixe
                        + "\",\"niveaux\":[\"Direction régionale\",\"Direction provinciale\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/plateforme/territoire/ministeres/{id}/directions/import", ministere).with(SUPER_ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contenu\":\"DR;DR Centre " + suffixe + ";\\nDP1;DP Kadiogo " + suffixe
                        + ";DR\\nDP2;DP Oubritenga " + suffixe + ";DR\",\"simulation\":false}"))
                .andExpect(jsonPath("$.creees").value(3));
        String arbre = mvc.perform(get("/api/v1/plateforme/territoire/ministeres/{id}/directions", ministere)
                .with(SUPER_ADMIN)).andReturn().getResponse().getContentAsString();
        UUID dr = UUID.fromString(((List<String>) JsonPath.read(arbre, "$[?(@.code == 'DR')].id")).get(0));
        UUID dp1 = UUID.fromString(((List<String>) JsonPath.read(arbre, "$[?(@.code == 'DP1')].id")).get(0));
        UUID dp2 = UUID.fromString(((List<String>) JsonPath.read(arbre, "$[?(@.code == 'DP2')].id")).get(0));
        RequestPostProcessor regionale = direction(dr, compte(dr));
        RequestPostProcessor kadiogo = direction(dp1, compte(dp1));
        UUID compteDp2 = compte(dp2);
        RequestPostProcessor oubritenga = direction(dp2, compteDp2);
        RequestPostProcessor central = jwt().jwt(x -> x.subject(compteDp2.toString()).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("ADMIN_PAYS")).claim("pays_id", bf.toString()))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_ADMIN_PAYS"));
        String libelle = j.getYear() + "-" + (j.getYear() + 1);

        // ------------------------------------------------------------ lycée A (DP1) : état des besoins
        Lycee a = lycee("ba-" + suffixe.toLowerCase(), "Lycée A " + suffixe, dp1, libelle);
        UUID censeur = membre(Role.CENSEUR, "Kabore", "Rose");
        UUID chef = membre(Role.CHEF_TRAVAUX, "Ouedraogo", "Salif");
        UUID intendant = membre(Role.INTENDANT, "Kafando", "Marc");
        RequestPostProcessor censeurA = jeton(a.id, censeur, "CENSEUR");
        RequestPostProcessor chefA = jeton(a.id, chef, "CHEF_TRAVAUX");
        RequestPostProcessor proviseurA = jeton(a.id, a.proviseur, "ADMIN_ECOLE");
        RequestPostProcessor intendantA = jeton(a.id, intendant, "INTENDANT");

        mvc.perform(get("/api/v1/etat-besoins").with(censeurA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("BROUILLON"))
                .andExpect(jsonPath("$.annee").value(libelle))
                .andExpect(jsonPath("$.direction").value("DP Kadiogo " + suffixe))
                .andExpect(jsonPath("$.domaines.length()").value(1))
                .andExpect(jsonPath("$.domaines[0]").value("GENERAL"));
        envoyer(post("/api/v1/etat-besoins/{a}/lignes", a.annee), censeurA, """
                {"categorie":"PERSONNEL","domaine":"GENERAL","designation":"Professeur de mathématiques",
                 "unite":"poste","quantite":2,"priorite":1,"justification":"Deux classes de 2nde sans professeur"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.lignes.length()").value(1));
        // Le censeur ne renseigne pas le domaine technique
        envoyer(post("/api/v1/etat-besoins/{a}/lignes", a.annee), censeurA, """
                {"categorie":"EQUIPEMENT_OUTILS","domaine":"TECHNIQUE","designation":"Tour","unite":"pièce","quantite":1}""")
                .andExpect(status().isForbidden());
        envoyer(post("/api/v1/etat-besoins/{a}/lignes", a.annee), chefA, """
                {"categorie":"EQUIPEMENT_OUTILS","domaine":"TECHNIQUE","designation":"Tour parallèle","unite":"pièce",
                 "quantite":3,"priorite":1}""").andExpect(status().isOk());
        String etat = envoyer(post("/api/v1/etat-besoins/{a}/lignes", a.annee), chefA, """
                {"categorie":"MATIERE","domaine":"TECHNIQUE","designation":"Rond d'acier Ø 20","unite":"barre",
                 "quantite":50}""").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String acierA = ((List<String>) JsonPath.read(etat, "$.lignes[?(@.unite == 'barre')].id")).get(0);
        String profA = ((List<String>) JsonPath.read(etat, "$.lignes[?(@.unite == 'poste')].id")).get(0);
        // Le chef des travaux ne touche pas aux besoins du domaine général
        mvc.perform(delete("/api/v1/etat-besoins/lignes/{id}", profA).with(chefA)).andExpect(status().isForbidden());
        envoyer(put("/api/v1/etat-besoins/lignes/{id}", acierA), chefA, """
                {"categorie":"MATIERE","domaine":"TECHNIQUE","designation":"Rond d'acier Ø 20","unite":"barre",
                 "quantite":60}""").andExpect(status().isOk());
        // Seul le proviseur transmet ; l'intendant consulte
        mvc.perform(post("/api/v1/etat-besoins/{a}/transmettre", a.annee).with(censeurA))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/etat-besoins").with(intendantA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lignes.length()").value(3)).andExpect(jsonPath("$.peutTransmettre").value(false));
        envoyer(post("/api/v1/etat-besoins/{a}/transmettre", a.annee), proviseurA, "{\"observations\":\"Urgent\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("TRANSMIS"))
                .andExpect(jsonPath("$.modifiable").value(false));
        envoyer(put("/api/v1/etat-besoins/lignes/{id}", acierA), chefA, """
                {"categorie":"MATIERE","domaine":"TECHNIQUE","designation":"Rond d'acier Ø 20","unite":"barre",
                 "quantite":70}""").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ETAT_TRANSMIS"));
        // Aucun accès des établissements aux routes de pilotage, ni des directions aux états des établissements
        mvc.perform(get("/api/v1/pilotage/besoins").with(proviseurA)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/etat-besoins").with(kadiogo)).andExpect(status().isForbidden());

        // ------------------------------------------------------------ lycée B (DP2)
        Lycee b = lycee("bb-" + suffixe.toLowerCase(), "Lycée B " + suffixe, dp2, libelle);
        RequestPostProcessor proviseurB = jeton(b.id, b.proviseur, "ADMIN_ECOLE");
        envoyer(post("/api/v1/etat-besoins/{a}/transmettre", b.annee), proviseurB, "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ETAT_VIDE"));
        envoyer(post("/api/v1/etat-besoins/{a}/lignes", b.annee), proviseurB, """
                {"categorie":"INFRASTRUCTURE","domaine":"GENERAL","designation":"Salle de classe","unite":"salle",
                 "quantite":2}""").andExpect(status().isOk());
        envoyer(post("/api/v1/etat-besoins/{a}/lignes", b.annee), proviseurB, """
                {"categorie":"EQUIPEMENT_OUTILS","domaine":"TECHNIQUE","designation":"Tour  parallèle","unite":"Pièce",
                 "quantite":1}""").andExpect(status().isOk());
        envoyer(post("/api/v1/etat-besoins/{a}/transmettre", b.annee), proviseurB, "{}").andExpect(status().isOk());

        // ------------------------------------------------------------ DP1 : réception, arbitrage, renvoi
        mvc.perform(get("/api/v1/pilotage/besoins").with(kadiogo)).andExpect(status().isOk())
                .andExpect(jsonPath("$.annee").value(libelle))
                .andExpect(jsonPath("$.destination").value("DR Centre " + suffixe))
                .andExpect(jsonPath("$.etablissements.length()").value(1))
                .andExpect(jsonPath("$.etablissements[0].statut").value("RECU"))
                .andExpect(jsonPath("$.consolidation.length()").value(3))
                .andExpect(jsonPath("$.peutTransmettre").value(true));
        String vueDr = mvc.perform(get("/api/v1/pilotage/besoins").with(regionale)).andExpect(status().isOk())
                .andExpect(jsonPath("$.etablissements[?(@.id == '" + a.id + "')].statut").value("EN_COURS"))
                .andExpect(jsonPath("$.etablissements[?(@.id == '" + a.id + "')].etape").value("DP Kadiogo " + suffixe))
                .andExpect(jsonPath("$.consolidation.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String remonteeA = ((List<String>) JsonPath.read(vueDr, "$.etablissements[?(@.id == '" + a.id
                + "')].remonteeId")).get(0);
        String tourA = ligne(remonteeA, kadiogo, "pièce");
        // La DR n'arbitre pas un état qui n'est pas arrivé jusqu'à elle
        envoyer(put("/api/v1/pilotage/besoins/remontees/{id}/arbitrages", remonteeA), regionale,
                "{\"lignes\":[{\"ligneId\":\"" + tourA + "\",\"quantiteRetenue\":1}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ETAT_NON_RECU"));
        // DP2 ne voit pas le lycée A
        mvc.perform(get("/api/v1/pilotage/besoins/remontees/{id}", remonteeA).with(oubritenga))
                .andExpect(status().isForbidden());
        envoyer(put("/api/v1/pilotage/besoins/remontees/{id}/arbitrages", remonteeA), kadiogo,
                "{\"lignes\":[{\"ligneId\":\"" + tourA + "\",\"quantiteRetenue\":5}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUANTITE_SUPERIEURE"));
        envoyer(post("/api/v1/pilotage/besoins/remontees/{id}/renvoyer", remonteeA), kadiogo, "{\"motif\":\"\"}")
                .andExpect(status().isBadRequest());
        envoyer(post("/api/v1/pilotage/besoins/remontees/{id}/renvoyer", remonteeA), kadiogo,
                "{\"motif\":\"Précisez la section du tour\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("RENVOYE"));
        mvc.perform(get("/api/v1/etat-besoins").with(chefA))
                .andExpect(jsonPath("$.statut").value("RENVOYE"))
                .andExpect(jsonPath("$.motifRenvoi").value("Précisez la section du tour"))
                .andExpect(jsonPath("$.modifiable").value(true));
        String tourLocal = ((List<String>) JsonPath.read(mvc.perform(get("/api/v1/etat-besoins").with(chefA))
                .andReturn().getResponse().getContentAsString(), "$.lignes[?(@.unite == 'pièce')].id")).get(0);
        envoyer(put("/api/v1/etat-besoins/lignes/{id}", tourLocal), chefA, """
                {"categorie":"EQUIPEMENT_OUTILS","domaine":"TECHNIQUE","designation":"Tour parallèle","unite":"pièce",
                 "quantite":3,"priorite":1,"justification":"Entre-pointes 1 000 mm"}""").andExpect(status().isOk());
        envoyer(post("/api/v1/etat-besoins/{a}/transmettre", a.annee), proviseurA, "{}")
                .andExpect(jsonPath("$.statut").value("TRANSMIS"));
        tourA = ligne(remonteeA, kadiogo, "pièce");
        envoyer(put("/api/v1/pilotage/besoins/remontees/{id}/arbitrages", remonteeA), kadiogo,
                "{\"lignes\":[{\"ligneId\":\"" + tourA + "\",\"quantiteRetenue\":2,\"observation\":\"Un tour réparable\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lignes[?(@.id == '" + tourA + "')].retenue").value(2.0))
                .andExpect(jsonPath("$.lignes[?(@.id == '" + tourA + "')].arbitreIci").value(true));

        // ------------------------------------------------------------ transmissions des DP vers la DR
        envoyer(post("/api/v1/pilotage/besoins/transmettre"), kadiogo, "{\"annee\":\"" + libelle + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.transmission.statut").value("TRANSMISE"))
                .andExpect(jsonPath("$.peutArbitrer").value(false));
        envoyer(post("/api/v1/pilotage/besoins/transmettre"), kadiogo, "{\"annee\":\"" + libelle + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BESOINS_DEJA_TRANSMIS"));
        envoyer(put("/api/v1/pilotage/besoins/remontees/{id}/arbitrages", remonteeA), kadiogo,
                "{\"lignes\":[{\"ligneId\":\"" + tourA + "\",\"quantiteRetenue\":1}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BESOINS_DEJA_TRANSMIS"));
        envoyer(post("/api/v1/pilotage/besoins/transmettre"), oubritenga, "{\"annee\":\"" + libelle + "\"}")
                .andExpect(status().isOk());
        // La DR voit les deux lycées ; « Tour parallèle » consolidé malgré la casse et les espaces
        mvc.perform(get("/api/v1/pilotage/besoins").with(regionale))
                .andExpect(jsonPath("$.directions.length()").value(2))
                .andExpect(jsonPath("$.directions[?(@.id == '" + dp1 + "')].statut").value("TRANSMISE"))
                .andExpect(jsonPath("$.etablissements[?(@.id == '" + a.id + "')].statut").value("RECU"))
                .andExpect(jsonPath("$.consolidation[?(@.designation == 'Tour parallèle')].demandee").value(4.0))
                .andExpect(jsonPath("$.consolidation[?(@.designation == 'Tour parallèle')].retenue").value(3.0))
                .andExpect(jsonPath("$.consolidation[?(@.designation == 'Tour parallèle')].etablissements").value(2))
                .andExpect(jsonPath("$.destination").value("Niveau central"));
        // La DR renvoie la transmission de DP2, qui la reprend
        envoyer(post("/api/v1/pilotage/besoins/directions/{id}/renvoyer", dp2), regionale,
                "{\"annee\":\"" + libelle + "\",\"motif\":\"Ajoutez le lycée technique\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.etablissements[?(@.id == '" + b.id + "')].statut").value("EN_COURS"));
        mvc.perform(get("/api/v1/pilotage/besoins").with(oubritenga))
                .andExpect(jsonPath("$.transmission.statut").value("RENVOYEE"))
                .andExpect(jsonPath("$.transmission.motifRenvoi").value("Ajoutez le lycée technique"))
                .andExpect(jsonPath("$.peutTransmettre").value(true));
        envoyer(post("/api/v1/pilotage/besoins/transmettre"), oubritenga, "{\"annee\":\"" + libelle + "\"}")
                .andExpect(status().isOk());
        // La DR arbitre au plus ce que DP1 a retenu
        envoyer(put("/api/v1/pilotage/besoins/remontees/{id}/arbitrages", remonteeA), regionale,
                "{\"lignes\":[{\"ligneId\":\"" + tourA + "\",\"quantiteRetenue\":3}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUANTITE_SUPERIEURE"));
        envoyer(put("/api/v1/pilotage/besoins/remontees/{id}/arbitrages", remonteeA), regionale,
                "{\"lignes\":[{\"ligneId\":\"" + tourA + "\",\"quantiteRetenue\":1}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lignes[?(@.id == '" + tourA + "')].maximum").value(2.0))
                .andExpect(jsonPath("$.lignes[?(@.id == '" + tourA + "')].arbitrages[0].quantite").value(2.0))
                .andExpect(jsonPath("$.peutRenvoyer").value(false));
        // Le super administrateur consulte sans arbitrer
        mvc.perform(get("/api/v1/pilotage/besoins").param("direction", dr.toString()).with(SUPER_ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.peutArbitrer").value(false));
        envoyer(put("/api/v1/pilotage/besoins/remontees/{id}/arbitrages", remonteeA), SUPER_ADMIN,
                "{\"lignes\":[{\"ligneId\":\"" + tourA + "\",\"quantiteRetenue\":1}]}").andExpect(status().isForbidden());
        // Une DP ne remonte pas au-dessus d'elle
        mvc.perform(get("/api/v1/pilotage/besoins").param("direction", dr.toString()).with(kadiogo))
                .andExpect(status().isForbidden());

        // ------------------------------------------------------------ niveau central
        mvc.perform(get("/api/v1/pilotage/besoins").param("annee", libelle).with(central))
                .andExpect(jsonPath("$.perimetre.type").value("PAYS"))
                .andExpect(jsonPath("$.etablissements[?(@.id == '" + a.id + "')].statut").value("EN_COURS"));
        envoyer(post("/api/v1/pilotage/besoins/transmettre"), regionale, "{\"annee\":\"" + libelle + "\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/pilotage/besoins").param("annee", libelle).with(central))
                .andExpect(jsonPath("$.etablissements[?(@.id == '" + a.id + "')].statut").value("RECU"))
                .andExpect(jsonPath("$.peutArbitrer").value(true))
                .andExpect(jsonPath("$.peutTransmettre").value(false));
        envoyer(post("/api/v1/pilotage/besoins/transmettre"), central, "{}").andExpect(status().isForbidden());
        byte[] classeur = mvc.perform(get("/api/v1/pilotage/besoins/export").param("format", "xlsx").with(regionale))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook x = new XSSFWorkbook(new ByteArrayInputStream(classeur))) {
            assertThat(x.getSheet("Besoins")).isNotNull();
            assertThat(x.getSheet("Établissements")).isNotNull();
        }

        // ------------------------------------------------------------ dotations
        envoyer(post("/api/v1/pilotage/dotations"), central, dotation(libelle, "DIRECTION", dp1, null,
                "{\"categorie\":\"MATIERE\",\"designation\":\"Rond d'acier Ø 20\",\"unite\":\"barre\",\"quantite\":10}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESTINATAIRE_HORS_RESSORT"));
        String nationale = id(envoyer(post("/api/v1/pilotage/dotations"), central, dotation(libelle, "DIRECTION", dr,
                null, """
                        {"categorie":"EQUIPEMENT_OUTILS","designation":"Tour parallèle","unite":"pièce","quantite":3,
                         "valeurUnitaire":2500000},
                        {"categorie":"MATIERE","designation":"Rond d'acier Ø 20","unite":"barre","quantite":100}"""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.statut").value("BROUILLON"))
                .andExpect(jsonPath("$.valeur").value(7500000)).andReturn());
        // Un brouillon n'est pas visible du destinataire
        mvc.perform(get("/api/v1/pilotage/dotations/{id}", nationale).with(regionale)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/pilotage/dotations/{id}/envoyer", nationale).with(central))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("ENVOYEE"));
        envoyer(put("/api/v1/pilotage/dotations/{id}", nationale), central, dotation(libelle, "DIRECTION", dr, null,
                "{\"categorie\":\"MATIERE\",\"designation\":\"Acier\",\"unite\":\"barre\",\"quantite\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOTATION_ENVOYEE"));
        String recue = mvc.perform(get("/api/v1/pilotage/dotations").with(regionale)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recues.length()").value(1))
                .andExpect(jsonPath("$.destinataires.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(recue, "$.recues[0].emettrice.nom")).startsWith("Niveau central");
        String detail = mvc.perform(get("/api/v1/pilotage/dotations/{id}", nationale).with(regionale))
                .andExpect(jsonPath("$.peutAccuser").value(true)).andReturn().getResponse().getContentAsString();
        String tourNat = ((List<String>) JsonPath.read(detail, "$.lignes[?(@.unite == 'pièce')].id")).get(0);
        String acierNat = ((List<String>) JsonPath.read(detail, "$.lignes[?(@.unite == 'barre')].id")).get(0);
        // Répartir exige d'avoir accusé réception
        envoyer(post("/api/v1/pilotage/dotations"), regionale, dotation(libelle, "DIRECTION", dp1, nationale,
                "{\"ligneOrigineId\":\"" + tourNat + "\",\"quantite\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORIGINE_INVALIDE"));
        envoyer(post("/api/v1/pilotage/dotations/{id}/accuser", nationale), regionale, """
                {"observations":"Un tour endommagé au transport",
                 "lignes":[{"ligneId":"%s","quantiteRecue":3,"quantiteConforme":2,"observation":"Carter fendu"}]}"""
                .formatted(tourNat)).andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("RECUE"))
                .andExpect(jsonPath("$.peutRepartir").value(true));
        mvc.perform(post("/api/v1/pilotage/dotations/{id}/accuser", nationale).with(regionale)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEJA_ACCUSEE"));
        // Répartition entre les deux DP, bornée par les quantités conformes reçues
        String versDp1 = id(envoyer(post("/api/v1/pilotage/dotations"), regionale, dotation(libelle, "DIRECTION", dp1,
                nationale, "{\"ligneOrigineId\":\"" + tourNat + "\",\"quantite\":1},{\"ligneOrigineId\":\"" + acierNat
                        + "\",\"quantite\":60}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.origineReference").exists()).andReturn());
        envoyer(post("/api/v1/pilotage/dotations"), regionale, dotation(libelle, "DIRECTION", dp2, nationale,
                "{\"ligneOrigineId\":\"" + tourNat + "\",\"quantite\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUANTITE_DEPASSEE"));
        String versDp2 = id(envoyer(post("/api/v1/pilotage/dotations"), regionale, dotation(libelle, "DIRECTION", dp2,
                nationale, "{\"ligneOrigineId\":\"" + tourNat + "\",\"quantite\":1},{\"ligneOrigineId\":\"" + acierNat
                        + "\",\"quantite\":40}")).andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/v1/pilotage/dotations/{id}", nationale).with(regionale))
                .andExpect(jsonPath("$.lignes[?(@.id == '" + tourNat + "')].repartie").value(2.0))
                .andExpect(jsonPath("$.lignes[?(@.id == '" + tourNat + "')].disponible").value(0.0))
                .andExpect(jsonPath("$.repartitions.length()").value(2))
                .andExpect(jsonPath("$.peutRepartir").value(false));
        mvc.perform(post("/api/v1/pilotage/dotations/{id}/envoyer", versDp1).with(regionale)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/pilotage/dotations/{id}/envoyer", versDp2).with(kadiogo))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/pilotage/dotations/{id}", versDp2).with(regionale)).andExpect(status().isNoContent());
        // DP2 ne voit pas la dotation de DP1
        mvc.perform(get("/api/v1/pilotage/dotations/{id}", versDp1).with(oubritenga)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/pilotage/dotations/{id}/accuser", versDp1).with(kadiogo)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        String acierDp1 = ((List<String>) JsonPath.read(mvc.perform(get("/api/v1/pilotage/dotations/{id}", versDp1)
                .with(kadiogo)).andReturn().getResponse().getContentAsString(), "$.lignes[?(@.unite == 'barre')].id"))
                .get(0);
        String tourDp1 = ((List<String>) JsonPath.read(mvc.perform(get("/api/v1/pilotage/dotations/{id}", versDp1)
                .with(kadiogo)).andReturn().getResponse().getContentAsString(), "$.lignes[?(@.unite == 'pièce')].id"))
                .get(0);
        // DP1 dote son lycée, pas celui de DP2
        envoyer(post("/api/v1/pilotage/dotations"), kadiogo, dotation(libelle, "ETABLISSEMENT", b.id, versDp1,
                "{\"ligneOrigineId\":\"" + acierDp1 + "\",\"quantite\":10}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DESTINATAIRE_HORS_RESSORT"));
        String versA = id(envoyer(post("/api/v1/pilotage/dotations"), kadiogo, dotation(libelle, "ETABLISSEMENT", a.id,
                versDp1, "{\"ligneOrigineId\":\"" + tourDp1 + "\",\"quantite\":1},{\"ligneOrigineId\":\"" + acierDp1
                        + "\",\"quantite\":60}")).andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/v1/pilotage/dotations/{id}/envoyer", versA).with(kadiogo)).andExpect(status().isOk());

        // ------------------------------------------------------------ accusé de réception du lycée A
        mvc.perform(get("/api/v1/dotations-recues").with(intendantA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].emettrice.nom").value("DP Kadiogo " + suffixe));
        String detailA = mvc.perform(get("/api/v1/dotations-recues/{id}", versA).with(intendantA))
                .andExpect(jsonPath("$.peutAccuser").value(true)).andReturn().getResponse().getContentAsString();
        String acierRecu = ((List<String>) JsonPath.read(detailA, "$.lignes[?(@.unite == 'barre')].id")).get(0);
        mvc.perform(post("/api/v1/dotations-recues/{id}/accuser", versA).with(censeurA)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        envoyer(post("/api/v1/dotations-recues/{id}/accuser", versA), intendantA, """
                {"lignes":[{"ligneId":"%s","quantiteRecue":60,"quantiteConforme":61}]}""".formatted(acierRecu))
                .andExpect(status().isBadRequest());
        envoyer(post("/api/v1/dotations-recues/{id}/accuser", versA), intendantA, """
                {"observations":"Reçu au magasin",
                 "lignes":[{"ligneId":"%s","quantiteRecue":60,"quantiteConforme":58,"observation":"2 barres tordues"}]}"""
                .formatted(acierRecu)).andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("RECUE"))
                .andExpect(jsonPath("$.accuseePar").value("KAFANDO Marc"));
        mvc.perform(post("/api/v1/dotations-recues/{id}/accuser", versA).with(intendantA)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEJA_ACCUSEE"));
        // Le lycée B ne voit pas la dotation du lycée A ; DP1 suit l'accusé de réception
        mvc.perform(get("/api/v1/dotations-recues/{id}", versA).with(proviseurB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pilotage/dotations").with(kadiogo))
                .andExpect(jsonPath("$.emises[?(@.id == '" + versA + "')].statut").value("RECUE"));
        mvc.perform(get("/api/v1/pilotage/dotations/{id}", versA).with(regionale))
                .andExpect(jsonPath("$.lignes[?(@.id == '" + acierRecu + "')].quantiteConforme").value(58.0));
    }

    // ------------------------------------------------------------------

    private record Lycee(UUID id, UUID proviseur, UUID annee) {
    }

    private Lycee lycee(String code, String nom, UUID direction, String libelle) {
        EtablissementsService.ResultatCreation r = etablissements.creer(code, nom, telephone(), "ADMIN", "Lycée",
                direction);
        ecole = r.etablissement().id();
        UUID annee = dans(() -> annees.creer(libelle, j.minusDays(30), j.plusDays(300))).id();
        return new Lycee(ecole, r.administrateur().utilisateurId(), annee);
    }

    private UUID membre(Role role, String nom, String prenoms) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, nom, prenoms, role));
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel)).findFirst().orElseThrow()
                .utilisateurId();
    }

    private UUID compte(UUID direction) throws Exception {
        String r = mvc.perform(post("/api/v1/plateforme/territoire/directions/{id}/comptes", direction)
                .with(SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"telephone\":\"" + telephone() + "\",\"nom\":\"Compte\",\"prenoms\":\"Direction\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(r, "$.compte.utilisateurId"));
    }

    private String ligne(String remontee, RequestPostProcessor qui, String unite) throws Exception {
        String r = mvc.perform(get("/api/v1/pilotage/besoins/remontees/{id}", remontee).with(qui))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((List<String>) JsonPath.read(r, "$.lignes[?(@.unite == '" + unite + "')].id")).get(0);
    }

    private static String dotation(String annee, String type, UUID destinataire, String origine, String lignes) {
        return "{\"annee\":\"" + annee + "\",\"destinataireType\":\"" + type + "\",\"destinataireId\":\"" + destinataire
                + "\"," + (origine == null ? "" : "\"origineId\":\"" + origine + "\",")
                + "\"objet\":\"Dotation de rentrée\",\"lignes\":[" + lignes + "]}";
    }

    private ResultActions envoyer(MockHttpServletRequestBuilder requete, RequestPostProcessor qui, String corps)
            throws Exception {
        return mvc.perform(requete.with(qui).contentType(MediaType.APPLICATION_JSON).content(corps));
    }

    private static String id(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private static RequestPostProcessor direction(UUID direction, UUID compte) {
        return jwt().jwt(x -> x.subject(compte.toString()).claim("typ_jeton", "ACCES")
                .claim("roles", List.of("DIRECTION")).claim("direction_id", direction.toString()))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_DIRECTION"));
    }

    private static RequestPostProcessor jeton(UUID ecole, UUID utilisateur, String role) {
        return jwt().jwt(x -> x.subject(utilisateur.toString()).claim("tenant_id", ecole.toString())
                .claim("typ_jeton", "ACCES").claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }
}
