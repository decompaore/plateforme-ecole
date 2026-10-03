package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.absences.AbsencesService;
import bf.edutech.plateforme.absences.Vues.SyntheseEleveVue;
import bf.edutech.plateforme.bulletins.ParametresBulletinsService.ParametresBulletins;
import bf.edutech.plateforme.bulletins.Vues.BulletinEleveVue;
import bf.edutech.plateforme.bulletins.Vues.BulletinResumeVue;
import bf.edutech.plateforme.bulletins.Vues.GenerationVue;
import bf.edutech.plateforme.bulletins.Vues.VerificationVue;
import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.ResultatsService;
import bf.edutech.plateforme.evaluations.Vues.ModuleVue;
import bf.edutech.plateforme.evaluations.Vues.MoyenneGroupeVue;
import bf.edutech.plateforme.evaluations.Vues.MoyenneMatiereVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatEleveVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatsPeriodeVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Bulletins : génération (résultats FIGÉS au moment de la génération, sur une
 * période verrouillée), PDF, publication aux familles avec SMS, consultation
 * par les parents, vérification publique d'un bulletin papier.
 * <p>
 * Tant qu'ils ne sont pas publiés, les bulletins peuvent être régénérés (après
 * une correction autorisée) ; une fois publiés, ils ne changent plus.
 */
@Service
public class BulletinsService {

    private static final String ALPHABET_CODE = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom HASARD = new SecureRandom();

    private final GenerationBulletinsRepository generations;
    private final BulletinRepository bulletins;
    private final BulletinLigneRepository lignes;
    private final AppreciationRepository appreciations;
    private final AvisConseilRepository avis;
    private final ParametresBulletinsService parametres;
    private final RenduPdf rendu;
    private final ResultatsService resultats;
    private final AbsencesService absences;
    private final ClassesService classes;
    private final PeriodesService periodes;
    private final AnneesService annees;
    private final ProfilsService profils;
    private final InscriptionsService inscriptions;
    private final ElevesService eleves;
    private final EspaceParentService espaceParent;
    private final EnseignantsService enseignants;
    private final ContactsEleves contacts;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock horloge;

    BulletinsService(GenerationBulletinsRepository generations, BulletinRepository bulletins,
            BulletinLigneRepository lignes, AppreciationRepository appreciations, AvisConseilRepository avis,
            ParametresBulletinsService parametres, RenduPdf rendu, ResultatsService resultats,
            AbsencesService absences, ClassesService classes, PeriodesService periodes, AnneesService annees,
            ProfilsService profils, InscriptionsService inscriptions, ElevesService eleves,
            EspaceParentService espaceParent, EnseignantsService enseignants, ContactsEleves contacts,
            NotificationsService notifications, AuditService audit, JdbcTemplate jdbc, Clock horloge) {
        this.generations = generations;
        this.bulletins = bulletins;
        this.lignes = lignes;
        this.appreciations = appreciations;
        this.avis = avis;
        this.parametres = parametres;
        this.rendu = rendu;
        this.resultats = resultats;
        this.absences = absences;
        this.classes = classes;
        this.periodes = periodes;
        this.annees = annees;
        this.profils = profils;
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.espaceParent = espaceParent;
        this.enseignants = enseignants;
        this.contacts = contacts;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
        this.horloge = horloge;
    }

