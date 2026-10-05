package bf.edutech.plateforme.eleves;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.ImportElevesService.RapportImport;
import bf.edutech.plateforme.eleves.Vues.DossierEleveVue;
import bf.edutech.plateforme.eleves.Vues.EspaceParentVue;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.eleves.Vues.ResponsableDeEleveVue;
import bf.edutech.plateforme.eleves.Vues.ResultatReinscription;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.FilieresService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.plateforme.EtablissementsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Tests du domaine Élèves et inscriptions sur une vraie base PostgreSQL (profil "test").
 * Chaque test crée son propre établissement, avec une année 2026-2027 et une filière.
 */
@SpringBootTest
@ActiveProfiles("test")
class ElevesIntegrationTest {

    @Autowired private EtablissementsService etablissements;
    @Autowired private ProfilsService profils;
    @Autowired private AnneesService annees;
    @Autowired private FilieresService filieres;
    @Autowired private ClassesService classes;
    @Autowired private ElevesService eleves;
    @Autowired private InscriptionsService inscriptions;
    @Autowired private ImportElevesService imports;
    @Autowired private EspaceParentService espaceParent;
    @Autowired private WebApplicationContext contexte;

    private MockMvc mvc;
    private UUID ecole;
    private AnneeVue annee;
    private UUID filiere;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        ecole = nouvelleEcole();
        dans(ecole, () -> profils.initialiserProfilsTypes());
        UUID general = dans(ecole, () -> profils.lister()).stream().filter(p -> p.code().equals("GENERAL"))
                .findFirst().orElseThrow().id();
        filiere = dans(ecole, () -> filieres.creer("GEN", "Enseignement général", "Premier cycle", "BEPC", general)).id();
        annee = dans(ecole, () -> annees.creer("2026-2027", LocalDate.of(2026, 10, 1), LocalDate.of(2027, 7, 31)));
    }

    @Test
    void leMatriculeEstGenereEtLesFreresEtSoeursPartagentLeResponsable() {
        String telPere = telephone();
        DossierEleveVue awa = dans(ecole, () -> eleves.creer(eleve("OUEDRAOGO", "Awa", Sexe.F),
                List.of(responsable("OUEDRAOGO", "Issa", telPere, LienParente.PERE, null))));
        DossierEleveVue ali = dans(ecole, () -> eleves.creer(eleve("Ouedraogo", "Ali", Sexe.M),
                List.of(responsable("OUEDRAOGO", "Issa", telPere, LienParente.PERE, null))));

        assertThat(awa.eleve().matricule()).matches("\\d{4}-00001");
        assertThat(ali.eleve().matricule()).matches("\\d{4}-00002");
        assertThat(ali.eleve().nom()).isEqualTo("OUEDRAOGO");
        UUID responsableAwa = awa.responsables().get(0).responsableId();
        assertThat(ali.responsables().get(0).responsableId()).isEqualTo(responsableAwa);
        assertThat(awa.responsables().get(0).contactPrioritaire()).isTrue();

        // Un nouveau contact prioritaire remplace l'ancien
        DossierEleveVue apres = dans(ecole, () -> eleves.ajouterResponsable(awa.eleve().id(),
                responsable("KABORE", "Mariam", telephone(), LienParente.MERE, true)));
        assertThat(apres.responsables()).hasSize(2);
        assertThat(apres.responsables()).filteredOn(ResponsableDeEleveVue::contactPrioritaire)
                .extracting(ResponsableDeEleveVue::nom).containsExactly("KABORE");

        // Le même responsable ne se rattache pas deux fois
        assertThatThrownBy(() -> dans(ecole, () -> eleves.ajouterResponsable(awa.eleve().id(),
                responsable("OUEDRAOGO", "Issa", telPere, LienParente.PERE, null))))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("RESPONSABLE_DEJA_LIE");

        // Recherche par nom, insensible à la casse
        assertThat(dans(ecole, () -> eleves.rechercher("ouedr", 0, 20)).total()).isEqualTo(2);
        assertThat(dans(ecole, () -> eleves.rechercher(awa.eleve().matricule(), 0, 20)).total()).isEqualTo(1);
    }

    @Test
    void lEffectifMaximalEtLesSortiesSontRespectes() {
        ClasseVue sixieme = dans(ecole, () -> classes.creer(annee.id(), filiere, "6e A", "6e", (short) 2));
        UUID e1 = nouvelEleve("SAWADOGO", "Paul");
        UUID e2 = nouvelEleve("SAWADOGO", "Anne");
        UUID e3 = nouvelEleve("ZONGO", "Luc");

        InscriptionVue i1 = dans(ecole, () -> inscriptions.inscrire(e1, sixieme.id(), false, StatutBourse.BOURSIER));
        dans(ecole, () -> inscriptions.inscrire(e2, sixieme.id(), true, null));
        assertThat(i1.statutBourse()).isEqualTo(StatutBourse.BOURSIER);
        assertThat(i1.classeCode()).isEqualTo("6e A");

        assertThatThrownBy(() -> dans(ecole, () -> inscriptions.inscrire(e1, sixieme.id(), false, null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("DEJA_INSCRIT");
        assertThatThrownBy(() -> dans(ecole, () -> inscriptions.inscrire(e3, sixieme.id(), false, null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("CLASSE_COMPLETE");

        // Un abandon libère une place ; l'inscription terminée n'est plus modifiable
        dans(ecole, () -> inscriptions.sortir(i1.id(), StatutInscription.ABANDON, aujourdhui(), "Départ"));
        assertThatThrownBy(() -> dans(ecole,
                () -> inscriptions.sortir(i1.id(), StatutInscription.TRANSFEREE, aujourdhui(), null)))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("INSCRIPTION_TERMINEE");
        dans(ecole, () -> inscriptions.inscrire(e3, sixieme.id(), false, null));

        assertThat(dans(ecole, () -> inscriptions.listerParClasse(sixieme.id(), false)))
                .extracting(InscriptionVue::prenoms).containsExactly("Anne", "Luc");
        assertThat(dans(ecole, () -> inscriptions.listerParClasse(sixieme.id(), true))).hasSize(3);

        // Une classe avec des élèves ne se supprime pas
        assertThatThrownBy(() -> dans(ecole, () -> {
            classes.supprimer(sixieme.id());
            return null;
        })).isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("CLASSE_NON_VIDE");
    }

    @Test
    void laReinscriptionPasseLesElevesDansLAnneeSuivante() {
        ClasseVue sixieme = dans(ecole, () -> classes.creer(annee.id(), filiere, "6e A", "6e", null));
        UUID e1 = nouvelEleve("TRAORE", "Moussa");
        UUID e2 = nouvelEleve("TRAORE", "Fatim");
        InscriptionVue i1 = dans(ecole, () -> inscriptions.inscrire(e1, sixieme.id(), false, StatutBourse.BOURSIER));
        InscriptionVue i2 = dans(ecole, () -> inscriptions.inscrire(e2, sixieme.id(), false, null));
        dans(ecole, () -> inscriptions.sortir(i2.id(), StatutInscription.TRANSFEREE, aujourdhui(), "Mutation"));

        AnneeVue suivante = dans(ecole, () -> annees.copier(annee.id(), "2027-2028",
                LocalDate.of(2027, 10, 1), LocalDate.of(2028, 7, 31))).annee();
        ClasseVue cinquieme = dans(ecole, () -> classes.creer(suivante.id(), filiere, "5e A", "5e", null));

        ResultatReinscription resultat = dans(ecole,
                () -> inscriptions.reinscrire(cinquieme.id(), List.of(i1.id(), i2.id()), false, false));
        assertThat(resultat.reinscrits()).isEqualTo(1);
        assertThat(resultat.nonReinscrits()).singleElement()
                .satisfies(n -> assertThat(n.motif()).contains("sorti"));

        InscriptionVue nouvelle = dans(ecole, () -> inscriptions.listerParClasse(cinquieme.id(), false)).get(0);
        assertThat(nouvelle.eleveId()).isEqualTo(e1);
        assertThat(nouvelle.statutBourse()).isEqualTo(StatutBourse.NON_BOURSIER); // se renouvelle chaque année
        assertThat(dans(ecole, () -> eleves.trouver(e1)).inscriptions()).hasSize(2);

        // Une deuxième réinscription est signalée, sans erreur
        ResultatReinscription encore = dans(ecole,
                () -> inscriptions.reinscrire(cinquieme.id(), List.of(i1.id()), false, false));
        assertThat(encore.reinscrits()).isZero();
        assertThat(encore.nonReinscrits().get(0).motif()).startsWith("Déjà inscrit");

        // Le changement de classe reste dans la même année
        assertThatThrownBy(() -> dans(ecole, () -> inscriptions.changerClasse(nouvelle.id(), sixieme.id())))
                .isInstanceOf(RegleMetierException.class).extracting("code").isEqualTo("CLASSE_AUTRE_ANNEE");
    }

    @Test
    void lImportSimuleSignaleLesErreursPuisImporteLesLignesValides() throws Exception {
        dans(ecole, () -> classes.creer(annee.id(), filiere, "6e A", "6e", (short) 60));
        String tel = telephone();
        byte[] fichier = classeur(
                new String[] { null, "Kabore", "Aminata", "F", "14/02/2014", "Koudougou", "6e A", "N", "B",
                    "KABORE", "Salif", tel, "PERE" },
                new String[] { null, "Kabore", "Issouf", "M", "03/09/2015", null, "6e a", null, null,
                    "KABORE", "Salif", tel, "PERE" },
                new String[] { null, "Nikiema", "Rose", "X", "01/01/2014", null, "6e A", null, null, null, null,
                    null, null },
                new String[] { null, "Ilboudo", "Jean", "M", "01/01/2014", null, "3e Z", null, null, null, null,
                    null, null },
                new String[] { null, "Yameogo", "Eric", "M", "32/13/2014", null, "6e A", null, null, null, null,
                    null, null },
                new String[] { null, "KABORE", "Aminata", "F", "14/02/2014", null, "6e A", null, null, null, null,
                    null, null });

        RapportImport simulation = dans(ecole,
                () -> imports.importer(annee.id(), new ByteArrayInputStream(fichier), true));
        assertThat(simulation.lignes()).isEqualTo(6);
        assertThat(simulation.valides()).isEqualTo(2);
        assertThat(simulation.importees()).isZero();
        assertThat(simulation.erreurs()).extracting(ImportElevesService.LigneRapport::ligne)
                .containsExactly(4, 5, 6, 7);
        assertThat(dans(ecole, () -> eleves.rechercher(null, 0, 20)).total()).isZero();

        RapportImport reel = dans(ecole, () -> imports.importer(annee.id(), new ByteArrayInputStream(fichier), false));
        assertThat(reel.importees()).isEqualTo(2);
        assertThat(dans(ecole, () -> eleves.rechercher("kabore", 0, 20)).elements())
                .extracting(e -> dans(ecole, () -> eleves.trouver(e.id())).responsables().get(0).telephone())
                .containsOnly("+226" + tel);

        // Réimporter le même fichier ne crée pas de doublons
        RapportImport encore = dans(ecole, () -> imports.importer(annee.id(), new ByteArrayInputStream(fichier), false));
        assertThat(encore.importees()).isZero();

        // Le modèle se télécharge et contient la liste des classes
        byte[] modele = dans(ecole, () -> imports.modele(annee.id())).contenu();
        try (XSSFWorkbook classeur = new XSSFWorkbook(new ByteArrayInputStream(modele))) {
            assertThat(classeur.getSheet("Classes").getRow(1).getCell(0).getStringCellValue()).isEqualTo("6e A");
        }

        // Même contrôle par l'API (envoi multipart)
        mvc.perform(multipart("/api/v1/annees/{id}/eleves/import", annee.id())
                        .file(new MockMultipartFile("fichier", "eleves.xlsx", ImportElevesController.XLSX.toString(),
                                fichier))
                        .param("simulation", "true")
                        .with(jeton(ecole, UUID.randomUUID(), "SECRETARIAT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulation").value(true))
                .andExpect(jsonPath("$.valides").value(0));
    }

    @Test
    void leParentConsulteSesEnfantsDansSonEspace() throws Exception {
        ClasseVue sixieme = dans(ecole, () -> classes.creer(annee.id(), filiere, "6e B", "6e", null));
        DossierEleveVue dossier = dans(ecole, () -> eleves.creer(eleve("BAMBARA", "Salif", Sexe.M),
                List.of(responsable("BAMBARA", "Awa", telephone(), LienParente.MERE, null))));
        dans(ecole, () -> inscriptions.inscrire(dossier.eleve().id(), sixieme.id(), false, null));
        UUID responsableId = dossier.responsables().get(0).responsableId();

        EspaceParentVue espace = dans(ecole, () -> espaceParent.ouvrir(responsableId));
        assertThat(espace.motDePasseTemporaire()).isNotBlank();
        EspaceParentVue rouvert = dans(ecole, () -> espaceParent.ouvrir(responsableId));
        assertThat(rouvert.utilisateurId()).isEqualTo(espace.utilisateurId());
        assertThat(rouvert.motDePasseTemporaire()).isNull();

        mvc.perform(get("/api/v1/espace-parent/enfants").with(jeton(ecole, espace.utilisateurId(), "PARENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].classeCode").value("6e B"))
                .andExpect(jsonPath("$[0].lien").value("MERE"));

        // Un parent ne consulte pas les dossiers ; un enseignant non affecté à la classe ne voit pas sa liste
        // (le cas de l'enseignant affecté est testé dans EnseignantsIntegrationTest)
        mvc.perform(get("/api/v1/eleves").with(jeton(ecole, espace.utilisateurId(), "PARENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/eleves/{id}", dossier.eleve().id()).with(jeton(ecole, UUID.randomUUID(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/classes/{id}/inscriptions", sixieme.id())
                        .with(jeton(ecole, UUID.randomUUID(), "ENSEIGNANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/classes/{id}/inscriptions", sixieme.id())
                        .with(jeton(ecole, UUID.randomUUID(), "SURVEILLANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nom").value("BAMBARA"));
        mvc.perform(post("/api/v1/eleves").with(jeton(ecole, UUID.randomUUID(), "SECRETARIAT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nom":"Compaore","prenoms":"Nina","sexe":"F","dateNaissance":"2013-05-20",
                                 "responsables":[{"nom":"Compaore","prenoms":"Adama","telephone":"%s","lien":"PERE"}]}
                                """.formatted(telephone())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eleve.nom").value("COMPAORE"))
                .andExpect(jsonPath("$.responsables[0].contactPrioritaire").value(true));
    }

    @Test
    void lesComptesDesParentsDUneClasseSOuvrentEnUneFois() throws Exception {
        ClasseVue classe = dans(ecole, () -> classes.creer(annee.id(), filiere, "6e C", "6e", null));
        ClasseVue vide = dans(ecole, () -> classes.creer(annee.id(), filiere, "6e D", "6e", null));
        String telMere = telephone();
        DossierEleveVue aine = dans(ecole, () -> eleves.creer(eleve("KABORE", "Ali", Sexe.M),
                List.of(responsable("KABORE", "Rasmata", telMere, LienParente.MERE, null))));
        DossierEleveVue cadette = dans(ecole, () -> eleves.creer(eleve("KABORE", "Fanta", Sexe.F),
                List.of(responsable("KABORE", "Rasmata", telMere, LienParente.MERE, null))));
        DossierEleveVue autre = dans(ecole, () -> eleves.creer(eleve("ZONGO", "Issa", Sexe.M),
                List.of(responsable("ZONGO", "Moussa", telephone(), LienParente.TUTEUR, null))));
        for (DossierEleveVue d : List.of(aine, cadette, autre)) {
            dans(ecole, () -> inscriptions.inscrire(d.eleve().id(), classe.id(), false, null));
        }
        // Le tuteur a déjà son espace : il garde son mot de passe
        dans(ecole, () -> espaceParent.ouvrir(autre.responsables().get(0).responsableId()));

        List<EspaceParentService.AccesParent> acces = dans(ecole, () -> espaceParent.ouvrirPourClasse(classe.id()));
        assertThat(acces).hasSize(2);
        assertThat(acces.get(0).parent()).isEqualTo("KABORE Rasmata");
        assertThat(acces.get(0).lien()).isEqualTo("Mère");
        assertThat(acces.get(0).eleves()).contains("KABORE Ali").contains("KABORE Fanta");
        assertThat(acces.get(0).motDePasseTemporaire()).isNotBlank();
        assertThat(acces.get(1).parent()).isEqualTo("ZONGO Moussa");
        assertThat(acces.get(1).motDePasseTemporaire()).isNull();
        assertThat(dans(ecole, () -> eleves.trouver(aine.eleve().id())).responsables().get(0).espaceParentOuvert()).isTrue();

        // Fiche de remise : PDF par défaut, Excel sur demande ; réservée à l'administration et au secrétariat
        byte[] pdf = mvc.perform(post("/api/v1/classes/{id}/espaces-parents", classe.id())
                        .with(jeton(ecole, UUID.randomUUID(), "SECRETARIAT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        byte[] excel = mvc.perform(post("/api/v1/classes/{id}/espaces-parents", classe.id()).param("format", "xlsx")
                        .with(jeton(ecole, UUID.randomUUID(), "ADMIN_ECOLE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(excel, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        mvc.perform(post("/api/v1/classes/{id}/espaces-parents", classe.id())
                .with(jeton(ecole, UUID.randomUUID(), "ENSEIGNANT"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/classes/{id}/espaces-parents", vide.id())
                        .with(jeton(ecole, UUID.randomUUID(), "SECRETARIAT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCUN_RESPONSABLE"));
    }

    @Test
    void uneEcoleNeVoitPasLesElevesDUneAutre() {
        UUID id = nouvelEleve("SOME", "Ines");
        UUID autre = nouvelleEcole();

        assertThat(dans(autre, () -> eleves.rechercher(null, 0, 20)).total()).isZero();
        assertThatThrownBy(() -> dans(autre, () -> eleves.trouver(id))).hasMessageContaining("introuvable");
        // Chaque établissement a sa propre numérotation
        DossierEleveVue premier = dans(autre, () -> eleves.creer(eleve("SOME", "Ines", Sexe.F), List.of()));
        assertThat(premier.eleve().matricule()).endsWith("-00001");
    }

    // ------------------------------------------------------------------

    /** Date du jour de l'application (horloge UTC, comme la date d'inscription). */
    private static LocalDate aujourdhui() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static <T> T dans(UUID etablissement, Supplier<T> action) {
        return TenantContext.executerPour(etablissement, action);
    }

    private UUID nouvelEleve(String nom, String prenoms) {
        return dans(ecole, () -> eleves.creer(eleve(nom, prenoms, Sexe.M), List.of())).eleve().id();
    }

    private static DonneesEleve eleve(String nom, String prenoms, Sexe sexe) {
        LocalDate naissance = LocalDate.of(2012, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(1500));
        return new DonneesEleve(null, nom, prenoms, sexe, naissance, "Ouagadougou", null, null, null);
    }

    private static DonneesResponsable responsable(String nom, String prenoms, String telephone, LienParente lien,
            Boolean prioritaire) {
        return new DonneesResponsable(nom, prenoms, telephone, lien, null, null, true, prioritaire);
    }

    /** Numéro national à 8 chiffres, aléatoire : les tests n'entrent pas en collision. */
    private static String telephone() {
        return "6" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private UUID nouvelleEcole() {
        String suffixe = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return etablissements.creer("elev-" + suffixe, "École " + suffixe, "7" + telephone().substring(1), "ADMIN",
                "Test").etablissement().id();
    }

    /** Classeur Excel au format du modèle (ligne 1 : en-têtes). */
    private static byte[] classeur(String[]... lignes) throws IOException {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Sheet feuille = classeur.createSheet(FormatImport.FEUILLE);
            Row entete = feuille.createRow(0);
            for (int c = 0; c < FormatImport.ENTETES.length; c++) {
                entete.createCell(c).setCellValue(FormatImport.ENTETES[c]);
            }
            for (int r = 0; r < lignes.length; r++) {
                Row ligne = feuille.createRow(r + 1);
                for (int c = 0; c < lignes[r].length; c++) {
                    if (lignes[r][c] != null) {
                        ligne.createCell(c).setCellValue(lignes[r][c]);
                    }
                }
            }
            classeur.write(sortie);
            return sortie.toByteArray();
        }
    }

    private static RequestPostProcessor jeton(UUID etablissement, UUID utilisateur, String role) {
        return jwt()
                .jwt(j -> j.subject(utilisateur.toString())
                        .claim("tenant_id", etablissement.toString())
                        .claim("typ_jeton", "ACCES")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("TYPE_ACCES"), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
