package bf.edutech.plateforme.recrutement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.enseignants.EnseignantsService.DonneesEngagement;
import bf.edutech.plateforme.enseignants.TypeEngagement;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Commission de recrutement (v0.49). Deux classes de 2nde : 2nde A (3 places, 2 inscrits) et 2nde B (2 places,
 * 1 inscrit), soit 2 places disponibles ; une 1re. Six candidatures : une irrecevable, une sous la moyenne
 * minimale, deux à égalité (le plus jeune passe devant). Délibération : 2 retenus, 1 en liste d'attente ;
 * procès-verbal et résultats ; inscription d'un retenu par l'intendance (mode « Complément d'effectif ») ; un
 * désistement repêche le premier de la liste d'attente ; une classe pleine est refusée.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecrutementIntegrationTest {

    @Autowired private WebApplicationContext contexte;
    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private MembresService membres;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EnseignantsService enseignants;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;

    @Test
    void laCommissionClasseDelibereEtLIntendanceInscritLesRetenus() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        EtablissementsService.ResultatCreation r = etablissements.creer("rec-" + suffixe, "Lycée " + suffixe,
                telephone(), "Traore", "Moussa");
        ecole = r.etablissement().id();
        UUID proviseur = r.administrateur().utilisateurId();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        UUID annee = dans(() -> annees.creer(j.getYear() + "-" + (j.getYear() + 1), j.minusDays(30), j.plusDays(300))).id();
        UUID filiere = dans(() -> filieres.creer("G" + ThreadLocalRandom.current().nextInt(100, 999),
                "Enseignement général", "Second cycle", "BAC", general)).id();
        UUID secondeA = dans(() -> classes.creer(annee, filiere, "2nde A", "2nde", (short) 3)).id();
        UUID secondeB = dans(() -> classes.creer(annee, filiere, "2nde B", "2nde", (short) 2)).id();
        UUID premiere = dans(() -> classes.creer(annee, filiere, "1re A", "1re", (short) 40)).id();
        inscrire("ANCIEN", "Un", secondeA);
        inscrire("ANCIEN", "Deux", secondeA);
        inscrire("ANCIEN", "Trois", secondeB);
        UUID censeur = membre(Role.CENSEUR, "Zongo", "Ines");
        UUID cpe = membre(Role.SURVEILLANT, "Sawadogo", "Issa");
        UUID intendant = membre(Role.INTENDANT, "Kafando", "Marc");
        UUID secretaire = membre(Role.SECRETARIAT, "Ouattara", "Awa");
        String telEnseignant = telephone();
        dans(() -> enseignants.engager(new DonneesEngagement(telEnseignant, null, "Kabore", "Paul", Sexe.M, null,
                TypeEngagement.TITULAIRE, j.minusDays(30), null, null)));
        UUID enseignant = dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == Role.ENSEIGNANT && m.telephone().endsWith(telEnseignant)).findFirst().orElseThrow()
                .utilisateurId();
        RequestPostProcessor pr = jeton(proviseur, "ADMIN_ECOLE");

        // Création : membres proposés d'après le personnel, offres par niveau et filière
        mvc.perform(post("/api/v1/annees/{a}/commissions-recrutement", annee).with(jeton(censeur, "CENSEUR"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        String c = envoyer(post("/api/v1/annees/{a}/commissions-recrutement", annee), pr, """
                {"intitule":"Complément d'effectif de la rentrée","dateSession":"%s","lieu":"Salle des professeurs",
                 "moyenneMinimale":10,"poidsExamen":0,"tailleListeAttente":1}""".formatted(j.plusDays(5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value(org.hamcrest.Matchers.startsWith("CR-")))
                .andExpect(jsonPath("$.statut").value("PREPARATION"))
                .andExpect(jsonPath("$.membres[0].qualite").value("PRESIDENT"))
                .andExpect(jsonPath("$.membres[?(@.qualite == 'VICE_PRESIDENT')].nom").value("ZONGO Ines"))
                .andExpect(jsonPath("$.membres[?(@.qualite == 'RAPPORTEUR')].nom").value("SAWADOGO Issa"))
                .andExpect(jsonPath("$.membres[?(@.fonction == 'Intendant')].nom").value("KAFANDO Marc"))
                .andExpect(jsonPath("$.membres[?(@.nom == 'À désigner')].fonction").value("Représentant de l'APE"))
                .andExpect(jsonPath("$.offres.length()").value(2))
                .andExpect(jsonPath("$.offres[?(@.niveau == '2nde')].disponibles").value(2))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(c, "$.id");
        String offre = ((List<String>) JsonPath.read(c, "$.offres[?(@.niveau == '2nde')].id")).get(0);
        String offre1re = ((List<String>) JsonPath.read(c, "$.offres[?(@.niveau == '1re')].id")).get(0);

        // Un enseignant non désigné ne voit rien ; désigné membre, il consulte
        mvc.perform(get("/api/v1/commissions-recrutement/{id}", id).with(jeton(enseignant, "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        envoyer(put("/api/v1/commissions-recrutement/{id}/membres", id), pr, """
                {"membres":[
                  {"qualite":"PRESIDENT","utilisateurId":"%s","fonction":"Proviseur"},
                  {"qualite":"VICE_PRESIDENT","utilisateurId":"%s","fonction":"Censeur"},
                  {"qualite":"RAPPORTEUR","utilisateurId":"%s","fonction":"CPE"},
                  {"qualite":"MEMBRE","utilisateurId":"%s","fonction":"Intendant"},
                  {"qualite":"MEMBRE","nom":"BAMBARA Salif","fonction":"Président de l'APE"},
                  {"qualite":"MEMBRE","utilisateurId":"%s","fonction":"Professeur principal de 2nde","present":false}]}"""
                .formatted(proviseur, censeur, cpe, intendant, enseignant))
                .andExpect(status().isOk()).andExpect(jsonPath("$.membres.length()").value(6));
        envoyer(put("/api/v1/commissions-recrutement/{id}/membres", id), pr, """
                {"membres":[{"qualite":"MEMBRE","nom":"X","fonction":"Y"}]}""")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRESIDENT_REQUIS"));
        mvc.perform(get("/api/v1/commissions-recrutement/{id}", id).with(jeton(enseignant, "ENSEIGNANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.droits.saisir").value(false));

        // Places : au plus les places disponibles
        envoyer(put("/api/v1/commissions-recrutement/{id}/offres", id), jeton(censeur, "CENSEUR"),
                "{\"offres\":[{\"offreId\":\"" + offre + "\",\"places\":3}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLACES_INSUFFISANTES"));
        envoyer(put("/api/v1/commissions-recrutement/{id}/offres", id), jeton(censeur, "CENSEUR"),
                "{\"offres\":[{\"offreId\":\"" + offre + "\",\"places\":2},{\"offreId\":\"" + offre1re + "\",\"places\":1}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.offres[?(@.niveau == '2nde')].places").value(2));

        // Candidatures (secrétariat) ; l'intendant n'en enregistre pas
        RequestPostProcessor sec = jeton(secretaire, "SECRETARIAT");
        String base = "/api/v1/commissions-recrutement/{id}/candidatures";
        envoyer(post(base, id), jeton(intendant, "INTENDANT"), candidat(offre, "X", "2010-01-01", "12"))
                .andExpect(status().isForbidden());
        envoyer(post(base, id), sec, candidat(offre, "X", "2010-01-01", "21")).andExpect(status().isBadRequest());
        String a = idDe(envoyer(post(base, id), sec, candidat(offre, "ALPHA", "2010-03-01", "14.5"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.numero").value(org.hamcrest.Matchers.startsWith("CAND-")))
                .andExpect(jsonPath("$.statut").value("DEPOSEE")));
        String b = idDe(envoyer(post(base, id), sec, candidat(offre, "BRAVO", "2009-05-01", "12")));
        String cc = idDe(envoyer(post(base, id), sec, candidat(offre, "CHARLIE", "2010-06-01", "12")));
        String d = idDe(envoyer(post(base, id), sec, candidat(offre, "DELTA", "2010-01-01", "9.5")));
        String e = idDe(envoyer(post(base, id), sec, candidat(offre, "ECHO", "2010-01-01", "11")));
        String f = idDe(envoyer(post(base, id), sec, candidat(offre, "FOXTROT", "2010-01-01", "13")));
        // Recevabilité : motif exigé pour une irrecevabilité
        envoyer(post("/api/v1/candidatures/{c}/recevabilite", f), jeton(censeur, "CENSEUR"), "{\"recevable\":false}")
                .andExpect(status().isBadRequest());
        envoyer(post("/api/v1/candidatures/{c}/recevabilite", f), jeton(censeur, "CENSEUR"),
                "{\"recevable\":false,\"motif\":\"Dossier incomplet : bulletins absents\"}")
                .andExpect(jsonPath("$.statut").value("IRRECEVABLE"));

        // Classement (aperçu) : à égalité, le plus jeune passe devant
        mvc.perform(get("/api/v1/commissions-recrutement/{id}/classement", id).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.offreId == '" + offre + "')].lignes[0].nom").value("ALPHA"))
                .andExpect(jsonPath("$[?(@.offreId == '" + offre + "')].lignes[1].nom").value("CHARLIE"))
                .andExpect(jsonPath("$[?(@.offreId == '" + offre + "')].lignes[1].decision").value("RETENUE"))
                .andExpect(jsonPath("$[?(@.offreId == '" + offre + "')].lignes[2].nom").value("BRAVO"))
                .andExpect(jsonPath("$[?(@.offreId == '" + offre + "')].lignes[2].decision").value("LISTE_ATTENTE"))
                .andExpect(jsonPath("$[?(@.offreId == '" + offre + "')].lignes[3].nom").value("ECHO"))
                .andExpect(jsonPath("$[?(@.offreId == '" + offre + "')].lignes[3].decision").value("NON_RETENUE"));

        // Délibération : par le président seulement ; ensuite tout est figé
        mvc.perform(post("/api/v1/commissions-recrutement/{id}/deliberation", id).with(jeton(censeur, "CENSEUR")))
                .andExpect(status().isForbidden());
        envoyer(post("/api/v1/commissions-recrutement/{id}/deliberation", id), pr,
                "{\"observations\":\"Séance tenue dans le calme.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("DELIBEREE"))
                .andExpect(jsonPath("$.droits.inscrire").value(true));
        envoyer(post(base, id), sec, candidat(offre, "TARDIF", "2010-01-01", "15"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMISSION_DELIBEREE"));
        mvc.perform(get(base, id).with(sec))
                .andExpect(jsonPath("$[?(@.id == '" + a + "')].statut").value("RETENUE"))
                .andExpect(jsonPath("$[?(@.id == '" + a + "')].rang").value(1))
                .andExpect(jsonPath("$[?(@.id == '" + d + "')].statut").value("NON_RETENUE"))
                .andExpect(jsonPath("$[?(@.id == '" + d + "')].motif").value("Moyenne inférieure à 10/20"))
                .andExpect(jsonPath("$[?(@.id == '" + e + "')].statut").value("NON_RETENUE"))
                .andExpect(jsonPath("$[?(@.id == '" + f + "')].statut").value("IRRECEVABLE"));

        // Procès-verbal et résultats
        byte[] pv = mvc.perform(get("/api/v1/commissions-recrutement/{id}/proces-verbal", id).with(pr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pv, 0, 5)).isEqualTo("%PDF-");
        mvc.perform(get("/api/v1/commissions-recrutement/{id}/resultats", id).param("format", "xlsx").with(sec))
                .andExpect(status().isOk());

        // Inscription par l'intendance : classe du bon niveau, candidat retenu seulement
        RequestPostProcessor it = jeton(intendant, "INTENDANT");
        envoyer(post("/api/v1/candidatures/{c}/inscription", a), it, "{\"classeId\":\"" + premiere + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASSE_HORS_OFFRE"));
        envoyer(post("/api/v1/candidatures/{c}/inscription", b), it, "{\"classeId\":\"" + secondeA + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANDIDAT_NON_RETENU"));
        envoyer(post("/api/v1/candidatures/{c}/inscription", a), sec, "{\"classeId\":\"" + secondeA + "\"}")
                .andExpect(status().isForbidden());
        envoyer(post("/api/v1/candidatures/{c}/inscription", a), it, "{\"classeId\":\"" + secondeA + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("INSCRITE"))
                .andExpect(jsonPath("$.classeInscription").value("2nde A"));
        String mode = dans(() -> new TransactionTemplate(transactions).execute(t -> jdbc.queryForObject("""
                select i.mode_admission from candidature x join inscription i on i.id = x.inscription_id
                where x.id = ?::uuid""", String.class, a)));
        assertThat(mode).isEqualTo("COMPLEMENT_EFFECTIF");

        // Désistement de CHARLIE : BRAVO est repêché ; la 2nde A est pleine, il va en 2nde B
        envoyer(post("/api/v1/candidatures/{c}/desistement", cc), it, "{\"motif\":\"Inscrit dans un autre lycée\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desistee.statut").value("DESISTEE"))
                .andExpect(jsonPath("$.repechee.id").value(b))
                .andExpect(jsonPath("$.repechee.statut").value("RETENUE"))
                .andExpect(jsonPath("$.repechee.repechee").value(true));
        envoyer(post("/api/v1/candidatures/{c}/inscription", b), it, "{\"classeId\":\"" + secondeA + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASSE_COMPLETE"));
        envoyer(post("/api/v1/candidatures/{c}/inscription", b), it, "{\"classeId\":\"" + secondeB + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("INSCRITE"));

        // Liste et clôture
        mvc.perform(get("/api/v1/annees/{a}/commissions-recrutement", annee).with(it))
                .andExpect(jsonPath("$[0].inscrites").value(2)).andExpect(jsonPath("$[0].places").value(3));
        mvc.perform(post("/api/v1/commissions-recrutement/{id}/cloture", id).with(pr))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statut").value("CLOSE"));
        envoyer(post("/api/v1/candidatures/{c}/desistement", a), it, "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMISSION_NON_DELIBEREE"));
    }

    // ------------------------------------------------------------------

    private static String candidat(String offre, String nom, String naissance, String moyenne) {
        return """
                {"offreId":"%s","nom":"%s","prenoms":"Candidat","sexe":"F","dateNaissance":"%s","lieuNaissance":"Ouagadougou",
                 "responsableNom":"%s","responsablePrenoms":"Parent","responsableTelephone":"%s","responsableLien":"MERE",
                 "etablissementOrigine":"CEG de Kossodo","classeOrigine":"3e A","moyenneAnnuelle":%s}"""
                .formatted(offre, nom, naissance, nom, telephone(), moyenne);
    }

    private static String idDe(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions envoyer(MockHttpServletRequestBuilder requete, RequestPostProcessor qui, String corps)
            throws Exception {
        return mvc.perform(requete.with(qui).contentType(MediaType.APPLICATION_JSON).content(corps));
    }

    private void inscrire(String nom, String prenoms, UUID classe) {
        UUID eleve = dans(() -> eleves.creer(new DonneesEleve(null, nom, prenoms, Sexe.M, LocalDate.of(2010, 1, 1), null,
                null, null, null), List.of())).eleve().id();
        dans(() -> inscriptions.inscrire(eleve, classe, false, StatutBourse.NON_BOURSIER));
    }

    private UUID membre(Role role, String nom, String prenoms) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, nom, prenoms, role));
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel)).findFirst().orElseThrow()
                .utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "7" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private RequestPostProcessor jeton(UUID utilisateur, String role) {
        return jwt()
                .jwt(x -> x.subject(utilisateur.toString())
                        .claim("tenant_id", ecole.toString())
                        .claim("typ_jeton", "ACCES")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