    /** Génère (ou régénère, avant publication) les bulletins d'une classe pour une période verrouillée. */
    @Transactional
    public GenerationVue generer(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        PeriodeVue periode = periodes.trouver(periodeId);
        if (!periode.verrouillee()) {
            throw new RegleMetierException("PERIODE_NON_VERROUILLEE", "Verrouillez la période « " + periode.libelle()
                    + " » avant de générer les bulletins : les notes ne doivent plus changer");
        }
        generations.findByClasseIdAndPeriodeId(classeId, periodeId).ifPresent(ancienne -> {
            if (ancienne.estPubliee()) {
                throw new RegleMetierException("BULLETINS_PUBLIES",
                        "Les bulletins de cette période sont publiés : ils ne peuvent plus être régénérés");
            }
            generations.delete(ancienne);
            generations.flush();
        });

        ResultatsPeriodeVue r = resultats.calculer(classeId, periodeId);
        ProfilVue profil = profils.trouver(classe.profilId());
        String annee = annees.trouver(classe.anneeId()).libelle();
        ParametresBulletins param = parametres.lire();
        String etablissement = notifications.nomEtablissement();
        List<UUID> ids = r.eleves().stream().map(ResultatEleveVue::inscriptionId).toList();
        Map<UUID, SyntheseEleveVue> absencesParEleve = absences
                .syntheseClasse(classeId, periode.debut(), periode.fin()).stream()
                .collect(Collectors.toMap(SyntheseEleveVue::inscriptionId, Function.identity(), (a, b) -> a));
        Map<String, String> textes = new HashMap<>();
        Map<UUID, AvisConseil> avisParEleve = new HashMap<>();
        if (!ids.isEmpty()) {
            appreciations.findByPeriodeIdAndInscriptionIdIn(periodeId, ids)
                    .forEach(a -> textes.put(a.getInscriptionId() + ":" + a.getMatiereId(), a.getTexte()));
            avis.findByPeriodeIdAndInscriptionIdIn(periodeId, ids)
                    .forEach(a -> avisParEleve.put(a.getInscriptionId(), a));
        }
        List<MatiereDeClasseVue> matieres = classes.matieres(classeId);
        Map<UUID, String> nomsEnseignants = enseignants.nomsParEngagement(
                matieres.stream().map(MatiereDeClasseVue::engagementId).filter(Objects::nonNull).distinct().toList());
        Map<UUID, String> enseignantParMatiere = new HashMap<>();
        matieres.forEach(m -> {
            if (m.engagementId() != null && nomsEnseignants.containsKey(m.engagementId())) {
                enseignantParMatiere.put(m.matiereId(), nomsEnseignants.get(m.engagementId()));
            }
        });
        Map<UUID, InscriptionVue> infos = inscriptions.listerParClasse(classeId, false).stream()
                .collect(Collectors.toMap(InscriptionVue::id, Function.identity()));

        Instant maintenant = horloge.instant();
        GenerationBulletins generation = generations.save(new GenerationBulletins(classeId, periodeId, r.effectif(),
                r.moyenneClasse(), r.plusForte(), r.plusFaible(), r.tauxReussite(),
                UtilisateurConnecte.idSiConnecte().orElse(null), maintenant));
        for (ResultatEleveVue e : r.eleves()) {
            List<LigneBulletin> contenu = lignes(e, textes, enseignantParMatiere);
            AvisConseil a = avisParEleve.get(e.inscriptionId());
            Distinction distinction = a != null && a.getDistinction() != null ? a.getDistinction()
                    : param.proposer(e.moyenne());
            String appreciation = a != null ? a.getAppreciation() : null;
            SyntheseEleveVue abs = absencesParEleve.get(e.inscriptionId());
            BigDecimal heures = abs != null ? abs.heuresAbsence() : BigDecimal.ZERO;
            BigDecimal nonJustifiees = abs != null ? abs.heuresNonJustifiees() : BigDecimal.ZERO;
            int retards = abs != null ? abs.retards() : 0;
            String code = nouveauCode();
            InscriptionVue info = infos.get(e.inscriptionId());
            Bulletin bulletin = new Bulletin(generation.getId(), e.inscriptionId(), e.matricule(), e.nom(),
                    e.prenoms(), e.moyenne(), e.rang(), e.admis(),
                    e.tauxMaitrise(), distinction, appreciation, heures, nonJustifiees, retards, code);
            bulletin.joindrePdf(rendu.rendre(List.of(new DonneesBulletin(param, etablissement, annee,
                    periode.libelle(), classe.code(), profil.modele(), profil.vocabulaire().apprenant(), r.effectif(),
                    e.nom(), e.prenoms(), e.matricule(), info != null ? info.dateNaissance() : null,
                    info != null && info.redoublant(), e.moyenne(), e.rang(), e.admis(), e.tauxMaitrise(), contenu,
                    r.moyenneClasse(), r.plusForte(), r.plusFaible(), heures, nonJustifiees, retards, distinction,
                    appreciation, code, maintenant))));
            bulletins.save(bulletin);
            contenu.forEach(l -> lignes.save(new BulletinLigne(bulletin.getId(), l)));
        }
        audit.enregistrer("BULLETINS_GENERES", classe.code() + " / " + periode.libelle(),
                Map.of("bulletins", r.eleves().size()));
        return vue(generation, classe, periode);
    }

    @Transactional(readOnly = true)
    public GenerationVue consulter(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        GenerationBulletins g = generation(classeId, periodeId);
        return vue(g, classes.trouver(classeId), periodes.trouver(periodeId));
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID bulletinId) {
        UtilisateurConnecte.etablissementActif();
        return bulletins.findById(bulletinId).map(Bulletin::getPdf)
                .orElseThrow(() -> new RessourceIntrouvableException("Bulletin introuvable"));
    }

