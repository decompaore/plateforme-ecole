package bf.edutech.plateforme.viescolaire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.NotificationVue;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;
import bf.edutech.plateforme.viescolaire.VieScolaireService.HistoriqueVue;
import bf.edutech.plateforme.viescolaire.VieScolaireService.IncidentVue;
import bf.edutech.plateforme.viescolaire.VieScolaireService.SaisieIncident;

/** Vie scolaire sur une vraie base PostgreSQL (profil "test"). */
@SpringBootTest
@ActiveProfiles("test")
class VieScolaireIntegrationTest {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private PeriodesService periodes;
    @Autowired private FilieresService filieres;
    @Autowired private MatieresService matieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EspaceParentService espaceParent;
    @Autowired private EnseignantsService enseignants;
    @Autowired private MembresService membres;
    @Autowired private NotificationsService notifications;
    @Autowired private VieScolaireService vieScolaire;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;
    private UUID classe;
    private UUID matiere;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        int an = j.getYear();
        AnneeVue annee = dans(() -> annees.creer(an + "-" + (an + 1), j.minusDays(30), j.plusDays(300)));
        UUID filiere = dans(() -> filieres.creer("F" + ThreadLocalRandom.current().nextInt(1000, 9999),
                "Enseignement général", "Premier cycle", "BEPC", general)).id();
        classe = dans(() -> classes.creer(annee.id(), filiere, "6e A", "6e", null)).id();
        String code = "M" + ThreadLocalRandom.current().nextInt(1000, 9999);
        matiere = dans(() -> matieres.creer(code, "Mathématiques", TypeMatiere.GENERALE)).id();
        dans(() -> classes.definirMatiere(classe, matiere, BigDecimal.ONE, null, BigDecimal.ONE, null));
        dans(() -> periodes.generer(annee.id(), general));
        dans(() -> annees.ouvrir(annee.id()));
    }

    @Test
    void incidentsConvocationsSmsHistoriqueEtParent() throws Exception {
        DossierEleveVue dossierAwa = eleve("OUEDRAOGO", "Awa", telephone());
        UUID awa = inscrire(dossierAwa);
        UUID ali = inscrire(eleve("SAWADOGO", "Ali", null));
        UUID surveillant = membre(Role.SURVEILLANT);

        // Surveillant : retard sans SMS par défaut, avertissement avec SMS, pas de blâme
        String retard = incident(surveillant, "SURVEILLANT", awa,
                "{\"type\":\"RETARD\",\"minutesRetard\":20,\"motif\":\"Arrivée à 7 h 20\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.famillePrevenue").value(false))
                .andReturn().getResponse().getContentAsString();
        UUID retardId = UUID.fromString(retard.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1"));
        String avertissement = incident(surveillant, "SURVEILLANT", awa,
                "{\"type\":\"AVERTISSEMENT\",\"motif\":\"Bavardages répétés\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.famillePrevenue").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID avertissementId = UUID.fromString(avertissement.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1"));
        incident(surveillant, "SURVEILLANT", awa, "{\"type\":\"BLAME\",\"motif\":\"Insolence\"}")
                .andExpect(status().isForbidden());
        assertThat(enAttente()).anySatisfy(n -> assertThat(n.message())
                .contains("avertissement pour Awa OUEDRAOGO (6e A) le " + j.format(JOUR) + " : Bavardages répétés."));

        // Enseignant : seulement dans les classes où il enseigne
        String tel = telephone();
        UUID engagement = dans(() -> enseignants.engager(new DonneesEngagement(tel, null, "SANOU", "Paul", Sexe.M,
                null, TypeEngagement.TITULAIRE, j.minusDays(30), null, null))).enseignant().engagementId();
        UUID professeur = compte(tel, Role.ENSEIGNANT);
        incident(professeur, "ENSEIGNANT", ali, "{\"type\":\"AVERTISSEMENT\",\"motif\":\"Devoir non fait\"}")
                .andExpect(status().isForbidden());
        dans(() -> enseignants.affecter(classe, matiere, engagement));
        incident(professeur, "ENSEIGNANT", ali, "{\"type\":\"AVERTISSEMENT\",\"motif\":\"Devoir non fait\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.famillePrevenue").value(false));      // Ali n'a pas de contact

        // Direction : exclusion temporaire de 3 jours
        IncidentVue exclusion = commeCenseur(() -> vieScolaire.signaler(awa, new SaisieIncident(
                TypeIncident.EXCLUSION_TEMPORAIRE, null, "Bagarre", null, j.plusDays(1), 3, null)));
        assertThat(exclusion.finExclusion()).isEqualTo(j.plusDays(3));
        assertThat(enAttente()).anySatisfy(n -> assertThat(n.message()).contains("exclusion temporaire de Awa "
                + "OUEDRAOGO (6e A) pour 3 jour(s), du " + j.plusDays(1).format(JOUR) + " au "
                + j.plusDays(3).format(JOUR) + " : Bagarre."));
        assertThatThrownBy(() -> commeCenseur(() -> vieScolaire.signaler(awa, new SaisieIncident(TypeIncident.RETARD,
                null, "Sans durée", null, null, null, null)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commeCenseur(() -> vieScolaire.signaler(awa, new SaisieIncident(
                TypeIncident.AVERTISSEMENT, j.plusDays(1), "Dans le futur", null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        // Annulations : l'auteur dans les 24 h, pas l'incident d'un autre ; la direction toujours
        String motif = "{\"motif\":\"Saisi par erreur\"}";
        mvc.perform(post("/api/v1/incidents/{id}/annulation", retardId).with(jeton(surveillant, "SURVEILLANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(motif))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annule").value(true));
        mvc.perform(post("/api/v1/incidents/{id}/annulation", exclusion.id()).with(jeton(surveillant, "SURVEILLANT"))
                        .contentType(MediaType.APPLICATION_JSON).content(motif))
                .andExpect(status().isForbidden());
        commeCenseur(() -> vieScolaire.annulerIncident(avertissementId, "Erreur d'élève"));
        assertThat(dans(() -> notifications.dernieres(StatutNotification.ANNULE, 10)))
                .anySatisfy(n -> assertThat(n.message()).contains("Bavardages répétés"));
        assertThatThrownBy(() -> commeCenseur(() -> vieScolaire.annulerIncident(avertissementId, "encore")))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("INCIDENT_DEJA_ANNULE");

        // Convocation : SMS avec la date et l'heure
        LocalDateTime rdv = j.plusDays(2).atTime(10, 0);
        String convocation = mvc.perform(post("/api/v1/inscriptions/{id}/convocations", awa)
                        .with(jeton(surveillant, "SURVEILLANT")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rendezVous\":\"%s\",\"motif\":\"Exclusion temporaire\",\"incidentId\":\"%s\"}"
                                .formatted(rdv, exclusion.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("PREVUE"))
                .andReturn().getResponse().getContentAsString();
        UUID convocationId = UUID.fromString(convocation.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1"));
        assertThat(enAttente()).anySatisfy(n -> assertThat(n.message()).contains("vous êtes convoqué(e) le "
                + rdv.format(JOUR) + " à 10h00 au sujet de Awa OUEDRAOGO (6e A) : Exclusion temporaire."));
        // Pas de compte rendu avant le rendez-vous ; une annulation avant envoi retire le SMS
        mvc.perform(post("/api/v1/convocations/{id}/cloture", convocationId).with(jeton(surveillant, "SURVEILLANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statut\":\"HONOREE\",\"compteRendu\":\"Père venu\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENDEZ_VOUS_A_VENIR"));
        var seconde = commeCenseur(() -> vieScolaire.convoquer(awa, j.plusDays(5).atTime(8, 30), "Suivi", null));
        commeCenseur(() -> vieScolaire.cloturer(seconde.id(), StatutConvocation.ANNULEE, null));
        assertThat(enAttente()).noneSatisfy(n -> assertThat(n.message()).contains("Suivi"));
        assertThat(enAttente()).noneSatisfy(n -> assertThat(n.message()).contains("est annulée"));
        assertThatThrownBy(() -> commeCenseur(() -> vieScolaire.cloturer(seconde.id(), StatutConvocation.ANNULEE,
                null))).isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("CONVOCATION_CLOSE");

        // Historique (direction) : tout, y compris les annulations ; synthèse sans les annulés
        HistoriqueVue h = commeCenseur(() -> vieScolaire.historique(awa));
        assertThat(h.incidents()).hasSize(3);
        assertThat(h.synthese().retards()).isZero();
        assertThat(h.synthese().avertissements()).isZero();
        assertThat(h.synthese().exclusions()).isEqualTo(1);
        assertThat(h.synthese().joursExclusion()).isEqualTo(3);
        assertThat(h.synthese().convocations()).isEqualTo(1);

        // Parent : incidents non annulés, convocations sans compte rendu interne
        UUID parent = dans(() -> espaceParent.ouvrir(dossierAwa.responsables().get(0).responsableId()))
                .utilisateurId();
        mvc.perform(get("/api/v1/espace-parent/enfants/{id}/vie-scolaire", dossierAwa.eleve().id())
                        .with(jeton(parent, "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].incidents.length()").value(1))
                .andExpect(jsonPath("$[0].incidents[0].type").value("EXCLUSION_TEMPORAIRE"))
                .andExpect(jsonPath("$[0].convocations.length()").value(1))
                .andExpect(jsonPath("$[0].convocations[0].compteRendu").value(org.hamcrest.Matchers.nullValue()));

        // Synthèse de la classe et agenda des convocations
        mvc.perform(get("/api/v1/classes/{id}/vie-scolaire", classe).param("du", j.minusDays(7).toString())
                        .param("au", j.toString()).with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.prenoms == 'Ali')].synthese.avertissements").value(1))
                .andExpect(jsonPath("$[?(@.prenoms == 'Awa')].synthese.exclusions").value(1));
        mvc.perform(get("/api/v1/convocations").param("du", j.toString()).param("au", j.plusDays(7).toString())
                        .with(jeton(surveillant, "SURVEILLANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ------------------------------------------------------------------

    private ResultActions incident(UUID utilisateur, String role, UUID inscription, String corps) throws Exception {
        return mvc.perform(post("/api/v1/inscriptions/{id}/incidents", inscription).with(jeton(utilisateur, role))
                .contentType(MediaType.APPLICATION_JSON).content(corps));
    }

    private List<NotificationVue> enAttente() {
        return dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 20));
    }

    private DossierEleveVue eleve(String nom, String prenoms, String telParent) {
        List<DonneesResponsable> responsables = telParent == null ? List.of()
                : List.of(new DonneesResponsable(nom, "Parent", telParent, LienParente.PERE, null, null, true, null));
        return dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.F, LocalDate.of(2013, 5, 6), null,
                null, null, null), responsables));
    }

    private UUID inscrire(DossierEleveVue dossier) {
        return dans(() -> inscriptions.inscrire(dossier.eleve().id(), classe, false, null)).id();
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
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("censeur", null, "ROLE_CENSEUR"));
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

    private UUID nouvelleEcole() {
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return etablissements.creer("vs-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN",
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
