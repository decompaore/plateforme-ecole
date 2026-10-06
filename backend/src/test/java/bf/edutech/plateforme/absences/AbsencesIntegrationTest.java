package bf.edutech.plateforme.absences;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import bf.edutech.plateforme.absences.Vues.SyntheseEleveVue;
import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
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
import bf.edutech.plateforme.notifications.EnvoiNotifications;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.NotificationVue;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.notifications.PasserelleSmsJournal;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Tests du domaine Absences sur une vraie base PostgreSQL (profil "test").
 * Chaque test crée une école avec une année ACTIVE contenant la date du jour,
 * une classe de 6e, un enseignant de mathématiques affecté et trois élèves.
 */
@SpringBootTest
@ActiveProfiles("test")
class AbsencesIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EspaceParentService espaceParent;
    @Autowired private AbsencesService absences;
    @Autowired private NotificationsService notifications;
    @Autowired private EnvoiNotifications envoi;
    @Autowired private PasserelleSmsJournal passerelle;
    @Autowired private WebApplicationContext contexte;

    private MockMvc mvc;
    private UUID ecole;
    private UUID classe;
    private UUID enseignant;
    private UUID maths;
    private UUID francais;
    private final LocalDate aujourdhui = LocalDate.now(ZoneOffset.UTC);
    private DossierEleveVue awa;
    private DossierEleveVue ali;
    private DossierEleveVue ines;
    private UUID inscriptionAwa;
    private UUID inscriptionAli;
    private UUID inscriptionInes;
    private String telephoneParentAwa;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        int an = aujourdhui.getYear();
        AnneeVue annee = dans(() -> annees.creer(an + "-" + (an + 1), aujourdhui.minusDays(30),
                aujourdhui.plusDays(300)));
        UUID filiere = dans(() -> filieres.creer("GEN", "Général", "Premier cycle", "BEPC", general)).id();
        maths = dans(() -> matieres.creer("MATH", "Mathématiques", TypeMatiere.GENERALE)).id();
        francais = dans(() -> matieres.creer("FR", "Français", TypeMatiere.GENERALE)).id();
        classe = dans(() -> classes.creer(annee.id(), filiere, "6e A", "6e", null)).id();
        dans(() -> classes.definirMatiere(classe, maths, new BigDecimal("4"), null, new BigDecimal("5"), null));
        dans(() -> classes.definirMatiere(classe, francais, new BigDecimal("3"), null, new BigDecimal("4"), null));
        dans(() -> periodes.generer(annee.id(), general));
        dans(() -> annees.ouvrir(annee.id()));

        String telEnseignant = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(telEnseignant, null, "Sanou", "Paul",
                Sexe.M, null, TypeEngagement.TITULAIRE, aujourdhui.minusDays(30), null, null))).enseignant()
                .engagementId();
        dans(() -> enseignants.affecter(classe, maths, engagement));
        enseignant = compte(telEnseignant, Role.ENSEIGNANT);

        telephoneParentAwa = telephone();
        awa = eleve("OUEDRAOGO", "Awa", Sexe.F, telephoneParentAwa);
        ali = eleve("SAWADOGO", "Ali", Sexe.M, telephone());
        ines = eleve("ZONGO", "Ines", Sexe.F, telephone());
        inscriptionAwa = dans(() -> inscriptions.inscrire(awa.eleve().id(), classe, false, null)).id();
        inscriptionAli = dans(() -> inscriptions.inscrire(ali.eleve().id(), classe, false, null)).id();
        inscriptionInes = dans(() -> inscriptions.inscrire(ines.eleve().id(), classe, false, null)).id();
    }

    @Test
    void unAppelRenvoyeNeCreePasDeDoublonEtLaFamilleEstPrevenue() throws Exception {
        UUID idClient = UUID.randomUUID();
        String lot = lot(appel(idClient, aujourdhui, "08:00", "10:00",
                marque(inscriptionAwa, "ABSENCE", null), marque(inscriptionAli, "RETARD", 15),
                marque(UUID.randomUUID(), "ABSENCE", null)));

        synchroniser(lot, enseignant, "ENSEIGNANT")
                .andExpect(jsonPath("$[0].statut").value("ENREGISTRE"))
                .andExpect(jsonPath("$[0].absents").value(1))
                .andExpect(jsonPath("$[0].retards").value(1))
                .andExpect(jsonPath("$[0].inscriptionsIgnorees.length()").value(1));
        // Coupure réseau : l'appareil renvoie le même lot
        synchroniser(lot, enseignant, "ENSEIGNANT").andExpect(jsonPath("$[0].statut").value("DEJA_RECU"));

        List<NotificationVue> enAttente = dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10));
        assertThat(enAttente).singleElement().satisfies(n -> {
            assertThat(n.message()).contains("Awa OUEDRAOGO (6e A) était absente").contains("de 08h00 à 10h00");
            assertThat(n.destinataire()).contains("****");
        });

        // Le worker traite les écoles par lots (la base de test peut contenir des messages d'autres tests)
        for (int passage = 0; passage < 200
                && dans(() -> notifications.dernieres(StatutNotification.ENVOYE, 10)).isEmpty(); passage++) {
            envoi.traiterLot();
        }
        assertThat(passerelle.derniers()).anySatisfy(sms -> {
            assertThat(sms.telephone()).isEqualTo("+226" + telephoneParentAwa);
            assertThat(sms.message()).contains("Awa OUEDRAOGO");
        });
        assertThat(dans(() -> notifications.dernieres(StatutNotification.ENVOYE, 10))).hasSize(1);
    }

    @Test
    void reglesDeSaisieEtDeModification() throws Exception {
        UUID matin = UUID.randomUUID();
        synchroniser(lot(appel(matin, aujourdhui, "08:00", "10:00", marque(inscriptionAwa, "ABSENCE", null))),
                enseignant, "ENSEIGNANT").andExpect(jsonPath("$[0].statut").value("ENREGISTRE"));

        // Le jour même, l'enseignant corrige : Awa était là, Ines était absente
        synchroniser(lot(appel(matin, aujourdhui, "08:00", "10:00", marque(inscriptionInes, "ABSENCE", null))),
                enseignant, "ENSEIGNANT").andExpect(jsonPath("$[0].statut").value("MODIFIE"));
        assertThat(dans(() -> notifications.dernieres(StatutNotification.ANNULE, 10))).hasSize(1);
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 10))).singleElement()
                .satisfies(n -> assertThat(n.message()).contains("Ines ZONGO"));

        // Un lot mêlant un appel valide et des appels refusés : chacun reçoit son accusé
        UUID surveillant = membre(Role.SURVEILLANT);
        synchroniser(lot(
                appel(UUID.randomUUID(), aujourdhui, "08:00", "09:00"),            // créneau déjà fait
                appel(UUID.randomUUID(), aujourdhui.plusDays(1), "08:00", "10:00"), // date future
                appel(UUID.randomUUID(), aujourdhui, "10:00", "12:00")),           // valide
                surveillant, "SURVEILLANT")
                .andExpect(jsonPath("$[0].code").value("CRENEAU_DEJA_FAIT"))
                .andExpect(jsonPath("$[1].code").value("DATE_FUTURE"))
                .andExpect(jsonPath("$[2].statut").value("ENREGISTRE"));

        // Un enseignant non affecté à la classe ne fait pas l'appel
        UUID autreEnseignant = membre(Role.ENSEIGNANT);
        synchroniser(lot(appel(UUID.randomUUID(), aujourdhui, "14:00", "16:00")), autreEnseignant, "ENSEIGNANT")
                .andExpect(jsonPath("$[0].code").value("NON_AFFECTE"));

        // Appel de la veille (synchronisé en retard) : accepté ; le modifier ensuite est réservé à la vie scolaire
        UUID hier = UUID.randomUUID();
        synchroniser(lot(appel(hier, aujourdhui.minusDays(1), "08:00", "10:00", marque(inscriptionAli, "ABSENCE", null))),
                enseignant, "ENSEIGNANT").andExpect(jsonPath("$[0].statut").value("ENREGISTRE"));
        synchroniser(lot(appel(hier, aujourdhui.minusDays(1), "08:00", "10:00")), enseignant, "ENSEIGNANT")
                .andExpect(jsonPath("$[0].code").value("APPEL_CLOS"));
        mvc.perform(put("/api/v1/appels/{id}", hier).with(jeton(surveillant, "SURVEILLANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marques\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("MODIFIE"));
        mvc.perform(get("/api/v1/appels/{id}", hier).with(jeton(enseignant, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marques.length()").value(0));
    }

    @Test
    void justificatifsSyntheseEtEspaceParent() throws Exception {
        synchroniser(lot(
                appel(UUID.randomUUID(), aujourdhui, "08:00", "10:00", marque(inscriptionAwa, "ABSENCE", null)),
                appel(UUID.randomUUID(), aujourdhui, "10:00", "12:00", marque(inscriptionAwa, "ABSENCE", null),
                        marque(inscriptionAli, "RETARD", 10))),
                enseignant, "ENSEIGNANT")
                .andExpect(jsonPath("$[0].statut").value("ENREGISTRE"))
                .andExpect(jsonPath("$[1].statut").value("ENREGISTRE"));

        SyntheseEleveVue avant = synthese(inscriptionAwa);
        assertThat(avant.absences()).isEqualTo(2);
        assertThat(avant.heuresAbsence()).isEqualByComparingTo("4");
        assertThat(avant.heuresNonJustifiees()).isEqualByComparingTo("4");
        assertThat(synthese(inscriptionAli).retards()).isEqualTo(1);

        // Liste du jour de la vie scolaire : un élève par ligne, ses créneaux réunis
        UUID surveillant = membre(Role.SURVEILLANT);
        mvc.perform(get("/api/v1/absences/jour").param("date", aujourdhui.toString()).with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nom").value("OUEDRAOGO"))
                .andExpect(jsonPath("$[0].classeCode").value("6e A"))
                .andExpect(jsonPath("$[0].absences").value(2))
                .andExpect(jsonPath("$[0].creneaux.length()").value(2))
                .andExpect(jsonPath("$[0].justifiee").value(false))
                .andExpect(jsonPath("$[1].nom").value("SAWADOGO"))
                .andExpect(jsonPath("$[1].retards").value(1));
        mvc.perform(get("/api/v1/absences/jour").with(jeton(enseignant, "ENSEIGNANT")))
                .andExpect(status().isForbidden());

        dans(() -> absences.justifier(inscriptionAwa, aujourdhui, aujourdhui, TypeJustificatif.MALADIE,
                "Certificat médical"));
        SyntheseEleveVue apres = synthese(inscriptionAwa);
        assertThat(apres.absencesJustifiees()).isEqualTo(2);
        assertThat(apres.heuresNonJustifiees()).isEqualByComparingTo("0");
        mvc.perform(get("/api/v1/absences/jour").param("date", aujourdhui.toString()).with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(jsonPath("$[0].justifiee").value(true))
                .andExpect(jsonPath("$[1].justifiee").value(false));

        // Le parent voit les absences de son enfant, et seulement de son enfant
        UUID parent = dans(() -> espaceParent.ouvrir(awa.responsables().get(0).responsableId())).utilisateurId();
        mvc.perform(get("/api/v1/espace-parent/enfants/{id}/absences", awa.eleve().id()).with(jeton(parent, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].justifiee").value(true));
        mvc.perform(get("/api/v1/espace-parent/enfants/{id}/absences", ali.eleve().id()).with(jeton(parent, "PARENT")))
                .andExpect(status().isForbidden());

        // L'enseignant de la classe consulte la synthèse
        mvc.perform(get("/api/v1/classes/{id}/absences/synthese", classe).with(jeton(enseignant, "ENSEIGNANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void lesAbsencesIndiquentLaDisciplineEtSeResumentParMatiere() throws Exception {
        UUID surveillant = membre(Role.SURVEILLANT);
        String hier = aujourdhui.minusDays(1).toString();
        String jour = aujourdhui.toString();
        synchroniser(lot(
                appelEn(maths, hier, "08:00", "10:00", marque(inscriptionAwa, "ABSENCE", null),
                        marque(inscriptionAli, "ABSENCE", null)),
                appelEn(maths, jour, "08:00", "10:00", marque(inscriptionAwa, "ABSENCE", null)),
                appelEn(francais, jour, "10:00", "11:00", marque(inscriptionAwa, "RETARD", 5)),
                appel(UUID.randomUUID(), aujourdhui, "15:00", "16:00", marque(inscriptionInes, "ABSENCE", null))),
                membre(Role.CENSEUR), "CENSEUR");

        // Liste du jour : la discipline de chaque créneau, « appel général » sans matière
        mvc.perform(get("/api/v1/absences/jour").param("date", jour).with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nom").value("OUEDRAOGO"))
                .andExpect(jsonPath("$[0].creneaux[0].matiereLibelle").value("Mathématiques"))
                .andExpect(jsonPath("$[0].creneaux[1].matiereCode").value("FR"))
                .andExpect(jsonPath("$[1].nom").value("ZONGO"))
                .andExpect(jsonPath("$[1].creneaux[0].matiereId").doesNotExist());

        // Absences de l'élève : la discipline de chaque cours manqué
        mvc.perform(get("/api/v1/inscriptions/{id}/absences", inscriptionAwa).with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.type == 'ABSENCE')].matiereLibelle", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("Mathématiques"))));

        // Synthèse de la classe par discipline : maths en tête (3 cours manqués, 6 h, 2 élèves)
        mvc.perform(get("/api/v1/classes/{id}/absences/matieres", classe).with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].matiereCode").value("MATH"))
                .andExpect(jsonPath("$[0].absences").value(3))
                .andExpect(jsonPath("$[0].heures").value(6.0))
                .andExpect(jsonPath("$[0].heuresNonJustifiees").value(6.0))
                .andExpect(jsonPath("$[0].eleves").value(2))
                .andExpect(jsonPath("$[1].matiereId").doesNotExist())
                .andExpect(jsonPath("$[1].heures").value(1.0))
                .andExpect(jsonPath("$[2].matiereCode").value("FR"))
                .andExpect(jsonPath("$[2].absences").value(0))
                .andExpect(jsonPath("$[2].retards").value(1));

        // Sur la seule journée d'aujourd'hui
        mvc.perform(get("/api/v1/classes/{id}/absences/matieres", classe).param("du", jour).param("au", jour)
                        .with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(jsonPath("$[0].matiereCode").value("MATH"))
                .andExpect(jsonPath("$[0].absences").value(1))
                .andExpect(jsonPath("$[0].eleves").value(1));
    }

    // ------------------------------------------------------------------

    private String appelEn(UUID matiere, String date, String debut, String fin, String... marques) {
        return """
                {"idClient":"%s","classeId":"%s","matiereId":"%s","date":"%s","heureDebut":"%s","heureFin":"%s","marques":[%s]}"""
                .formatted(UUID.randomUUID(), classe, matiere, date, debut, fin, String.join(",", marques));
    }

    private SyntheseEleveVue synthese(UUID inscriptionId) {
        return TenantContext.executerPour(ecole, () -> absencesSansControle()).stream()
                .filter(s -> s.inscriptionId().equals(inscriptionId)).findFirst().orElseThrow();
    }

    /** Synthèse de la classe, appelée comme le ferait la vie scolaire. */
    private List<SyntheseEleveVue> absencesSansControle() {
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken("vie-scolaire", null,
                "ROLE_SURVEILLANT");
        var contexteSecurite = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var precedent = contexteSecurite.getAuthentication();
        contexteSecurite.setAuthentication(auth);
        try {
            return absences.syntheseClasse(classe, null, null);
        } finally {
            contexteSecurite.setAuthentication(precedent);
        }
    }

    private ResultActions synchroniser(String lot, UUID utilisateur, String role) throws Exception {
        return mvc.perform(post("/api/v1/appels/lot").with(jeton(utilisateur, role))
                        .contentType(MediaType.APPLICATION_JSON).content(lot))
                .andExpect(status().isOk());
    }

    private static String lot(String... appels) {
        return "{\"appels\":[" + String.join(",", appels) + "]}";
    }

    private String appel(UUID idClient, LocalDate date, String debut, String fin, String... marques) {
        return """
                {"idClient":"%s","classeId":"%s","date":"%s","heureDebut":"%s","heureFin":"%s","marques":[%s]}"""
                .formatted(idClient, classe, date, debut, fin, String.join(",", marques));
    }

    private static String marque(UUID inscriptionId, String type, Integer minutes) {
        return "{\"inscriptionId\":\"%s\",\"type\":\"%s\"%s}".formatted(inscriptionId, type,
                minutes != null ? ",\"minutesRetard\":" + minutes : "");
    }

    private DossierEleveVue eleve(String nom, String prenoms, Sexe sexe, String telParent) {
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, sexe, LocalDate.of(2014, 5, 12),
                "Ouagadougou", null, null, null),
                List.of(new DonneesResponsable(nom, "Parent", telParent, LienParente.PERE, null, null, true, null))));
    }

    /** Ajoute un membre du personnel et renvoie son compte. */
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

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "6" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return etablissements.creer("abs-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
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