    /** Tous les bulletins de la classe dans un seul PDF, par ordre alphabétique (impression). */
    @Transactional(readOnly = true)
    public byte[] pdfClasse(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        GenerationBulletins g = generation(classeId, periodeId);
        List<byte[]> documents = bulletins.findByGenerationIdOrderByRangAsc(g.getId()).stream()
                .sorted(Comparator.comparing((Bulletin b) -> b.getNom() + " " + b.getPrenoms()))
                .map(Bulletin::getPdf)
                .toList();
        if (documents.isEmpty()) {
            throw new RegleMetierException("AUCUN_BULLETIN", "Aucun bulletin dans cette génération");
        }
        return rendu.fusionner(documents);
    }

    /** Publie les bulletins : visibles dans l'espace parent, SMS au contact prioritaire de chaque élève. */
    @Transactional
    public GenerationVue publier(UUID classeId, UUID periodeId) {
        UtilisateurConnecte.etablissementActif();
        ClasseVue classe = classes.trouver(classeId);
        PeriodeVue periode = periodes.trouver(periodeId);
        if (!periode.verrouillee()) {
            throw new RegleMetierException("PERIODE_NON_VERROUILLEE", "La période « " + periode.libelle()
                    + " » a été déverrouillée : régénérez les bulletins après l'avoir verrouillée à nouveau");
        }
        GenerationBulletins g = generation(classeId, periodeId);
        g.publier(UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant());
        List<Bulletin> liste = bulletins.findByGenerationIdOrderByRangAsc(g.getId());
        Map<UUID, ContactEleve> parInscription = contacts
                .pourInscriptions(liste.stream().map(Bulletin::getInscriptionId).toList());
        String etablissement = notifications.nomEtablissement();
        int sms = 0;
        for (Bulletin b : liste) {
            ContactEleve contact = parInscription.get(b.getInscriptionId());
            if (contact == null || contact.telephone() == null) {
                continue;
            }
            String resultat = b.getMoyenne() != null
                    ? "moyenne " + RenduPdf.nombre(b.getMoyenne(), 2) + "/20"
                            + (b.getRang() != null ? ", rang " + RenduPdf.rang(b.getRang()) + "/" + g.getEffectif() : "")
                    : b.getTauxMaitrise() != null ? "taux de maîtrise " + RenduPdf.nombre(b.getTauxMaitrise(), 2) + " %"
                    : "résultats disponibles";
            notifications.planifierSms("bulletin:" + b.getId(), contact.telephone(), contact.langueSms(),
                    etablissement + " : bulletin « " + periode.libelle() + " » de " + contact.prenoms() + " "
                            + contact.nom() + " (" + classe.code() + ") : " + resultat
                            + ". Consultez-le dans l'espace parent.");
            sms++;
        }
        audit.enregistrer("BULLETINS_PUBLIES", classe.code() + " / " + periode.libelle(),
                Map.of("bulletins", liste.size(), "sms", sms));
        return vue(g, classe, periode);
    }

    /** Bulletins publiés d'un enfant du parent connecté. */
    @Transactional(readOnly = true)
    public List<BulletinEleveVue> deMonEnfant(UUID eleveId) {
        if (!espaceParent.estMonEnfant(eleveId)) {
            throw new AccesRefuseException("Cet élève n'est pas rattaché à votre compte");
        }
        List<UUID> ids = eleves.trouver(eleveId).inscriptions().stream().map(InscriptionVue::id).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<BulletinEleveVue> vues = new ArrayList<>();
        for (Object[] ligne : bulletins.publiesDe(ids, StatutGeneration.PUBLIEE)) {
            Bulletin b = (Bulletin) ligne[0];
            GenerationBulletins g = (GenerationBulletins) ligne[1];
            vues.add(new BulletinEleveVue(b.getId(), periodes.trouver(g.getPeriodeId()).libelle(),
                    classes.trouver(g.getClasseId()).code(), b.getMoyenne(), b.getRang(), g.getEffectif(),
                    b.getTauxMaitrise(), b.getDistinction(), g.getPublieLe()));
        }
        return vues;
    }

    /** PDF d'un bulletin publié, pour le parent de l'élève. */
    @Transactional(readOnly = true)
    public byte[] pdfPourParent(UUID bulletinId) {
        UtilisateurConnecte.etablissementActif();
        Bulletin b = bulletins.findById(bulletinId)
                .orElseThrow(() -> new RessourceIntrouvableException("Bulletin introuvable"));
        GenerationBulletins g = generations.findById(b.getGenerationId()).orElseThrow();
        UUID eleveId = inscriptions.trouver(b.getInscriptionId()).eleveId();
        if (!g.estPubliee() || !espaceParent.estMonEnfant(eleveId)) {
            throw new RessourceIntrouvableException("Bulletin introuvable");
        }
        return b.getPdf();
    }

