package bf.edutech.plateforme.scolarite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.notifications.NotificationsService.StatutNotification;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.scolarite.Vues.DonneesFrais;
import bf.edutech.plateforme.scolarite.Vues.TrancheSaisie;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Moyens de paiement (v0.38) : l'administrateur choisit les moyens du guichet et ceux des
 * parents, enregistre un compte bancaire ; un parent déclare un virement (avec preuve),
 * l'intendance le valide (reçu) ou le refuse (SMS). Awa doit 30 000 FCFA.
 */
@SpringBootTest
@ActiveProfiles("test")
class MoyensPaiementIntegrationTest {

    /** En-tête PNG minimal : la preuve est reconnue à ses premiers octets. */
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private EspaceParentService espaceParent;
    @Autowired private MembresService membres;
    @Autowired private NotificationsService notifications;
    @Autowired private FraisService frais;
    @Autowired private WebApplicationContext contexte;

    private final LocalDate j = LocalDate.now(ZoneOffset.UTC);
    private MockMvc mvc;
    private UUID ecole;

    @Test
    void moyensConfiguresEtVirementDeclarePuisValideOuRefuse() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ecole = etablissements.creer("mp-" + suffixe, "Lycée " + suffixe, "7" + telephone().substring(1), "ADMIN", "Test")
                .etablissement().id();
        dans(() -> profils.initialiserProfilsTypes());
        UUID general = dans(() -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL")).findFirst()
                .orElseThrow().id();
        int an = j.getYear();
        AnneeVue annee = dans(() -> annees.creer(an + "-" + (an + 1), j.minusDays(30), j.plusDays(300)));
        UUID filiere = dans(() -> filieres.creer("F" + ThreadLocalRandom.current().nextInt(1000, 9999),
                "Enseignement général", "Premier cycle", "BEPC", general)).id();
        UUID classe = dans(() -> classes.creer(annee.id(), filiere, "3e C", "3e", null)).id();
        dans(() -> frais.creer(annee.id(), new DonneesFrais("Scolarité", 30_000L, null, null, null, null, null, null,
                List.of(new TrancheSaisie(j.minusDays(5), 30_000L)))));
        UUID admin = membre(Role.ADMIN_ECOLE);
        UUID intendant = membre(Role.INTENDANT);
        DossierEleveVue dossier = dans(() -> eleves.creer(new DonneesEleve(null, "OUEDRAOGO", "Awa", Sexe.F,
                LocalDate.of(2011, 2, 3), null, null, null, null),
                List.of(new DonneesResponsable("OUEDRAOGO", "Parent", telephone(), LienParente.MERE, null, null, true, null))));
        UUID awa = dans(() -> inscriptions.inscrire(dossier.eleve().id(), classe, false, StatutBourse.NON_BOURSIER)).id();
        UUID parent = dans(() -> espaceParent.ouvrir(dossier.responsables().get(0).responsableId())).utilisateurId();

        // Par défaut : tous les moyens au guichet, Mobile Money proposé (mais pas configuré), pas de virement
        mvc.perform(get("/api/v1/parametres/paiement").with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moyensGuichet.length()").value(6))
                .andExpect(jsonPath("$.virementParents").value(false))
                .andExpect(jsonPath("$.mobileMoneyDisponible").value(false));
        mvc.perform(get("/api/v1/espace-parent/inscriptions/{id}/moyens-paiement", awa).with(jeton(parent, "PARENT")))
                .andExpect(jsonPath("$.mobileMoney").value(false))
                .andExpect(jsonPath("$.virement").value(false));

        // Le virement exige un compte ; l'intendant ne configure rien
        parametres(admin, "{\"virementParents\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SANS_COMPTE_BANCAIRE"));
        mvc.perform(post("/api/v1/comptes-bancaires").with(jeton(intendant, "INTENDANT")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"banque\":\"Coris Bank\",\"titulaire\":\"Lycée\",\"numero\":\"BF0001\"}"))
                .andExpect(status().isForbidden());
        String compte = JsonPath.read(mvc.perform(post("/api/v1/comptes-bancaires").with(jeton(admin, "ADMIN_ECOLE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"banque\":\"Coris Bank International\",\"titulaire\":\"Lycée " + suffixe
                        + "\",\"numero\":\"bf042 01001 00123456789 12\",\"codeSwift\":\"cobfbfbf\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value("BF042 01001 00123456789 12"))
                .andExpect(jsonPath("$.codeSwift").value("COBFBFBF"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        parametres(admin, """
                {"moyensGuichet":["ESPECES","VIREMENT"],"mobileMoneyParents":true,"operateurs":["ORANGE_MONEY"],
                 "virementParents":true,"consigneVirement":"Indiquez la référence ci-dessous dans le motif du virement."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moyensGuichet.length()").value(2));

        // Guichet : le chèque n'est plus accepté
        mvc.perform(post("/api/v1/inscriptions/{id}/paiements", awa).with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"montant\":1000,\"moyen\":\"CHEQUE\",\"payeur\":\"FAMILLE\",\"referenceExterne\":\"CH-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOYEN_NON_ACCEPTE"));

        // Le parent voit le compte et la référence à indiquer
        mvc.perform(get("/api/v1/espace-parent/inscriptions/{id}/moyens-paiement", awa).with(jeton(parent, "PARENT")))
                .andExpect(jsonPath("$.virement").value(true))
                .andExpect(jsonPath("$.comptes[0].banque").value("Coris Bank International"))
                .andExpect(jsonPath("$.referenceAIndiquer").value(org.hamcrest.Matchers.startsWith("SCOL ")))
                .andExpect(jsonPath("$.consigneVirement").isNotEmpty());

        // Déclaration avec preuve ; doublon refusé ; plus que le reste refusé (virements en attente déduits)
        String v1 = JsonPath.read(declarer(parent, awa, compte, 20_000, "VIR-2026-001", PNG)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("EN_ATTENTE"))
                .andExpect(jsonPath("$.preuve").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        declarer(parent, awa, compte, 1_000, "vir-2026-001", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIREMENT_DEJA_DECLARE"));
        declarer(parent, awa, compte, 10_001, "VIR-2026-002", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONTANT_SUPERIEUR_AU_RESTE"));
        declarer(parent, awa, compte, 5_000, "VIR-2026-003", "pas une image".getBytes()).andExpect(status().isBadRequest());
        String v2 = JsonPath.read(declarer(parent, awa, compte, 5_000, "VIR-2026-003", null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        // L'intendance : liste, preuve, validation avec le montant reçu (reçu émis), refus avec motif
        mvc.perform(get("/api/v1/virements").with(jeton(intendant, "INTENDANT")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(v1))
                .andExpect(jsonPath("$[0].eleve").value("Awa OUEDRAOGO"));
        mvc.perform(get("/api/v1/virements/{id}/preuve", v1).with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/v1/virements").with(jeton(parent, "PARENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/virements/{id}/validation", v1).with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"montantRecu\":19500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("VALIDE"))
                .andExpect(jsonPath("$.montantValide").value(19_500))
                .andExpect(jsonPath("$.recuNumero").isNotEmpty());
        mvc.perform(post("/api/v1/virements/{id}/validation", v1).with(jeton(intendant, "INTENDANT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIREMENT_DEJA_TRAITE"));
        mvc.perform(post("/api/v1/virements/{id}/refus", v2).with(jeton(intendant, "INTENDANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"motif\":\"Introuvable sur le relevé du 7 octobre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("REFUSE"));
        mvc.perform(get("/api/v1/inscriptions/{id}/scolarite", awa).with(jeton(intendant, "INTENDANT")))
                .andExpect(jsonPath("$.payeFamille").value(19_500))
                .andExpect(jsonPath("$.paiements[0].moyen").value("VIREMENT"))
                .andExpect(jsonPath("$.paiements[0].referenceExterne").value("VIR-2026-001"));
        assertThat(dans(() -> notifications.dernieres(StatutNotification.EN_ATTENTE, 20)))
                .anySatisfy(n -> assertThat(n.message()).contains("19 500 FCFA reçus pour Awa OUEDRAOGO"))
                .anySatisfy(n -> assertThat(n.message()).contains("n'a pas été retenu : Introuvable sur le relevé"));

        // Le parent suit ses déclarations ; un virement refusé peut être déclaré à nouveau
        mvc.perform(get("/api/v1/espace-parent/inscriptions/{id}/virements", awa).with(jeton(parent, "PARENT")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == '" + v2 + "')].motifRefus").value("Introuvable sur le relevé du 7 octobre"));
        declarer(parent, awa, compte, 5_000, "VIR-2026-003", null).andExpect(status().isCreated());

        // Le dernier compte proposé ne se désactive pas tant que le virement est proposé
        mvc.perform(put("/api/v1/comptes-bancaires/{id}", compte).with(jeton(admin, "ADMIN_ECOLE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"banque\":\"Coris Bank International\",\"titulaire\":\"Lycée\",\"numero\":\"BF042 01001 00123456789 12\",\"actif\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DERNIER_COMPTE"));
    }

    // ------------------------------------------------------------------

    private ResultActions parametres(UUID admin, String json) throws Exception {
        return mvc.perform(put("/api/v1/parametres/paiement").with(jeton(admin, "ADMIN_ECOLE"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions declarer(UUID parent, UUID inscription, String compte, long montant, String reference,
            byte[] preuve) throws Exception {
        var requete = multipart("/api/v1/espace-parent/inscriptions/{id}/virements", inscription);
        if (preuve != null) {
            requete.file(new MockMultipartFile("fichier", "avis.png", "image/png", preuve));
        }
        return mvc.perform(requete.param("compteId", compte).param("montant", Long.toString(montant))
                .param("dateVirement", j.toString()).param("reference", reference).param("donneurOrdre", "OUEDRAOGO Mariam")
                .with(jeton(parent, "PARENT")));
    }

    private UUID membre(Role role) {
        String tel = telephone();
        dans(() -> membres.ajouter(tel, "KONE", "Ali", role));
        return dans(() -> membres.lister()).stream()
                .filter(m -> m.role() == role && m.telephone().endsWith(tel))
                .findFirst().orElseThrow().utilisateurId();
    }

    private <T> T dans(Supplier<T> action) {
        return TenantContext.executerPour(ecole, action);
    }

    private static String telephone() {
        return "5" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
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
