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
 * Fiches de progression sur une vraie base PostgreSQL (profil "test") : l'enseignant prépare
 * et soumet, le censeur suit le général (et le technique tant qu'il n'y a pas de chef des
 * travaux), le chef des travaux vise ou renvoie les progressions des matières techniques.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProgressionIntegrationTest {

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
        String suffixe = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        ecole = etablissements.creer("prog-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
    }

    @Test
    void lEnseignantPrepareSoumetEtLeChefDesTravauxViseLesMatieresTechniques() throws Exception {
        UUID technique = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("TECHNIQUE")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F3" + ThreadLocalRandom.current().nextInt(100, 999),
                "Électrotechnique", "Second cycle", "BAC F3", technique)).id();
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "2nde F3", "2nde", null)).id();
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe);
        UUID elec = matiere("ELEC", TypeMatiere.TECHNIQUE, classe);
        dans(() -> periodes.generer(annee.id(), technique));
        dans(() -> annees.ouvrir(annee.id()));

        UUID sanou = enseignant("Sanou", "Paul", classe, elec);
        UUID kabore = enseignant("Kabore", "Awa", classe, maths);
        UUID censeur = membre(Role.CENSEUR, "Zongo", "Ines");
        String fiche = "/api/v1/classes/{c}/matieres/{m}/progression";

        // Sans chef des travaux, le censeur suit toutes les matières
        mvc.perform(get("/api/v1/annees/{a}/progressions", annee.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].matiereCode").value("ELEC"))
                .andExpect(jsonPath("$[0].statut").doesNotExist())
                .andExpect(jsonPath("$[0].enseignant").value("SANOU Paul"));

        // L'enseignant voit sa fiche vide, pas celle d'une matière qui n'est pas la sienne
        mvc.perform(get(fiche, classe, elec).with(jeton(sanou, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").doesNotExist())
                .andExpect(jsonPath("$.modifiable").value(true))
                .andExpect(jsonPath("$.domaine").value("TECHNIQUE"))
                .andExpect(jsonPath("$.enseignant").value("SANOU Paul"));
        mvc.perform(get(fiche, classe, maths).with(jeton(sanou, "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(put(fiche, classe, maths).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(sequences("Algèbre", "10"))).andExpect(status().isForbidden());

        // Soumettre une fiche vide est refusé ; une séquence sans titre aussi
        mvc.perform(post(fiche + "/soumission", classe, elec).with(jeton(sanou, "ENSEIGNANT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FICHE_VIDE"));
        mvc.perform(put(fiche, classe, elec).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(sequences(" ", "10"))).andExpect(status().isBadRequest());

        // Deux séquences, 30 heures prévues
        mvc.perform(put(fiche, classe, elec).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sequences":[
                          {"titre":"Lois de l'électricité","contenu":"Loi d'Ohm, puissance","competences":"C1",
                           "heuresPrevues":12,"semaineDebut":"%s"},
                          {"titre":"Installations domestiques","heuresPrevues":18.5}]}""".formatted(aujourdhui)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("BROUILLON"))
                .andExpect(jsonPath("$.sequences.length()").value(2))
                .andExpect(jsonPath("$.sequences[1].ordre").value(2))
                .andExpect(jsonPath("$.heuresPrevues").value(30.5));
        mvc.perform(post(fiche + "/soumission", classe, elec).with(jeton(sanou, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("SOUMISE"))
                .andExpect(jsonPath("$.modifiable").value(false));
        mvc.perform(put(fiche, classe, elec).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(sequences("Autre", "4"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FICHE_SOUMISE"));

        // Un chef des travaux arrive : il vise le technique, le censeur ne garde que le général
        UUID chef = membre(Role.CHEF_TRAVAUX, "Ouedraogo", "Salif");
        mvc.perform(get("/api/v1/annees/{a}/progressions", annee.id()).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].matiereCode").value("ELEC"))
                .andExpect(jsonPath("$[0].statut").value("SOUMISE"))
                .andExpect(jsonPath("$[0].sequences").value(2));
        mvc.perform(get("/api/v1/annees/{a}/progressions", annee.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].matiereCode").value("MATH"));
        mvc.perform(post(fiche + "/visa", classe, elec).with(jeton(censeur, "CENSEUR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepte\":true}")).andExpect(status().isForbidden());
        mvc.perform(get(fiche, classe, elec).with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visable").value(true))
                .andExpect(jsonPath("$.modifiable").value(false));

        // Renvoi : le commentaire est obligatoire
        mvc.perform(post(fiche + "/visa", classe, elec).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepte\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMENTAIRE_OBLIGATOIRE"));
        mvc.perform(post(fiche + "/visa", classe, elec).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accepte\":false,\"commentaire\":\"Ajoutez les TP d'atelier\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("A_REVOIR"))
                .andExpect(jsonPath("$.visePar").value("OUEDRAOGO Salif"));
        mvc.perform(get("/api/v1/espace-enseignant/progressions").with(jeton(sanou, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].statut").value("A_REVOIR"));

        // Correction, nouvelle soumission, visa ; une modification après visa repasse en brouillon
        mvc.perform(put(fiche, classe, elec).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(sequences("TP atelier : câblage", "20"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("A_REVOIR"));
        mvc.perform(post(fiche + "/soumission", classe, elec).with(jeton(sanou, "ENSEIGNANT")))
                .andExpect(jsonPath("$.statut").value("SOUMISE"));
        mvc.perform(post(fiche + "/visa", classe, elec).with(jeton(sanou, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepte\":true}")).andExpect(status().isForbidden());
        mvc.perform(post(fiche + "/visa", classe, elec).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepte\":true,\"commentaire\":\"Bon travail\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("VISEE"))
                .andExpect(jsonPath("$.commentaireVisa").value("Bon travail"));
        mvc.perform(put(fiche, classe, elec).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(sequences("TP atelier : câblage", "22"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("BROUILLON"))
                .andExpect(jsonPath("$.viseLe").doesNotExist());

        // L'autre enseignant ne voit que sa matière, sans fiche
        mvc.perform(get("/api/v1/espace-enseignant/progressions").with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].matiereCode").value("MATH"))
                .andExpect(jsonPath("$[0].statut").doesNotExist());
    }

    @Test
    void leCahierDeTextesMesureLeRealiseFaceAuPrevu() throws Exception {
        UUID technique = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("TECHNIQUE")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F4" + ThreadLocalRandom.current().nextInt(100, 999),
                "Génie civil", "Second cycle", "BAC F4", technique)).id();
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "1re F4", "1re", null)).id();
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, classe);
        UUID elec = matiere("ELEC", TypeMatiere.TECHNIQUE, classe);
        dans(() -> periodes.generer(annee.id(), technique));
        dans(() -> annees.ouvrir(annee.id()));
        UUID sanou = enseignant("Sanou", "Paul", classe, elec);
        UUID kabore = enseignant("Kabore", "Awa", classe, maths);
        UUID censeur = membre(Role.CENSEUR, "Zongo", "Ines");

        mvc.perform(put("/api/v1/classes/{c}/matieres/{m}/progression", classe, elec).with(jeton(sanou, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sequences":[{"titre":"Lois de l'électricité","heuresPrevues":10},
                                      {"titre":"Installations","heuresPrevues":6}]}""")).andExpect(status().isOk());

        UUID s1 = UUID.randomUUID();
        String seance = """
                {"classeId":"%s","matiereId":"%s","date":"%s","heureDebut":"%s","heureFin":"%s",
                 "sequenceOrdre":%s,"contenu":"%s","travailAFaire":"Exercices 1 à 3"}""";
        mvc.perform(put("/api/v1/cahier-textes/{id}", s1).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classe, elec, aujourdhui, "08:00", "10:00", "1", "Loi d'Ohm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heures").value(2.0))
                .andExpect(jsonPath("$.sequenceTitre").value("Lois de l'électricité"));
        // Renvoi du même identifiant (coupure de réseau, correction) : la même séance est modifiée
        mvc.perform(put("/api/v1/cahier-textes/{id}", s1).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classe, elec, aujourdhui, "08:00", "10:00", "1", "Loi d'Ohm et puissance")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contenu").value("Loi d'Ohm et puissance"));
        // Même cours sous un autre identifiant, cours futur, autre enseignant : refusés
        mvc.perform(put("/api/v1/cahier-textes/{id}", UUID.randomUUID()).with(jeton(sanou, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classe, elec, aujourdhui, "08:00", "09:00", "1", "Doublon")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEANCE_EXISTANTE"));
        mvc.perform(put("/api/v1/cahier-textes/{id}", UUID.randomUUID()).with(jeton(sanou, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classe, elec, aujourdhui.plusDays(1), "08:00", "10:00", "1", "Demain")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATE_FUTURE"));
        mvc.perform(put("/api/v1/cahier-textes/{id}", UUID.randomUUID()).with(jeton(kabore, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classe, elec, aujourdhui, "14:00", "15:00", "null", "Pas ma matière")))
                .andExpect(status().isForbidden());
        // Séquence inconnue (fiche raccourcie depuis une saisie hors connexion) : séance hors séquence
        UUID s2 = UUID.randomUUID();
        mvc.perform(put("/api/v1/cahier-textes/{id}", s2).with(jeton(sanou, "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content(seance.formatted(classe, elec, aujourdhui.minusDays(1), "10:00", "11:30", "9", "Révisions")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequenceOrdre").doesNotExist())
                .andExpect(jsonPath("$.heures").value(1.5));

        // Réalisé face au prévu, dans la fiche et dans le suivi de la direction
        mvc.perform(get("/api/v1/classes/{c}/matieres/{m}/progression", classe, elec).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequences[0].heuresRealisees").value(2.0))
                .andExpect(jsonPath("$.sequences[0].seances").value(1))
                .andExpect(jsonPath("$.sequences[1].heuresRealisees").value(0))
                .andExpect(jsonPath("$.avancement.heuresRealisees").value(3.5))
                .andExpect(jsonPath("$.avancement.heuresHorsSequence").value(1.5))
                .andExpect(jsonPath("$.avancement.seances").value(2))
                .andExpect(jsonPath("$.avancement.derniereSeance").value(aujourdhui.toString()));
        mvc.perform(get("/api/v1/annees/{a}/progressions", annee.id()).with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$[0].matiereCode").value("ELEC"))
                .andExpect(jsonPath("$[0].avancement.seances").value(2))
                .andExpect(jsonPath("$[1].avancement.seances").value(0));

        // Lecture : enseignant et direction ; pas un autre enseignant
        mvc.perform(get("/api/v1/classes/{c}/matieres/{m}/cahier-textes", classe, elec).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value(aujourdhui.toString()))
                .andExpect(jsonPath("$[0].travailAFaire").value("Exercices 1 à 3"));
        mvc.perform(get("/api/v1/classes/{c}/matieres/{m}/cahier-textes", classe, elec).with(jeton(kabore, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        // Suppression : seulement l'enseignant de la matière
        mvc.perform(delete("/api/v1/cahier-textes/{id}", s2).with(jeton(censeur, "CENSEUR"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/cahier-textes/{id}", s2).with(jeton(kabore, "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/cahier-textes/{id}", s2).with(jeton(sanou, "ENSEIGNANT"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/classes/{c}/matieres/{m}/cahier-textes", classe, elec).with(jeton(sanou, "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ------------------------------------------------------------------

    private static String sequences(String titre, String heures) {
        return "{\"sequences\":[{\"titre\":\"" + titre + "\",\"heuresPrevues\":" + heures + "}]}";
    }

    private UUID matiere(String code, TypeMatiere type, UUID classe) {
        UUID id = dans(() -> matieres.creer(code, code, type)).id();
        // Profil technique : chaque matière appartient à un groupe (moyennes par groupe)
        String groupe = type == TypeMatiere.GENERALE ? "Enseignement général" : "Enseignement technique";
        dans(() -> classes.definirMatiere(classe, id, BigDecimal.TWO, groupe, new BigDecimal("4"), null));
        return id;
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
