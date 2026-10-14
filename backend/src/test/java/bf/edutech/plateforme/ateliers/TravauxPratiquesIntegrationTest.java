package bf.edutech.plateforme.ateliers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.enseignants.CategorieEnseignant;
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
 * Travaux pratiques dans les ateliers (v0.47.2) : consignes de sécurité lues avant toute demande, planning (demande,
 * validation, conflit, refus), commande de production, fiche de préparation et mise à disposition, compte rendu de
 * l'enseignant (consommation, panne, quantité produite), bilan avec retour au stock, journal HSE et alerte, annulation.
 */
@SpringBootTest
@ActiveProfiles("test")
class TravauxPratiquesIntegrationTest {

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
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("tp-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
    }

    @Test
    void leChefDAtelierEtLesEnseignantsOrganisentLesTravauxPratiques() throws Exception {
        UUID technique = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("TECHNIQUE")).findFirst()
                .orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F1" + ThreadLocalRandom.current().nextInt(100, 999),
                "Fabrication mécanique", "Second cycle", "BAC F1", technique)).id();
        UUID seconde = dans(() -> classes.creer(annee.id(), filiere, "2nde F1", "2nde", null)).id();
        UUID premiere = dans(() -> classes.creer(annee.id(), filiere, "1re F1", "1re", null)).id();
        UUID techno = matiere("TECHNO", TypeMatiere.TECHNIQUE, seconde);
        UUID tp = matiere("TP-FAB", TypeMatiere.PRATIQUE, seconde);
        UUID tp1 = matiere("TP-FAB1", TypeMatiere.PRATIQUE, premiere);
        UUID maths = matiere("MATH", TypeMatiere.GENERALE, seconde);
        dans(() -> periodes.generer(annee.id(), technique));
        dans(() -> annees.ouvrir(annee.id()));
        Prof sanou = enseignant("Sanou", "Paul", seconde, techno);      // chef d'atelier
        Prof traore = enseignant("Traore", "Ali", seconde, tp);
        Prof diallo = enseignant("Diallo", "Issa", premiere, tp1);
        UUID chef = membre(Role.CHEF_TRAVAUX, "Ouedraogo", "Salif");
        UUID intendant = membre(Role.INTENDANT, "Kafando", "Marc");

        String atelier = id(mvc.perform(post("/api/v1/ateliers").with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"fab\",\"nom\":\"Atelier de fabrication\",\"postes\":20,\"filieres\":[\"%s\"]}"
                        .formatted(filiere))).andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/v1/ateliers/{a}/responsable", atelier).with(jeton(chef, "CHEF_TRAVAUX"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"engagementId\":\"%s\"}".formatted(sanou.engagement())))
                .andExpect(status().isOk());
        String acier = id(mvc.perform(post("/api/v1/catalogue").with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"ACIER-20","designation":"Rond d'acier Ø 20","nature":"MATIERE_OEUVRE","unite":"barre",
                         "prixReference":4500}""")).andExpect(status().isCreated()).andReturn());
        String tour = id(mvc.perform(post("/api/v1/catalogue").with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"TOUR","designation":"Tour parallèle","nature":"EQUIPEMENT","unite":"pièce"}"""))
                .andExpect(status().isCreated()).andReturn());
        String machine = id(mvc.perform(post("/api/v1/ateliers/{a}/equipements", atelier)
                .with(jeton(sanou.compte(), "ENSEIGNANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\"}".formatted(tour))).andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/v1/ateliers/{a}/mouvements", atelier).with(jeton(sanou.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":\"%s\",\"type\":\"ENTREE\",\"quantite\":10}".formatted(acier)))
                .andExpect(status().isCreated());

        // ---- Sécurité : le chef d'atelier fixe les consignes ; l'enseignant les lit avant toute demande
        mvc.perform(put("/api/v1/ateliers/{a}/consignes", atelier).with(jeton(traore.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/ateliers/{a}/consignes", atelier).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"categorie":"EPI","texte":"Blouse, lunettes et chaussures de sécurité obligatoires"},
                                 {"categorie":"ARRET_URGENCE","texte":"Coup de poing d'arrêt près de la porte"},
                                 {"categorie":"MACHINE","equipementId":"%s","texte":"Carter fermé avant la mise en marche"}]"""
                                .formatted(machine)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consignes.length()").value(3));
        String demande = """
                {"classeId":"%s","matiereId":"%s","date":"%s","heureDebut":"%s","heureFin":"%s","titre":"%s",
                 "effectif":24,"besoins":[{"articleId":"%s","quantite":4}]}""";
        LocalDate demain = aujourdhui.plusDays(1);
        mvc.perform(post("/api/v1/ateliers/{a}/seances-tp", atelier).with(jeton(traore.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(seconde, tp, demain, "08:00", "10:00", "Tournage cylindrique", acier)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONSIGNES_A_LIRE"));
        mvc.perform(get("/api/v1/ateliers/{a}/consignes", atelier).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.aLire").value(true));
        mvc.perform(post("/api/v1/ateliers/{a}/consignes/lecture", atelier).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aLire").value(false));
        mvc.perform(get("/api/v1/ateliers/{a}/consignes/lectures", atelier).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$[?(@.engagementId == '%s')].aJour".formatted(traore.engagement())).value(true))
                .andExpect(jsonPath("$[?(@.engagementId == '%s')].aJour".formatted(diallo.engagement())).value(false));

        // ---- Planning : demande, matière générale refusée, validation avec quantité accordée
        mvc.perform(post("/api/v1/ateliers/{a}/seances-tp", atelier).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(seconde, maths, demain, "14:00", "15:00", "Calcul", acier)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MATIERE_GENERALE"));
        String s1 = id(mvc.perform(post("/api/v1/ateliers/{a}/seances-tp", atelier).with(jeton(traore.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(seconde, tp, demain, "08:00", "10:00", "Tournage cylindrique", acier)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("DEMANDEE"))
                .andExpect(jsonPath("$.enseignant").value("TRAORE Ali"))
                .andExpect(jsonPath("$.droits.valider").value(false))
                .andReturn());
        mvc.perform(post("/api/v1/seances-tp/{id}/validation", s1).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/seances-tp/{id}/validation", s1).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accordees\":[{\"articleId\":\"%s\",\"quantite\":3}]}".formatted(acier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("PLANIFIEE"))
                .andExpect(jsonPath("$.besoins[0].accordee").value(3.0))
                .andExpect(jsonPath("$.besoins[0].aAcheter").value(0.0));

        // Une demande sur le même créneau est en conflit ; le chef d'atelier la refuse (l'enseignant est prévenu)
        mvc.perform(post("/api/v1/ateliers/{a}/consignes/lecture", atelier).with(jeton(diallo.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk());
        String s2 = id(mvc.perform(post("/api/v1/ateliers/{a}/seances-tp", atelier).with(jeton(diallo.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demande.formatted(premiere, tp1, demain, "09:00", "11:00", "Fraisage", acier)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conflits.length()").value(1))
                .andReturn());
        mvc.perform(post("/api/v1/seances-tp/{id}/validation", s2).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLIT_ATELIER"));
        mvc.perform(post("/api/v1/seances-tp/{id}/refus", s2).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motif\":\"Atelier pris : proposez 10 h\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("REFUSEE"));
        mvc.perform(get("/api/v1/messages/non-lus").with(jeton(diallo.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.nombre").value(1));

        // ---- Production : commande publique coordonnée par le chef d'atelier
        mvc.perform(post("/api/v1/productions").with(jeton(traore.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"atelierId":"%s","client":"Mairie","typeClient":"PUBLIC","objet":"Tables-bancs",
                         "quantite":20,"unite":"pièce"}""".formatted(atelier))).andExpect(status().isForbidden());
        String production = id(mvc.perform(post("/api/v1/productions").with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"atelierId":"%s","client":"Mairie de Koudougou","typeClient":"PUBLIC",
                                 "objet":"Tables-bancs","quantite":20,"unite":"pièce","montant":1000000,
                                 "echeance":"%s"}""".formatted(atelier, aujourdhui.plusDays(60))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("PR-" + aujourdhui.getYear() + "-001"))
                .andReturn());

        // ---- Séance du jour planifiée directement par le chef d'atelier, rattachée à la production
        String s3 = id(mvc.perform(post("/api/v1/ateliers/{a}/seances-tp", atelier).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"classeId":"%s","matiereId":"%s","date":"%s","heureDebut":"14:00","heureFin":"16:00",
                                 "titre":"Pieds de tables-bancs","productionId":"%s",
                                 "besoins":[{"articleId":"%s","quantite":2}]}"""
                                .formatted(seconde, tp, aujourdhui, production, acier)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("PLANIFIEE"))
                .andExpect(jsonPath("$.enseignant").value("TRAORE Ali"))
                .andReturn());

        // ---- Mise à disposition : sortie du stock
        mvc.perform(post("/api/v1/seances-tp/{id}/preparation", s1).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/seances-tp/{id}/preparation", s1).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"observations\":\"Clés remises\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.besoins[0].servie").value(3.0));
        mvc.perform(post("/api/v1/seances-tp/{id}/preparation", s3).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.besoins[0].servie").value(2.0));
        stock(atelier, sanou, 5.0);

        // ---- Compte rendu : pas avant la séance ; consommation bornée ; panne rattachée ; quantité produite
        String cr = """
                {"nettoyage":true,"rangement":true,"epiRespectes":false,"quantiteProduite":4,
                 "observations":"Deux élèves sans lunettes","consommation":[{"articleId":"%s","quantite":%s}],
                 "pannes":[{"equipementId":"%s","description":"Embrayage qui patine"}]}""";
        mvc.perform(post("/api/v1/seances-tp/{id}/compte-rendu", s1).with(jeton(traore.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(cr.formatted(acier, "1", machine)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEANCE_A_VENIR"));
        mvc.perform(post("/api/v1/seances-tp/{id}/compte-rendu", s3).with(jeton(diallo.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(cr.formatted(acier, "1", machine)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/seances-tp/{id}/compte-rendu", s3).with(jeton(traore.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(cr.formatted(acier, "3", machine)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONSOMMATION_EXCESSIVE"));
        mvc.perform(post("/api/v1/seances-tp/{id}/compte-rendu", s3).with(jeton(traore.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(cr.formatted(acier, "1.5", machine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("REALISEE"))
                .andExpect(jsonPath("$.epiRespectes").value(false));
        mvc.perform(get("/api/v1/ateliers/{a}/equipements", atelier).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$[0].etat").value("EN_PANNE"));

        // ---- Bilan : la matière non utilisée revient au stock, une seule fois
        mvc.perform(post("/api/v1/seances-tp/{id}/bilan", s3).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.besoins[0].rendue").value(0.5));
        mvc.perform(post("/api/v1/seances-tp/{id}/bilan", s3).with(jeton(sanou.compte(), "ENSEIGNANT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BILAN_DEJA_FAIT"));
        stock(atelier, sanou, 5.5);
        mvc.perform(get("/api/v1/productions/{id}", production).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantiteProduite").value(4.0))
                .andExpect(jsonPath("$.seances").value(1));

        // ---- HSE : le chef d'atelier consigne un danger imminent ; le chef des travaux est alerté
        mvc.perform(post("/api/v1/ateliers/{a}/hse", atelier).with(jeton(traore.compte(), "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gravite\":\"OBSERVATION\",\"description\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ateliers/{a}/hse", atelier).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"seanceId":"%s","gravite":"DANGER_IMMINENT",
                                 "description":"Élève sans lunettes au tour","mesures":"Machine arrêtée, rappel des consignes"}"""
                                .formatted(s3)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.gravite").value("DANGER_IMMINENT"));
        mvc.perform(get("/api/v1/messages/non-lus").with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(jsonPath("$.nombre").value(1));
        mvc.perform(get("/api/v1/ateliers/{a}/hse", atelier).with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(1));

        // ---- Plannings : l'enseignant, l'atelier, la direction ; génération depuis l'emploi du temps
        mvc.perform(get("/api/v1/seances-tp/miennes").with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/ateliers/{a}/seances-tp", atelier).param("du", aujourdhui.toString())
                        .param("au", demain.toString()).with(jeton(diallo.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/v1/seances-tp").param("du", aujourdhui.toString()).param("au", demain.toString())
                        .with(jeton(chef, "CHEF_TRAVAUX")))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/v1/seances-tp").with(jeton(traore.compte(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ateliers/{a}/seances-tp/generation", atelier).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"du\":\"%s\",\"au\":\"%s\"}".formatted(aujourdhui, aujourdhui.plusDays(6))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.creees").value(0));
        mvc.perform(get("/api/v1/seances-tp/achats").with(jeton(intendant, "INTENDANT"))).andExpect(status().isOk());

        // ---- Annulation : la séance préparée ne s'annule que par le chef d'atelier, qui remet la matière en stock
        mvc.perform(post("/api/v1/seances-tp/{id}/annulation", s1).with(jeton(traore.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motif\":\"Sortie pédagogique\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEANCE_PREPAREE"));
        mvc.perform(post("/api/v1/seances-tp/{id}/annulation", s1).with(jeton(sanou.compte(), "ENSEIGNANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motif\":\"Sortie pédagogique\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("ANNULEE"));
        stock(atelier, sanou, 8.5);
    }

    private void stock(String atelier, Prof responsable, double attendu) throws Exception {
        mvc.perform(get("/api/v1/ateliers/{a}/stock", atelier).with(jeton(responsable.compte(), "ENSEIGNANT")))
                .andExpect(jsonPath("$[0].quantite").value(attendu));
    }

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
        CategorieEnseignant categorie = CategorieEnseignant.TECHNIQUE;
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, nom, prenoms, Sexe.M, null,
                TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null, categorie))).enseignant().engagementId();
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
