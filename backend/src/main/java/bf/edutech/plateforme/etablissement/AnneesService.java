package bf.edutech.plateforme.etablissement;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ResultatCopie;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Années scolaires : création, cycle de vie (préparation, active, clôturée,
 * archivée) et création de l'année suivante par copie de la structure.
 */
@Service
public class AnneesService {

    private static final Pattern FORMAT_LIBELLE = Pattern.compile("^(\\d{4})-(\\d{4})$");

    private final AnneeScolaireRepository annees;
    private final PeriodeRepository periodes;
    private final ClasseRepository classes;
    private final ClasseMatiereRepository matieresDeClasse;
    private final ProfilsService profils;
    private final AuditService audit;
    private final AccesAnnees acces;

    AnneesService(AnneeScolaireRepository annees, PeriodeRepository periodes, ClasseRepository classes,
            ClasseMatiereRepository matieresDeClasse, ProfilsService profils, AuditService audit, AccesAnnees acces) {
        this.annees = annees;
        this.periodes = periodes;
        this.classes = classes;
        this.matieresDeClasse = matieresDeClasse;
        this.profils = profils;
        this.audit = audit;
        this.acces = acces;
    }

    @Transactional(readOnly = true)
    public List<AnneeVue> lister() {
        UtilisateurConnecte.etablissementActif();
        return annees.findAllByOrderByDebutDesc().stream().map(AnneeVue::depuis).toList();
    }

    @Transactional(readOnly = true)
    public AnneeVue trouver(UUID id) {
        return AnneeVue.depuis(charger(id));
    }

    @Transactional(readOnly = true)
    public AnneeVue active() {
        UtilisateurConnecte.etablissementActif();
        return annees.findByEtat(EtatAnnee.ACTIVE).map(AnneeVue::depuis)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune année scolaire active"));
    }

    @Transactional
    public AnneeVue creer(String libelle, LocalDate debut, LocalDate fin) {
        UtilisateurConnecte.etablissementActif();
        AnneeScolaire annee = creerSansAudit(libelle, debut, fin);
        audit.enregistrer("ANNEE_CREEE", annee.getLibelle(), null);
        return AnneeVue.depuis(annee);
    }

    /**
     * Passe l'année à l'état ACTIVE. Conditions : aucune autre année active,
     * au moins une classe, et des périodes définies pour chaque profil utilisé.
     */
    @Transactional
    public AnneeVue ouvrir(UUID id) {
        AnneeScolaire annee = charger(id);
        annees.findByEtat(EtatAnnee.ACTIVE).ifPresent(autre -> {
            throw new RegleMetierException("ANNEE_ACTIVE_EXISTE",
                    "L'année " + autre.getLibelle() + " est encore active : clôturez-la d'abord");
        });
        if (classes.countByAnneeId(id) == 0) {
            throw new RegleMetierException("AUCUNE_CLASSE", "Créez au moins une classe avant d'ouvrir l'année");
        }
        for (UUID profilId : classes.profilsUtilises(id)) {
            if (!periodes.existsByAnneeIdAndProfilId(id, profilId)) {
                throw new RegleMetierException("PERIODES_MANQUANTES", "Définissez les périodes du profil « "
                        + profils.trouver(profilId).libelle() + " » avant d'ouvrir l'année");
            }
        }
        annee.ouvrir();
        audit.enregistrer("ANNEE_OUVERTE", annee.getLibelle(), null);
        return AnneeVue.depuis(annee);
    }

    /** Clôture l'année : toutes ses périodes doivent être verrouillées. */
    @Transactional
    public AnneeVue cloturer(UUID id) {
        AnneeScolaire annee = charger(id);
        long nonVerrouillees = periodes.countByAnneeIdAndVerrouilleeFalse(id);
        if (nonVerrouillees > 0) {
            throw new RegleMetierException("PERIODES_NON_VERROUILLEES",
                    nonVerrouillees + " période(s) ne sont pas verrouillées : verrouillez-les avant la clôture");
        }
        annee.cloturer();
        audit.enregistrer("ANNEE_CLOTUREE", annee.getLibelle(), null);
        return AnneeVue.depuis(annee);
    }

