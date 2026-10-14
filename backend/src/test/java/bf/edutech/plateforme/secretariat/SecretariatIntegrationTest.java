package bf.edutech.plateforme.secretariat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Secrétariat (v0.47.1) sur une vraie base : courrier et suivi par le service destinataire, documents rédigés puis
 * signés et affichés, demandes du personnel jusqu'à la hiérarchie, accueil et message transmis, comptes rendus,
 * archives, certificat de scolarité, candidats aux examens ; le secrétariat ne voit plus évaluations et bulletins.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecretariatIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n%recette\n".getBytes(StandardCharsets.ISO_8859_1);

    @Autowired private WebApplicationContext contexte;
    @Autowired private EtablissementsService etablissements;
    @Autowired private MembresService membres;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;

    private MockMvc mvc;
    private UUID ecole;
    private UUID chef;
    private UUID secretaire;
    private UUID censeur;
    private UUID intendant;
    private UUID classe;
    private UUID inscription;
    private UUID eleve;
    private final LocalDate j = LocalDate.now(ZoneId.of("Africa/Ouagadougou"));

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var creation = etablissements.creer("sec-" + suffixe, "Lycée " + suffixe, telephone(), "ADMIN", "Test");
        ecole = creation.etablissement().id();
        chef = creation.administrateur().utilisateurId();
        secretaire = membre(Role.SECRETARIAT);
        censeur = membre(Role.CENSEUR);
        intendant = membre(Role.INTENDANT);
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        AnneeVue annee = dans(() -> annees.creer(j.getYear() + "-" + (j.getYear() + 1), j.minusDays(30), j.plusDays(300)));
        UUID filiere = dans(() -> filieres.creer("G" + ThreadLocalRandom.current().nextInt(1000, 9999),
                "Enseignement général", "Premier cycle", "BEPC", general)).id();
        classe = dans(() -> classes.creer(annee.id(), filiere, "3e A", "3e", null)).id();
        DossierEleveVue d = dans(() -> eleves.creer(new DonneesEleve(null, "OUEDRAOGO", "Awa", Sexe.F,
                LocalDate.of(2011, 4, 2), "Koudougou", null, null, null), List.of()));
        eleve = d.eleve().id();
        inscription = dans(() -> inscriptions.inscrire(eleve, classe, false, null)).id();
    }

    @Test
    void courrierEtSuiviParLeServiceDestinataire() throws Exception {
        String arrivee = """
                {"sens":"ARRIVEE","correspondant":"DPEPPNF du Kadiogo","categorie":"DIRECTION_PROVINCIALE",
                 "objet":"Calendrier des examens","reference":"N°2026-123"}""";
        mvc.perform(post("/api/v1/courriers").with(jeton(intendant, "INTENDANT")).contentType(MediaType.APPLICATION_JSON)
                .content(arrivee)).andExpect(status().isForbidden());
        String a1 = mvc.perform(post("/api/v1/courriers").with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(arrivee))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("A-" + j.getYear() + "-0001"))
                .andExpect(jsonPath("$.statut").value("ENREGISTRE"))
                .andReturn().getResponse().getContentAsString();
        UUID id = id(a1);
        mvc.perform(post("/api/v1/courriers").with(jeton(secretaire, "SECRETARIAT")).contentType(MediaType.APPLICATION_JSON)
                .content(arrivee)).andExpect(jsonPath("$.numero").value("A-" + j.getYear() + "-0002"));
        mvc.perform(post("/api/v1/courriers").with(jeton(secretaire, "SECRETARIAT")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sens":"DEPART","correspondant":"Parents d'élèves","categorie":"PARENTS","objet":"Réunion",
                                 "reponseA":"%s"}""".formatted(id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("D-" + j.getYear() + "-0001"))
                .andExpect(jsonPath("$.reponseANumero").value("A-" + j.getYear() + "-0001"));
        mvc.perform(post("/api/v1/courriers").with(jeton(secretaire, "SECRETARIAT")).contentType(MediaType.APPLICATION_JSON)
                .content(arrivee.replace("\"ARRIVEE\"", "\"ARRIVEE\",\"dateCourrier\":\"" + j.plusDays(2) + "\"")))
                .andExpect(status().isBadRequest());

        // Pièce scannée
        mvc.perform(multipart("/api/v1/courriers/{id}/fichier", id)
                        .file(new MockMultipartFile("fichier", "note.txt", "text/plain", "texte".getBytes()))
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/courriers/{id}/fichier", id)
                        .file(new MockMultipartFile("fichier", "note.pdf", "application/pdf", PDF))
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fichierNom").value("note.pdf"));

        // L'intendance ne voit le courrier qu'une fois transmis ; elle le marque traité, pas classé
        mvc.perform(get("/api/v1/courriers").with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/courriers/{id}/suivi", id).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"TRANSMIS\",\"service\":\"INTENDANCE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("TRANSMIS"));
        mvc.perform(get("/api/v1/courriers").with(jeton(intendant, "INTENDANT")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/courriers/{id}/fichier", id).with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/courriers/{id}", id).with(jeton(censeur, "CENSEUR"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/courriers/{id}/suivi", id).with(jeton(intendant, "INTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"CLASSE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/courriers/{id}/suivi", id).with(jeton(intendant, "INTENDANT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"TRAITE\",\"suivi\":\"Frais calculés\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suivi").value("Frais calculés"));

        byte[] registre = mvc.perform(get("/api/v1/courriers/registre").param("format", "xlsx")
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(registre, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
    }

    @Test
    void documentsSignesEtTableauDAffichage() throws Exception {
        String note = """
                {"type":"NOTE_SERVICE","titre":"Horaires de la semaine des examens","destinataires":"Tout le personnel",
                 "corps":"Les cours se terminent à 12 h.\\nMerci de votre compréhension."}""";
        String d = mvc.perform(post("/api/v1/documents-administratifs").with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("BROUILLON"))
                .andExpect(jsonPath("$.numero").doesNotExist())
                .andExpect(jsonPath("$.signataire").value("Le Chef d'établissement"))
                .andReturn().getResponse().getContentAsString();
        UUID id = id(d);
        byte[] projet = mvc.perform(get("/api/v1/documents-administratifs/{id}/pdf", id).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(projet, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        String affichage = "{\"affichage\":\"%s\",\"au\":\"%s\"}";
        mvc.perform(put("/api/v1/documents-administratifs/{id}/affichage", id).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(affichage.formatted("TOUS", j.plusDays(7))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_NON_SIGNE"));
        mvc.perform(post("/api/v1/documents-administratifs/{id}/signature", id).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/documents-administratifs/{id}/signature", id).with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("SIGNE"))
                .andExpect(jsonPath("$.numero").value(j.getYear() + "-0001/NS"));
        mvc.perform(put("/api/v1/documents-administratifs/{id}", id).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(note))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_SIGNE"));

        // Un communiqué pour le personnel seulement, la note pour tous
        String c = mvc.perform(post("/api/v1/documents-administratifs").with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(note.replace("NOTE_SERVICE", "COMMUNIQUE")
                                .replace("Horaires de la semaine des examens", "Assemblée du personnel")))
                .andReturn().getResponse().getContentAsString();
        UUID communique = id(c);
        mvc.perform(post("/api/v1/documents-administratifs/{id}/signature", communique).with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(jsonPath("$.numero").value(j.getYear() + "-0001/COM"));
        mvc.perform(put("/api/v1/documents-administratifs/{id}/affichage", id).with(jeton(secretaire, "SECRETARIAT"))
                .contentType(MediaType.APPLICATION_JSON).content(affichage.formatted("TOUS", j.plusDays(7))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.affichage").value("TOUS"));
        mvc.perform(put("/api/v1/documents-administratifs/{id}/affichage", communique).with(jeton(secretaire, "SECRETARIAT"))
                .contentType(MediaType.APPLICATION_JSON).content(affichage.formatted("PERSONNEL", j.plusDays(3))))
                .andExpect(status().isOk());
        UUID parent = UUID.randomUUID();
        mvc.perform(get("/api/v1/affichage").with(jeton(parent, "PARENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].typeLibelle").value("Note de service"));
        mvc.perform(get("/api/v1/affichage").with(jeton(intendant, "INTENDANT")))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/documents-administratifs/{id}/pdf", id).with(jeton(parent, "PARENT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents-administratifs/{id}/pdf", communique).with(jeton(parent, "PARENT")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents-administratifs").with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void demandesDuPersonnelAccueilReunionsArchivesCertificatsEtExamens() throws Exception {
        // ---- Demande d'autorisation d'absence du censeur
        String corps = """
                {"type":"AUTORISATION_ABSENCE","demandeurId":"%s","objet":"Obsèques d'un parent","du":"%s","au":"%s"}""";
        mvc.perform(post("/api/v1/demandes-administratives").with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"AUTORISATION_ABSENCE\",\"demandeurId\":\"%s\",\"objet\":\"X\"}".formatted(censeur)))
                .andExpect(status().isBadRequest());
        String dem = mvc.perform(post("/api/v1/demandes-administratives").with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps.formatted(censeur, j.plusDays(3), j.plusDays(5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("R-" + j.getYear() + "-0001"))
                .andExpect(jsonPath("$.demandeur").value(org.hamcrest.Matchers.containsString("KONE")))
                .andReturn().getResponse().getContentAsString();
        UUID demande = id(dem);
        mvc.perform(post("/api/v1/demandes-administratives/{id}/transmission", demande).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$.statut").value("TRANSMISE"));
        mvc.perform(post("/api/v1/demandes-administratives/{id}/cloture", demande).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"suite\":\"X\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVIS_ATTENDU"));
        mvc.perform(post("/api/v1/demandes-administratives/{id}/avis", demande).with(jeton(secretaire, "SECRETARIAT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorable\":true}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demandes-administratives/{id}/avis", demande).with(jeton(chef, "ADMIN_ECOLE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorable\":false}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/demandes-administratives/{id}/avis", demande).with(jeton(chef, "ADMIN_ECOLE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorable\":true,\"avis\":\"Accordé\"}"))
                .andExpect(jsonPath("$.statut").value("AVIS_FAVORABLE"));
        mvc.perform(post("/api/v1/demandes-administratives/{id}/transmission-hierarchie", demande)
                        .with(jeton(secretaire, "SECRETARIAT")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinataire\":\"DPEPPNF du Kadiogo\"}"))
                .andExpect(jsonPath("$.statut").value("TRANSMISE_HIERARCHIE"))
                .andExpect(jsonPath("$.courrierNumero").value("D-" + j.getYear() + "-0001"));
        mvc.perform(get("/api/v1/demandes-administratives/miennes").with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].statut").value("TRANSMISE_HIERARCHIE"));
        mvc.perform(get("/api/v1/demandes-administratives").with(jeton(censeur, "CENSEUR"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demandes-administratives/{id}/cloture", demande).with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"suite\":\"Autorisation accordée par la DPEPPNF\"}"))
                .andExpect(jsonPath("$.statut").value("CLOTUREE"));

        // ---- Accueil : appel pour le censeur, transmis dans sa messagerie
        mvc.perform(post("/api/v1/accueil").with(jeton(secretaire, "SECRETARIAT")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"canal":"APPEL","nom":"M. SAWADOGO","telephone":"70 11 22 33","qualite":"PARENT",
                                 "objet":"Absence de son fils","personneId":"%s","message":"Rappeler avant 17 h",
                                 "urgent":true,"transmettre":true}""".formatted(censeur)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transmisLe").exists())
                .andExpect(jsonPath("$.personne").value(org.hamcrest.Matchers.containsString("KONE")));
        mvc.perform(get("/api/v1/messages/non-lus").with(jeton(censeur, "CENSEUR")))
                .andExpect(jsonPath("$.nombre").value(1));
        String visite = mvc.perform(post("/api/v1/accueil").with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canal\":\"VISITE\",\"nom\":\"Mme KABORE\",\"qualite\":\"PARENT\",\"objet\":\"Inscription\",\"service\":\"INTENDANCE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/accueil/{id}/depart", id(visite)).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$.depart").exists());
        mvc.perform(post("/api/v1/accueil/{id}/transmission", id(visite)).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SANS_DESTINATAIRE"));
        mvc.perform(get("/api/v1/accueil").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$.length()").value(2));

        // ---- Compte rendu du conseil d'établissement
        String r = mvc.perform(post("/api/v1/reunions").with(jeton(secretaire, "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"instance":"CONSEIL_ETABLISSEMENT","date":"%s","lieu":"Salle des professeurs",
                                 "president":"Le Proviseur","ordreDuJour":"1. Rentrée","compteRendu":"La séance est ouverte à 9 h."}"""
                                .formatted(j)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titre").value("Conseil d'établissement"))
                .andReturn().getResponse().getContentAsString();
        UUID reunion = id(r);
        mvc.perform(post("/api/v1/reunions/{id}/validation", reunion).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reunions/{id}/validation", reunion).with(jeton(chef, "ADMIN_ECOLE")))
                .andExpect(jsonPath("$.statut").value("VALIDE"));
        mvc.perform(delete("/api/v1/reunions/{id}", reunion).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/reunions/{id}/pdf", reunion).with(jeton(censeur, "CENSEUR"))).andExpect(status().isOk());

        // ---- Archives : acte de naissance de l'élève, arrêté du censeur
        String piece = mvc.perform(multipart("/api/v1/archives")
                        .file(new MockMultipartFile("fichier", "acte.pdf", "application/pdf", PDF))
                        .param("dossier", "ELEVE").param("eleveId", eleve.toString())
                        .param("categorie", "État civil").param("titre", "Extrait d'acte de naissance")
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/archives").param("dossier", "ELEVE").param("eleveId", eleve.toString())
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].fichierType").value("application/pdf"));
        mvc.perform(multipart("/api/v1/archives").file(new MockMultipartFile("fichier", "a.pdf", "application/pdf", PDF))
                        .param("dossier", "PERSONNEL").param("utilisateurId", UUID.randomUUID().toString())
                        .param("categorie", "Carrière").param("titre", "Arrêté").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/archives/{id}/fichier", id(piece)).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/archives/{id}/fichier", id(piece)).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/archives/{id}", id(piece)).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isNoContent());

        // ---- Certificat de scolarité, candidats aux examens
        byte[] certificat = mvc.perform(post("/api/v1/inscriptions/{id}/certificat-scolarite", inscription)
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(certificat, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        mvc.perform(get("/api/v1/certificats-scolarite").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$[0].numero").value("CS-" + j.getYear() + "-0001"))
                .andExpect(jsonPath("$[0].classe").value("3e A"));
        mvc.perform(get("/api/v1/examens/candidats").param("classes", classe.toString()).with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].manquants[0]").value("photo d'identité"));
        mvc.perform(get("/api/v1/examens/candidats/export").param("classes", classe.toString()).param("examen", "BEPC")
                        .param("format", "pdf").with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isOk());

        // ---- Le secrétariat ne voit plus les évaluations ni les bulletins (v0.47.1)
        // (periodeId est obligatoire : sans lui, la requête est refusée en 400 avant le contrôle des droits)
        mvc.perform(get("/api/v1/classes/{id}/evaluations", classe).param("periodeId", UUID.randomUUID().toString())
                        .with(jeton(secretaire, "SECRETARIAT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/classes/{c}/periodes/{p}/visas", classe, UUID.randomUUID())
                .with(jeton(secretaire, "SECRETARIAT"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private static UUID id(String json) {
        return UUID.fromString(json.replaceAll("(?s)^\\{\"id\":\"([0-9a-f-]+)\".*", "$1"));
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return dans(() -> membres.lister()).stream().filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private RequestPostProcessor jeton(UUID utilisateur, String role) {
        return jwt().jwt(t -> t.subject(utilisateur.toString()).claim("tenant_id", ecole.toString())
                        .claim("typ_jeton", "ACCES").claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
