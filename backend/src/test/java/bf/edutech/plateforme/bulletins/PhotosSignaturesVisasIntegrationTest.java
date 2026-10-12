package bf.edutech.plateforme.bulletins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
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
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.EvaluationsService;
import bf.edutech.plateforme.evaluations.TypeEvaluation;
import bf.edutech.plateforme.evaluations.Vues.EvaluationVue;
import bf.edutech.plateforme.evaluations.Vues.SaisieNote;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * v0.44 : photos d'identité (élèves, personnel), cartes scolaires et professionnelles, signature des
 * enseignants et visa de leur matière sur les bulletins.
 */
@SpringBootTest
@ActiveProfiles("test")
class PhotosSignaturesVisasIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private EvaluationsService evaluations;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private UUID ecole;
    private AnneeVue annee;

    @Test
    void photosCartesSignatureEtVisasJusquALaPublication() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("vis-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN", "Test")
                .etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        int an = aujourdhui.getYear();
        annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30), aujourdhui.plusDays(300)));
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst().orElseThrow().id();
        UUID filiere = dans(() -> filieres.creer("F" + ThreadLocalRandom.current().nextInt(1000, 9999), "Générale", "Cycle", "BEPC", general)).id();
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "6e A", "6e", null)).id();
        UUID maths = matiere("MATH", classe);
        UUID anglais = matiere("ANG", classe);
        matiere("FR", classe); // sans enseignant : ne compte pas parmi les matières à viser
        List<PeriodeVue> trimestres = dans(() -> periodes.generer(annee.id(), general));
        dans(() -> annees.ouvrir(annee.id()));
        UUID trimestre = trimestres.stream().filter(t -> !aujourdhui.isBefore(t.debut()) && !aujourdhui.isAfter(t.fin()))
                .findFirst().orElseThrow().id();
        UUID eleveAwa = dans(() -> eleves.creer(new DonneesEleve(null, "OUEDRAOGO", "Awa", Sexe.F, LocalDate.of(2012, 3, 4),
                null, null, null, null), List.of())).eleve().id();
        UUID awa = dans(() -> inscriptions.inscrire(eleveAwa, classe, false, null)).id();

        String telProf = telephone();
        UUID engagementProf = dans(() -> enseignants.engager(new DonneesEngagement(telProf, null, "SANOU", "Paul", Sexe.M,
                null, TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        dans(() -> enseignants.affecter(classe, maths, engagementProf));
        UUID prof = compte(telProf, Role.ENSEIGNANT);
        String telAutre = telephone();
        UUID engagementAutre = dans(() -> enseignants.engager(new DonneesEngagement(telAutre, null, "ZONGO", "Ali", Sexe.M,
                null, TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant().engagementId();
        dans(() -> enseignants.affecter(classe, anglais, engagementAutre));
        UUID autre = compte(telAutre, Role.ENSEIGNANT);
        String telEtranger = telephone();
        dans(() -> enseignants.engager(new DonneesEngagement(telEtranger, null, "KABORE", "Issa", Sexe.M,
                null, TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null)));
        UUID etranger = compte(telEtranger, Role.ENSEIGNANT);
        UUID secretaire = membre(Role.SECRETARIAT);
        UUID censeur = membre(Role.CENSEUR);

        // ---- Photo de l'élève : le secrétariat la dépose ; les enseignants de la classe la voient, pas les autres
        mvc.perform(multipart("/api/v1/eleves/{id}/photo", eleveAwa).file(fichier(jpeg(1200, 1600)))
                .with(jeton(prof, "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/eleves/{id}/photo", eleveAwa)
                .file(new MockMultipartFile("fichier", "photo.txt", "text/plain", "pas une image".getBytes()))
                .with(jeton(secretaire, "SECRETARIAT"))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/eleves/{id}/photo", eleveAwa).file(fichier(jpeg(1200, 1600)))
                .with(jeton(secretaire, "SECRETARIAT"))).andExpect(status().isNoContent());
        byte[] miniature = mvc.perform(get("/api/v1/eleves/{id}/photo", eleveAwa).param("miniature", "true")
                        .with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage mini = ImageIO.read(new java.io.ByteArrayInputStream(miniature));
        assertThat(mini.getWidth()).isLessThanOrEqualTo(96);
        assertThat(mini.getHeight()).isLessThanOrEqualTo(120);
        mvc.perform(get("/api/v1/eleves/{id}/photo", eleveAwa).with(jeton(etranger, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/classes/{id}/photos", classe).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value(eleveAwa.toString()));
        mvc.perform(get("/api/v1/classes/{id}/photos", classe).with(jeton(etranger, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        // Liste de la recherche des élèves (v0.44.1) : qui a une photo parmi la page affichée
        mvc.perform(get("/api/v1/eleves/photos").param("ids", eleveAwa + "," + UUID.randomUUID())
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(eleveAwa.toString()));
        mvc.perform(get("/api/v1/eleves/photos").param("ids", eleveAwa.toString()).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        // ---- Photo du personnel : chacun dépose la sienne ; le personnel administratif la voit
        mvc.perform(multipart("/api/v1/personnes/{id}/photo", prof).file(fichier(jpeg(600, 800))).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isNoContent());
        mvc.perform(multipart("/api/v1/personnes/{id}/photo", prof).file(fichier(jpeg(600, 800))).with(jeton(autre, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/personnes/{id}/photo", prof).with(jeton(censeur, "CENSEUR"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/personnes/{id}/photo", prof).with(jeton(autre, "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/personnes/photos").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value(prof.toString()));

        // ---- Cartes scolaires et professionnelles
        byte[] cartes = mvc.perform(get("/api/v1/classes/{id}/cartes", classe).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(texte(cartes)).contains("CARTE D'IDENTITÉ SCOLAIRE").contains("OUEDRAOGO").contains("6e A");
        mvc.perform(get("/api/v1/classes/{id}/cartes", classe).with(jeton(prof, "ENSEIGNANT"))).andExpect(status().isForbidden());
        byte[] professionnelles = mvc.perform(get("/api/v1/personnes/cartes").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(texte(professionnelles)).contains("CARTE PROFESSIONNELLE").contains("SANOU").contains("Enseignant");

        // ---- Signature de l'enseignant : la sienne seulement
        mvc.perform(get("/api/v1/moi/signature/etat").with(jeton(prof, "ENSEIGNANT")))
                .andExpect(jsonPath("$.presente").value(false));
        mvc.perform(multipart("/api/v1/moi/signature").file(fichier(png(1200, 400))).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.presente").value(true));
        mvc.perform(get("/api/v1/moi/signature").with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/v1/moi/signature").with(jeton(autre, "ENSEIGNANT"))).andExpect(status().isNotFound());

        // ---- Notes, appréciation, puis visa
        EvaluationVue devoir = commeCenseur(() -> evaluations.creer(classe, maths, trimestre, "Devoir", TypeEvaluation.DEVOIR,
                aujourdhui, null, null));
        commeCenseur(() -> evaluations.saisir(devoir.id(), List.of(new SaisieNote(awa, new BigDecimal("15"), false))));
        String appreciation = "{\"matiereId\":\"%s\",\"appreciations\":[{\"inscriptionId\":\"%s\",\"texte\":\"%s\"}]}";
        mvc.perform(put("/api/v1/classes/{c}/periodes/{p}/appreciations", classe, trimestre).with(jeton(prof, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content(appreciation.formatted(maths, awa, "Bon travail")))
                .andExpect(status().isOk());

        String visa = "/api/v1/classes/{c}/periodes/{p}/matieres/{m}/visa";
        mvc.perform(post(visa, classe, trimestre, maths).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISA_PERIODE_OUVERTE"));
        dans(() -> periodes.verrouiller(trimestre));
        mvc.perform(post(visa, classe, trimestre, maths).with(jeton(autre, "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(post(visa, classe, trimestre, maths).with(jeton(censeur, "CENSEUR"))).andExpect(status().isForbidden());
        mvc.perform(post(visa, classe, trimestre, maths).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)) // l'enseignant ne voit que sa matière
                .andExpect(jsonPath("$[0].vise").value(true))
                .andExpect(jsonPath("$[0].signature").value(true))
                .andExpect(jsonPath("$[0].moi").value(true));
        mvc.perform(get("/api/v1/classes/{c}/periodes/{p}/visas", classe, trimestre).with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.matiereCode == 'MATH')].vise").value(true))
                .andExpect(jsonPath("$[?(@.matiereCode == 'ANG')].vise").value(false));
        mvc.perform(get("/api/v1/classes/{c}/periodes/{p}/visas", classe, trimestre).with(jeton(etranger, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        // Une appréciation modifiée annule le visa ; l'enseignant vise de nouveau
        mvc.perform(put("/api/v1/classes/{c}/periodes/{p}/appreciations", classe, trimestre).with(jeton(prof, "ENSEIGNANT"))
                .contentType(MediaType.APPLICATION_JSON).content(appreciation.formatted(maths, awa, "Très bon travail")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/classes/{c}/periodes/{p}/visas", classe, trimestre).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(jsonPath("$[0].vise").value(false));
        mvc.perform(post(visa, classe, trimestre, maths).with(jeton(prof, "ENSEIGNANT"))).andExpect(status().isOk());

        // ---- Génération : avertissement pour l'anglais non visé, signature et photo sur le bulletin
        String generation = mvc.perform(post("/api/v1/classes/{c}/periodes/{p}/bulletins", classe, trimestre)
                        .with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matieresNonVisees.length()").value(1))
                .andExpect(jsonPath("$.matieresNonVisees[0]").value("ANG"))
                .andReturn().getResponse().getContentAsString();
        String bulletinId = JsonPath.read(generation, "$.bulletins[0].id");
        byte[] pdf = mvc.perform(get("/api/v1/bulletins/{id}/pdf", bulletinId).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String date = "le " + aujourdhui.format(DateTimeFormatter.ofPattern("dd/MM/yy"));
        assertThat(texte(pdf)).contains("Visa").contains(date).contains("SANOU");

        // ---- Après publication, les visas ne changent plus
        mvc.perform(post("/api/v1/classes/{c}/periodes/{p}/bulletins/publication", classe, trimestre)
                .with(jeton(censeur, "CENSEUR"))).andExpect(status().isOk());
        mvc.perform(delete(visa, classe, trimestre, maths).with(jeton(prof, "ENSEIGNANT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BULLETINS_PUBLIES"));
        mvc.perform(post(visa, classe, trimestre, anglais).with(jeton(autre, "ENSEIGNANT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BULLETINS_PUBLIES"));
    }

    // ------------------------------------------------------------------

    private static MockMultipartFile fichier(byte[] contenu) {
        return new MockMultipartFile("fichier", "image", "application/octet-stream", contenu);
    }

    private static byte[] jpeg(int largeur, int hauteur) throws IOException {
        BufferedImage image = new BufferedImage(largeur, hauteur, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillOval(largeur / 4, hauteur / 4, largeur / 2, hauteur / 2);
        g.dispose();
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", sortie);
        return sortie.toByteArray();
    }

    private static byte[] png(int largeur, int hauteur) throws IOException {
        BufferedImage image = new BufferedImage(largeur, hauteur, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.drawLine(10, hauteur / 2, largeur - 10, hauteur / 3);
        g.dispose();
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        ImageIO.write(image, "png", sortie);
        return sortie.toByteArray();
    }

    private static String texte(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private UUID matiere(String code, UUID classe) {
        UUID id = dans(() -> matieres.creer(code, code, TypeMatiere.GENERALE)).id();
        dans(() -> classes.definirMatiere(classe, id, BigDecimal.ONE, null, BigDecimal.ONE, null));
        return id;
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return compte(tel, role);
    }

    private UUID compte(String tel, Role role) {
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T commeCenseur(Supplier<T> action) {
        Authentication precedente = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("censeur", null, "ROLE_CENSEUR"));
        try {
            return dans(action);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(precedente);
        }
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