    /** Archive l'année : elle devient consultable en lecture seule. */
    @Transactional
    public AnneeVue archiver(UUID id) {
        AnneeScolaire annee = charger(id);
        annee.archiver();
        audit.enregistrer("ANNEE_ARCHIVEE", annee.getLibelle(), null);
        return AnneeVue.depuis(annee);
    }

    /**
     * Crée l'année suivante en copiant la structure d'une année existante :
     * classes, matières et coefficients, périodes (décalées du nombre d'années
     * d'écart). Les périodes qui ne tombent pas dans la nouvelle année sont ignorées.
     */
    @Transactional
    public ResultatCopie copier(UUID sourceId, String libelle, LocalDate debut, LocalDate fin) {
        AnneeScolaire source = charger(sourceId);
        AnneeScolaire cible = creerSansAudit(libelle, debut, fin);
        long decalageAnnees = ChronoUnit.YEARS.between(source.getDebut().withDayOfMonth(1),
                cible.getDebut().withDayOfMonth(1));

        int nbPeriodes = 0;
        for (Periode p : periodes.findByAnneeIdOrderByProfilIdAscOrdreAsc(sourceId)) {
            LocalDate nouveauDebut = p.getDebut().plusYears(decalageAnnees);
            LocalDate nouvelleFin = p.getFin().plusYears(decalageAnnees);
            if (cible.contient(nouveauDebut) && cible.contient(nouvelleFin)) {
                periodes.save(new Periode(cible.getId(), p.getProfilId(), p.getLibelle(), p.getOrdre(), nouveauDebut,
                        nouvelleFin));
                nbPeriodes++;
            }
        }

        int nbClasses = 0;
        int nbMatieres = 0;
        Map<UUID, UUID> correspondance = new HashMap<>();
        for (Classe c : classes.findByAnneeIdOrderByCodeAsc(sourceId)) {
            Classe copie = classes.save(new Classe(cible.getId(), c.getFiliereId(), c.getCode(), c.getNiveau(),
                    c.getEffectifMax()));
            correspondance.put(c.getId(), copie.getId());
            nbClasses++;
            for (ClasseMatiere cm : matieresDeClasse.findByClasseId(c.getId())) {
                ClasseMatiere nouvelle = new ClasseMatiere(copie.getId(), cm.getMatiereId());
                nouvelle.definir(cm.getCoefficient(), cm.getGroupe(), cm.getVolumeHebdo(), cm.getVolumeTotal());
                matieresDeClasse.save(nouvelle);
                nbMatieres++;
            }
        }
        audit.enregistrer("ANNEE_COPIEE", cible.getLibelle(),
                Map.of("source", source.getLibelle(), "classes", nbClasses, "periodes", nbPeriodes));
        return new ResultatCopie(AnneeVue.depuis(cible), nbClasses, nbMatieres, nbPeriodes);
    }

    private AnneeScolaire charger(UUID id) {
        return acces.charger(id);
    }

    private AnneeScolaire creerSansAudit(String libelleSaisi, LocalDate debut, LocalDate fin) {
        String libelle = libelleSaisi.trim();
        Matcher m = FORMAT_LIBELLE.matcher(libelle);
        if (!m.matches() || Integer.parseInt(m.group(2)) != Integer.parseInt(m.group(1)) + 1) {
            throw new IllegalArgumentException("Libellé attendu de la forme 2026-2027");
        }
        if (!fin.isAfter(debut) || ChronoUnit.DAYS.between(debut, fin) > 400) {
            throw new IllegalArgumentException("Dates invalides : la fin doit suivre le début, sur 400 jours au plus");
        }
        if (annees.existsByLibelle(libelle)) {
            throw new RegleMetierException("ANNEE_EXISTANTE", "L'année " + libelle + " existe déjà");
        }
        return annees.save(new AnneeScolaire(libelle, debut, fin));
    }
}
