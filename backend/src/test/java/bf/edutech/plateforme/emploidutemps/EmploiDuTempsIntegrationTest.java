package bf.edutech.plateforme.emploidutemps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.DocumentContext;
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
 * Emplois du temps sur une vraie base PostgreSQL (profil "test") : le censeur fixe la grille
 * et place les matières générales, le chef des travaux les matières techniques et pratiques ;
 * les conflits sont refusés, y compris avec les heures d'un vacataire dans un autre établissement.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmploiDuTempsIntegrationTest {

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

    /** Un établissement de test avec son année, sa filière technique et ses comptes. */
    private final class Ecole {
        final UUID id;
        final AnneeVue annee;
        final UUID filiere;
        final UUID technique;

        Ecole(String prefixe) {
            String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            id = etablissements.creer(prefixe + "-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1),
                    "ADMIN", "Test").etablissement().id();
            dans(() -> profils.initialiserProfilsTypes());
            technique = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("TECHNIQUE")).findFirst()
                    .orElseThrow().id();
            int an = aujourdhui.getYear();
            annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
            filiere = dans(() -> filieres.creer("F3" + ThreadLocalRandom.current().nextInt(100, 999),
                    "Électrotechnique", "Second cycle", "BAC F3", technique)).id();
        }

        <T> T dans(Supplier<T> action) {
            return TenantContext.executerPour(id, action);
        }

        UUID classe(String code) {
            return dans(() -> classes.creer(annee.id(), filiere, code, code.substring(0, code.indexOf(' ')), null)).id();
        }

        UUID matiere(String code, TypeMatiere type) {
            return dans(() -> matieres.creer(code, code, type)).id();
        }

        void programme(UUID classe, UUID matiere, TypeMatiere type, String heures) {
            String groupe = type == TypeMatiere.GENERALE ? "Enseignement général" : "Enseignement technique";
            dans(() -> classes.definirMatiere(classe, matiere, BigDecimal.TWO, groupe, new BigDecimal(heures), null));
        }

        UUID titulaire(String tel, String nom, String prenoms) {
            return dans(() -> enseignants.engager(new DonneesEngagement(tel, null, nom, prenoms, Sexe.M, null,
                    TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        }

        void affecter(UUID classe, UUID matiere, UUID engagement) {
            dans(() -> enseignants.affecter(classe, matiere, engagement));
        }

        UUID membre(Role role, String nom, String prenoms) {
            String tel = telephone();
            dans(() -> membres.ajouter(tel, nom, prenoms, role));
            return compte(tel, role);
        }

        UUID compte(String tel, Role role) {
            return dans(() -> membres.lister()).stream()
                    .filter(m -> m.role() == role && m.telephone().endsWith(tel)).findFirst().orElseThrow()
                    .utilisateurId();
        }

        RequestPostProcessor jeton(UUID utilisateur, String role) {
            return jwt().jwt(j -> j.subject(utilisateur.toString()).claim("tenant_id", id.toString())
                    .claim("typ_jeton", "ACCES").claim("roles", List.of(role)))
                    .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
        }

        ResultActions grille(UUID qui, String role, String creneaux) throws Exception {
            return mvc.perform(put("/api/v1/annees/{a}/creneaux", annee.id()).with(jeton(qui, role))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"creneaux\":[" + creneaux + "]}"));
        }

        ResultActions placer(UUID qui, String role, UUID seance, String donnees) throws Exception {
            return mvc.perform(put("/api/v1/seances-emploi/{id}", seance).with(jeton(qui, role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"anneeId\":\"" + annee.id() + "\"," + donnees + "}"));
        }

        DocumentContext emploi(UUID qui, String role) throws Exception {
            return lire(mvc.perform(get("/api/v1/annees/{a}/emploi-du-temps", annee.id()).with(jeton(qui, role)))
                    .andExpect(status().isOk()));
        }
    }

    @Test
    void leCenseurEtLeChefDesTravauxSePartagentLEmploiDuTemps() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        Ecole e = new Ecole("edt");
        UUID seconde = e.classe("2nde F3");
        UUID premiere = e.classe("1re F3");
        UUID maths = e.matiere("MATH", TypeMatiere.GENERALE);
        UUID francais = e.matiere("FRAN", TypeMatiere.GENERALE);
        UUID elec = e.matiere("ELEC", TypeMatiere.TECHNIQUE);
        UUID tp = e.matiere("TPEL", TypeMatiere.PRATIQUE);
        for (UUID c : List.of(seconde, premiere)) {
            e.programme(c, maths, TypeMatiere.GENERALE, "4");
            e.programme(c, francais, TypeMatiere.GENERALE, "3");
            e.programme(c, elec, TypeMatiere.TECHNIQUE, "2");
            e.programme(c, tp, TypeMatiere.PRATIQUE, "4");
        }
        String telKabore = telephone();
        UUID kabore = e.titulaire(telKabore, "Kabore", "Awa");
        UUID zida = e.titulaire(telephone(), "Zida", "Marie");
        UUID sanou = e.titulaire(telephone(), "Sanou", "Paul");
        UUID traore = e.titulaire(telephone(), "Traore", "Ali");
        UUID yameogo = e.titulaire(telephone(), "Yameogo", "Jean");
        for (UUID c : List.of(seconde, premiere)) {
            e.affecter(c, maths, kabore);
            e.affecter(c, francais, zida);
            e.affecter(c, elec, sanou);
        }
        e.affecter(seconde, tp, traore);
        e.affecter(premiere, tp, yameogo);
        e.dans(() -> periodes.generer(e.annee.id(), e.technique));
        e.dans(() -> annees.ouvrir(e.annee.id()));
        UUID censeur = e.membre(Role.CENSEUR, "Zongo", "Ines");
        UUID chef = e.membre(Role.CHEF_TRAVAUX, "Ouedraogo", "Salif");
        UUID compteKabore = e.compte(telKabore, Role.ENSEIGNANT);

        // Atelier d'électricité de la filière (créé par le chef des travaux)
        String atelier = lire(mvc.perform(post("/api/v1/ateliers").with(e.jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"elec\",\"nom\":\"Atelier d'électricité\",\"postes\":24,\"filieres\":[\"%s\"]}"
                        .formatted(e.filiere)))
                .andExpect(status().isCreated())).read("$.id");

        // Grille : le censeur la fixe, pas le chef des travaux ; les chevauchements sont refusés
        String matin = "{\"heureDebut\":\"%s\",\"heureFin\":\"%s\",\"jours\":[1,2,3,4,5,6]}";
        String apresMidi = "{\"heureDebut\":\"%s\",\"heureFin\":\"%s\",\"jours\":[1,2,4,5]}";
        String grille = String.join(",", matin.formatted("07:00", "08:00"), matin.formatted("08:00", "09:00"),
                matin.formatted("09:00", "10:00"), matin.formatted("10:15", "11:15"), matin.formatted("11:15", "12:15"),
                apresMidi.formatted("15:00", "16:00"), apresMidi.formatted("16:00", "17:00"));
        e.grille(chef, "CHEF_TRAVAUX", grille).andExpect(status().isForbidden());
        e.grille(censeur, "CENSEUR", matin.formatted("07:00", "08:30") + "," + matin.formatted("08:00", "09:00"))
                .andExpect(status().isBadRequest());
        e.grille(censeur, "CENSEUR", grille).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[5].jours.length()").value(4)).andExpect(jsonPath("$[0].minutes").value(60));

        DocumentContext vue = e.emploi(chef, "CHEF_TRAVAUX");
        assertThat(vue.read("$.droits.domaines", List.class)).containsExactly("TECHNIQUE");
        assertThat(vue.read("$.droits.grille", Boolean.class)).isFalse();
        assertThat(vue.read("$.jours", List.class)).containsExactly(1, 2, 3, 4, 5, 6);
        String c7 = vue.read("$.creneaux[0].id");
        String c8 = vue.read("$.creneaux[1].id");
        String c9 = vue.read("$.creneaux[2].id");
        String c15 = vue.read("$.creneaux[5].id");

        // Le censeur place les mathématiques, pas le chef des travaux
        String mathsSeconde = "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\""
                .formatted(seconde, maths, c7);
        e.placer(chef, "CHEF_TRAVAUX", UUID.randomUUID(), mathsSeconde).andExpect(status().isForbidden());
        UUID s1 = UUID.randomUUID();
        e.placer(censeur, "CENSEUR", s1, mathsSeconde).andExpect(status().isOk())
                .andExpect(jsonPath("$.seances.length()").value(1))
                .andExpect(jsonPath("$.seances[0].engagementId").value(kabore.toString()));
        // Renvoi de la même séance : pas de doublon
        e.placer(censeur, "CENSEUR", s1, mathsSeconde).andExpect(jsonPath("$.seances.length()").value(1));
        // Même enseignant, même heure, autre classe : refusé
        e.placer(censeur, "CENSEUR", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\""
                .formatted(premiere, maths, c7)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLIT_EMPLOI_DU_TEMPS"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("KABORE Awa a déjà cours en 2nde F3")));
        // Pas de cours le samedi après-midi
        e.placer(censeur, "CENSEUR", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":6,\"creneauId\":\"%s\""
                .formatted(premiere, maths, c15)).andExpect(status().isBadRequest());

        // Le chef des travaux place le TP : classe déjà prise à 7 h, libre à 8 h (dans l'atelier)
        e.placer(chef, "CHEF_TRAVAUX", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\""
                .formatted(seconde, tp, c7)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("2nde F3 a déjà MATH")));
        UUID tp1 = UUID.randomUUID();
        e.placer(chef, "CHEF_TRAVAUX", tp1, "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\",\"atelierId\":\"%s\""
                .formatted(seconde, tp, c8, atelier)).andExpect(status().isOk());
        // Demi-classes : le groupe 1 au TP dans l'atelier, le groupe 2 en électrotechnique ailleurs
        e.placer(chef, "CHEF_TRAVAUX", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\",\"atelierId\":\"%s\",\"groupe\":\"G1\""
                .formatted(seconde, tp, c9, atelier)).andExpect(status().isOk());
        e.placer(chef, "CHEF_TRAVAUX", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\",\"atelierId\":\"%s\",\"groupe\":\"G2\""
                .formatted(seconde, elec, c9, atelier)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("L'atelier ELEC accueille déjà")));
        e.placer(chef, "CHEF_TRAVAUX", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\",\"groupe\":\"G2\",\"salle\":\"Salle 4\""
                .formatted(seconde, elec, c9)).andExpect(status().isOk())
                .andExpect(jsonPath("$.classes[1].code").value("2nde F3"))
                .andExpect(jsonPath("$.classes[1].groupes.length()").value(2));
        // Le censeur ne retire pas une séance de TP
        mvc.perform(delete("/api/v1/seances-emploi/{id}", tp1).with(e.jeton(censeur, "CENSEUR")))
                .andExpect(status().isForbidden());

        // Génération par le chef des travaux : seulement ses matières
        DocumentContext r = lire(mvc.perform(post("/api/v1/annees/{a}/emploi-du-temps/generation", e.annee.id())
                .with(e.jeton(chef, "CHEF_TRAVAUX")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()));
        assertThat(r.read("$.manques", List.class)).isEmpty();
        assertThat(r.read("$.seancesPlacees", Integer.class)).isPositive();
        assertThat(r.read("$.emploi.seances[?(@.domaine == 'GENERAL')].id", List.class)).containsExactly(s1.toString());
        // Le censeur ne génère pas les matières techniques quand un chef des travaux est en fonction
        mvc.perform(post("/api/v1/annees/{a}/emploi-du-temps/generation", e.annee.id()).with(e.jeton(censeur, "CENSEUR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"domaines\":[\"TECHNIQUE\"]}"))
                .andExpect(status().isForbidden());
        r = lire(mvc.perform(post("/api/v1/annees/{a}/emploi-du-temps/generation", e.annee.id())
                .with(e.jeton(censeur, "CENSEUR"))).andExpect(status().isOk()));
        assertThat(r.read("$.manques", List.class)).isEmpty();

        // Tout est couvert, sans conflit ; le TP d'une classe est dans l'atelier de sa filière
        vue = e.emploi(censeur, "CENSEUR");
        assertThat(vue.read("$.conflits", List.class)).isEmpty();
        List<Map<String, Object>> classesVues = vue.read("$.classes");
        for (Map<String, Object> c : classesVues) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> programme = (List<Map<String, Object>>) c.get("matieres");
            for (Map<String, Object> m : programme) {
                assertThat((Integer) m.get("minutesPlacees")).as(c.get("code") + " " + m.get("code"))
                        .isGreaterThanOrEqualTo((Integer) m.get("minutesPrevues"));
            }
        }
        List<String> ateliersTp = vue.read("$.seances[?(@.classeId == '%s' && @.matiereId == '%s')].atelierId"
                .formatted(premiere, tp));
        assertThat(ateliersTp).hasSize(4).containsOnly(atelier);

        // Avant publication, l'enseignant ne voit rien ; le chef des travaux ne publie pas
        mvc.perform(get("/api/v1/espace-enseignant/emploi-du-temps").with(e.jeton(compteKabore, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publieLe").doesNotExist())
                .andExpect(jsonPath("$.seances.length()").value(0));
        mvc.perform(post("/api/v1/annees/{a}/emploi-du-temps/publication", e.annee.id()).with(e.jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/annees/{a}/emploi-du-temps/publication", e.annee.id()).with(e.jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publieLe").exists());
        mvc.perform(get("/api/v1/espace-enseignant/emploi-du-temps").with(e.jeton(compteKabore, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publieLe").exists())
                .andExpect(jsonPath("$.seances.length()").value(8));

        // Exports : une classe en PDF, un enseignant en Excel, l'enseignant son propre emploi du temps
        mvc.perform(get("/api/v1/annees/{a}/emploi-du-temps/export", e.annee.id()).param("format", "pdf")
                .param("classe", seconde.toString()).with(e.jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(get("/api/v1/annees/{a}/emploi-du-temps/export", e.annee.id()).param("format", "xlsx")
                .param("enseignant", kabore.toString()).with(e.jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/espace-enseignant/emploi-du-temps/export").param("format", "pdf")
                .with(e.jeton(compteKabore, "ENSEIGNANT"))).andExpect(status().isOk());

        // Une heure de la grille utilisée ne peut pas disparaître
        e.grille(censeur, "CENSEUR", matin.formatted("07:00", "08:00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CRENEAU_UTILISE"));
        // Décaler toute la matinée d'un quart d'heure garde les séances
        StringBuilder decalee = new StringBuilder();
        String[][] heures = { { "07:15", "08:15" }, { "08:15", "09:15" }, { "09:15", "10:15" }, { "10:30", "11:30" },
                { "11:30", "12:30" }, { "15:00", "16:00" }, { "16:00", "17:00" } };
        for (int i = 0; i < heures.length; i++) {
            if (i > 0) {
                decalee.append(',');
            }
            decalee.append("{\"id\":\"%s\",\"heureDebut\":\"%s\",\"heureFin\":\"%s\",\"jours\":%s}"
                    .formatted(vue.read("$.creneaux[" + i + "].id"), heures[i][0], heures[i][1],
                            vue.read("$.creneaux[" + i + "].jours", List.class)));
        }
        e.grille(censeur, "CENSEUR", decalee.toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].heureDebut").value(Matchers.startsWith("07:15")));
        assertThat(e.emploi(censeur, "CENSEUR").read("$.seances", List.class))
                .hasSameSizeAs(vue.read("$.seances", List.class));
    }

    @Test
    void unVacataireNEstJamaisPlaceAuxHeuresOuIlEnseigneDansUnAutreEtablissement() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        Ecole lycee = new Ecole("edta");
        Ecole cfp = new Ecole("edtb");
        String tel = telephone();

        // Au lycée : titulaire, mathématiques le lundi de 8 h à 9 h
        UUID classeLycee = lycee.classe("Tle F3");
        UUID mathsLycee = lycee.matiere("MATH", TypeMatiere.GENERALE);
        lycee.programme(classeLycee, mathsLycee, TypeMatiere.GENERALE, "1");
        UUID engagementLycee = lycee.titulaire(tel, "Sanou", "Paul");
        lycee.affecter(classeLycee, mathsLycee, engagementLycee);
        UUID censeurLycee = lycee.membre(Role.CENSEUR, "Zongo", "Ines");
        lycee.grille(censeurLycee, "CENSEUR", "{\"heureDebut\":\"08:00\",\"heureFin\":\"09:00\",\"jours\":[1]}")
                .andExpect(status().isOk());
        String creneauLycee = lycee.emploi(censeurLycee, "CENSEUR").read("$.creneaux[0].id");
        lycee.placer(censeurLycee, "CENSEUR", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\""
                .formatted(classeLycee, mathsLycee, creneauLycee)).andExpect(status().isOk());

        // Au CFP : vacataire (invitation acceptée), grille décalée d'une demi-heure
        UUID invitation = cfp.dans(() -> enseignants.engager(new DonneesEngagement(tel, null, "Sanou", "Paul", Sexe.M,
                null, TypeEngagement.VACATAIRE, aujourdhui.minusDays(30), aujourdhui.plusDays(300),
                new BigDecimal("2500")))).enseignant().engagementId();
        UUID compte = lycee.compte(tel, Role.ENSEIGNANT);
        mvc.perform(post("/api/v1/moi/invitations/{id}/acceptation", invitation).with(lycee.jeton(compte, "ENSEIGNANT")))
                .andExpect(status().isOk());
        UUID classeCfp = cfp.classe("CAP 1");
        UUID mathsCfp = cfp.matiere("MATH", TypeMatiere.GENERALE);
        cfp.programme(classeCfp, mathsCfp, TypeMatiere.GENERALE, "1");
        cfp.affecter(classeCfp, mathsCfp, invitation);
        UUID censeurCfp = cfp.membre(Role.CENSEUR, "Kafando", "Marc");
        cfp.grille(censeurCfp, "CENSEUR", "{\"heureDebut\":\"08:30\",\"heureFin\":\"09:30\",\"jours\":[1]},"
                + "{\"heureDebut\":\"10:00\",\"heureFin\":\"11:00\",\"jours\":[1]}").andExpect(status().isOk());
        DocumentContext vue = cfp.emploi(censeurCfp, "CENSEUR");
        assertThat(vue.read("$.occupationsAilleurs", List.class)).hasSize(1);
        assertThat(vue.read("$.occupationsAilleurs[0].heureDebut", String.class)).startsWith("08:00");
        assertThat(vue.jsonString()).doesNotContain("Tle F3").doesNotContain(lycee.id.toString());

        // 8 h 30 chevauche son cours au lycée : refusé, sans dire où il enseigne
        cfp.placer(censeurCfp, "CENSEUR", UUID.randomUUID(), "\"classeId\":\"%s\",\"matiereId\":\"%s\",\"jour\":1,\"creneauId\":\"%s\""
                .formatted(classeCfp, mathsCfp, vue.read("$.creneaux[0].id")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("SANOU Paul a déjà cours dans un autre établissement le lundi à 08h30"));

        // La génération le place à 10 h
        DocumentContext r = lire(mvc.perform(post("/api/v1/annees/{a}/emploi-du-temps/generation", cfp.annee.id())
                .with(cfp.jeton(censeurCfp, "CENSEUR"))).andExpect(status().isOk()));
        assertThat(r.read("$.seancesPlacees", Integer.class)).isEqualTo(1);
        assertThat(r.read("$.emploi.seances[0].creneauId", String.class))
                .isEqualTo(vue.read("$.creneaux[1].id", String.class));
    }

    private static DocumentContext lire(ResultActions r) throws Exception {
        return JsonPath.parse(r.andReturn().getResponse().getContentAsString());
    }

    private static String telephone() {
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }
}