    /** Vérification publique d'un bulletin papier (sans connexion). */
    public VerificationVue verifier(String codeSaisi) {
        String code = codeSaisi == null ? "" : codeSaisi.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (code.length() != 10) {
            throw new RessourceIntrouvableException("Code de vérification inconnu");
        }
        List<VerificationVue> trouves = jdbc.query("""
                select etablissement, eleve, matricule, classe, periode, annee, moyenne, rang, effectif, publie_le
                from verifier_bulletin(?)""",
                (l, n) -> new VerificationVue(l.getString(1), l.getString(2), l.getString(3), l.getString(4),
                        l.getString(5), l.getString(6), l.getBigDecimal(7), (Integer) l.getObject(8), l.getInt(9),
                        l.getTimestamp(10).toInstant()),
                code);
        return trouves.stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Code de vérification inconnu"));
    }

    // ------------------------------------------------------------------

    /**
     * Lignes du bulletin : rubriques de matières (les matières générales d'abord),
     * chacune suivie de sa moyenne, puis les modules de compétences.
     */
    private static List<LigneBulletin> lignes(ResultatEleveVue e, Map<String, String> textes,
            Map<UUID, String> enseignantParMatiere) {
        Map<String, List<MoyenneMatiereVue>> parGroupe = new LinkedHashMap<>();
        for (MoyenneMatiereVue m : e.matieres()) {
            parGroupe.computeIfAbsent(m.groupe() != null ? m.groupe() : "", k -> new ArrayList<>()).add(m);
        }
        List<String> ordreGroupes = new ArrayList<>(parGroupe.keySet());
        ordreGroupes.sort(Comparator.comparing((String g) -> g.isEmpty() ? 0 : cle(g).contains("GENERA") ? 1 : 2));
        Map<String, MoyenneGroupeVue> moyennesGroupes = e.groupes().stream()
                .collect(Collectors.toMap(MoyenneGroupeVue::groupe, Function.identity(), (a, b) -> a));
        List<LigneBulletin> resultat = new ArrayList<>();
        int ordre = 1;
        for (String groupe : ordreGroupes) {
            for (MoyenneMatiereVue m : parGroupe.get(groupe)) {
                resultat.add(new LigneBulletin(ordre++, NatureLigne.MATIERE, m.code(), m.libelle(), m.groupe(),
                        m.coefficient(), m.moyenne(), m.points(), m.rang(), m.sansNote(),
                        textes.get(e.inscriptionId() + ":" + m.matiereId()), enseignantParMatiere.get(m.matiereId()),
                        null, null, null, null));
            }
            MoyenneGroupeVue moyenne = moyennesGroupes.get(groupe);
            if (!groupe.isEmpty() && moyenne != null) {
                resultat.add(new LigneBulletin(ordre++, NatureLigne.GROUPE, null, groupe, groupe,
                        moyenne.coefficients(), moyenne.moyenne(), null, null, false, null, null, null, null, null,
                        null));
            }
        }
        for (ModuleVue module : e.modules()) {
            resultat.add(new LigneBulletin(ordre++, NatureLigne.MODULE, module.code(), module.libelle(), null, null,
                    null, null, null, false, textes.get(e.inscriptionId() + ":" + module.matiereId()),
                    enseignantParMatiere.get(module.matiereId()), module.competences(), module.acquises(),
                    module.taux(), module.statut().name()));
        }
        return resultat;
    }

    private static String cle(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    private static String nouveauCode() {
        StringBuilder code = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            code.append(ALPHABET_CODE.charAt(HASARD.nextInt(ALPHABET_CODE.length())));
        }
        return code.toString();
    }

    private GenerationBulletins generation(UUID classeId, UUID periodeId) {
        return generations.findByClasseIdAndPeriodeId(classeId, periodeId)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucun bulletin généré pour cette période"));
    }

    private GenerationVue vue(GenerationBulletins g, ClasseVue classe, PeriodeVue periode) {
        List<BulletinResumeVue> liste = bulletins.findByGenerationIdOrderByRangAsc(g.getId()).stream()
                .map(b -> new BulletinResumeVue(b.getId(), b.getInscriptionId(), b.getMatricule(), b.getNom(),
                        b.getPrenoms(), b.getMoyenne(), b.getRang(), b.isAdmis(), b.getTauxMaitrise(),
                        b.getDistinction(), b.getCodeVerification()))
                .sorted(Comparator.comparing((BulletinResumeVue b) -> b.rang() == null ? Integer.MAX_VALUE : b.rang())
                        .thenComparing(BulletinResumeVue::nom)
                        .thenComparing(BulletinResumeVue::prenoms))
                .toList();
        return new GenerationVue(g.getId(), classe.id(), classe.code(), periode.id(), periode.libelle(), g.getStatut(),
                g.getEffectif(), g.getMoyenneClasse(), g.getTauxReussite(), g.getGenereLe(), g.getPublieLe(), liste);
    }
}
