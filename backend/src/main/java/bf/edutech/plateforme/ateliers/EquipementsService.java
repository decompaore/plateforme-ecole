package bf.edutech.plateforme.ateliers;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.DemandeCloturePanne;
import bf.edutech.plateforme.ateliers.Vues.DemandePanne;
import bf.edutech.plateforme.ateliers.Vues.DonneesEquipement;
import bf.edutech.plateforme.ateliers.Vues.EquipementVue;
import bf.edutech.plateforme.ateliers.Vues.PanneVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Équipements des ateliers et pannes. Le responsable de l'atelier et le chef des travaux tiennent
 * l'inventaire ; tout enseignant technique de l'atelier peut signaler une panne, que le responsable
 * suit jusqu'à la réparation (ou la réforme si l'équipement est irréparable).
 */
@Service
public class EquipementsService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final EquipementRepository equipements;
    private final PanneRepository pannes;
    private final AteliersService ateliers;
    private final CatalogueService catalogue;
    private final AccesAteliers acces;
    private final Noms noms;
    private final AuditService audit;
    private final Clock horloge;

    EquipementsService(EquipementRepository equipements, PanneRepository pannes, AteliersService ateliers,
            CatalogueService catalogue, AccesAteliers acces, Noms noms, AuditService audit, Clock horloge) {
        this.equipements = equipements;
        this.pannes = pannes;
        this.ateliers = ateliers;
        this.catalogue = catalogue;
        this.acces = acces;
        this.noms = noms;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<EquipementVue> lister(UUID atelierId) {
        UtilisateurConnecte.etablissementActif();
        ateliers.charger(atelierId);
        acces.exigerLecture(atelierId);
        List<Equipement> liste = equipements.findByAtelierIdOrderByDesignationAscNumeroInventaireAsc(atelierId);
        Map<UUID, Panne> ouvertes = pannes.findByEquipementIdInAndStatut(liste.stream().map(Equipement::getId).toList(),
                StatutPanne.OUVERTE).stream().collect(Collectors.toMap(Panne::getEquipementId, Function.identity()));
        Map<UUID, String> parNom = ouvertes.isEmpty() ? Map.of() : noms.utilisateurs();
        return liste.stream().map(e -> vue(e, ouvertes.get(e.getId()), parNom)).toList();
    }

    @Transactional
    public EquipementVue creer(UUID atelierId, DonneesEquipement d) {
        UtilisateurConnecte.etablissementActif();
        Atelier atelier = ateliers.charger(atelierId);
        acces.exigerTenue(atelierId);
        Equipement e = new Equipement(atelierId);
        appliquer(e, d, atelier, true);
        equipements.save(e);
        audit.enregistrer("EQUIPEMENT_AJOUTE", e.getNumeroInventaire(), Map.of("atelier", atelier.getCode()));
        return vue(e, null, Map.of());
    }

    /** Modification ; l'état peut passer à BON, MANQUANT ou REFORME (une panne se signale à part). */
    @Transactional
    public EquipementVue modifier(UUID id, DonneesEquipement d) {
        UtilisateurConnecte.etablissementActif();
        Equipement e = charger(id);
        Atelier atelier = ateliers.charger(e.getAtelierId());
        acces.exigerTenue(e.getAtelierId());
        appliquer(e, d, atelier, false);
        Panne ouverte = pannes.findByEquipementIdAndStatut(id, StatutPanne.OUVERTE).orElse(null);
        if (d.etat() != null && d.etat() != e.getEtat()) {
            if (d.etat() == EtatEquipement.EN_PANNE) {
                throw new IllegalArgumentException("Pour une panne, utilisez « Signaler une panne »");
            }
            if (ouverte != null && d.etat() == EtatEquipement.BON) {
                throw new RegleMetierException("PANNE_OUVERTE", "Clôturez d'abord la panne en cours");
            }
            if (ouverte != null) {
                ouverte.cloturer(StatutPanne.IRREPARABLE, "Équipement retiré du service", null, UtilisateurConnecte.id(),
                        horloge.instant());
                ouverte = null;
            }
            e.changerEtat(d.etat());
            audit.enregistrer("EQUIPEMENT_ETAT", e.getNumeroInventaire(), Map.of("etat", d.etat().name()));
        }
        return vue(e, ouverte, ouverte == null ? Map.of() : noms.utilisateurs());
    }

    @Transactional(readOnly = true)
    public List<PanneVue> pannes(UUID equipementId) {
        UtilisateurConnecte.etablissementActif();
        Equipement e = charger(equipementId);
        acces.exigerLecture(e.getAtelierId());
        Map<UUID, String> parNom = noms.utilisateurs();
        return pannes.findByEquipementIdOrderBySignaleeLeDesc(equipementId).stream().map(p -> panne(p, parNom)).toList();
    }

    @Transactional
    public PanneVue signaler(UUID equipementId, DemandePanne d) {
        UtilisateurConnecte.etablissementActif();
        Equipement e = charger(equipementId);
        if (!acces.droits(e.getAtelierId()).signaler()) {
            throw new AccesRefuseException("Seuls les enseignants de l'atelier signalent une panne");
        }
        String description = Textes.obligatoire(d == null ? null : d.description(), "La description de la panne", 500);
        if (e.getEtat() == EtatEquipement.REFORME) {
            throw new RegleMetierException("EQUIPEMENT_REFORME", "Cet équipement est réformé");
        }
        if (pannes.findByEquipementIdAndStatut(equipementId, StatutPanne.OUVERTE).isPresent()) {
            throw new RegleMetierException("PANNE_DEJA_SIGNALEE", "Une panne est déjà signalée pour cet équipement");
        }
        Panne p = pannes.save(new Panne(equipementId, description, UtilisateurConnecte.id(), horloge.instant()));
        e.changerEtat(EtatEquipement.EN_PANNE);
        audit.enregistrer("PANNE_SIGNALEE", e.getNumeroInventaire(), null);
        return panne(p, noms.utilisateurs());
    }

    /** Clôture : réparée (l'équipement repasse en bon état) ou irréparable (il est réformé). */
    @Transactional
    public PanneVue cloturer(UUID panneId, DemandeCloturePanne d) {
        UtilisateurConnecte.etablissementActif();
        Panne p = pannes.findById(panneId).orElseThrow(() -> new RessourceIntrouvableException("Panne introuvable"));
        Equipement e = charger(p.getEquipementId());
        acces.exigerTenue(e.getAtelierId());
        if (p.getStatut() != StatutPanne.OUVERTE) {
            throw new RegleMetierException("PANNE_CLOTUREE", "Cette panne est déjà clôturée");
        }
        if (d == null || d.statut() == null || d.statut() == StatutPanne.OUVERTE) {
            throw new IllegalArgumentException("Indiquez REPAREE ou IRREPARABLE");
        }
        p.cloturer(d.statut(), Textes.facultatif(d.intervention(), "L'intervention", 500),
                Textes.montant(d.cout(), "Le coût"), UtilisateurConnecte.id(), horloge.instant());
        e.changerEtat(d.statut() == StatutPanne.REPAREE ? EtatEquipement.BON : EtatEquipement.REFORME);
        audit.enregistrer("PANNE_CLOTUREE", e.getNumeroInventaire(), Map.of("statut", d.statut().name()));
        return panne(p, noms.utilisateurs());
    }

    // ------------------------------------------------------------------

    Equipement charger(UUID id) {
        return equipements.findById(id).orElseThrow(() -> new RessourceIntrouvableException("Équipement introuvable"));
    }

    private void appliquer(Equipement e, DonneesEquipement d, Atelier atelier, boolean creation) {
        Article article = null;
        if (d.articleId() != null) {
            article = catalogue.charger(d.articleId());
            if (article.getNature() != NatureArticle.EQUIPEMENT) {
                throw new IllegalArgumentException("Cet article du catalogue est une matière d'œuvre, pas un équipement");
            }
        }
        String designation = d.designation() == null || d.designation().isBlank()
                ? (article == null ? null : article.getDesignation()) : d.designation();
        String numero = d.numeroInventaire() == null || d.numeroInventaire().isBlank()
                ? (creation ? numeroAutomatique(atelier) : e.getNumeroInventaire())
                : d.numeroInventaire().strip().toUpperCase(Locale.ROOT);
        if (numero.length() > 40) {
            throw new IllegalArgumentException("Numéro d'inventaire : 40 caractères au plus");
        }
        if (!numero.equals(e.getNumeroInventaire()) && equipements.existsByNumeroInventaire(numero)) {
            throw new RegleMetierException("NUMERO_EXISTANT", "Le numéro d'inventaire " + numero + " est déjà attribué");
        }
        if (d.dateAcquisition() != null && d.dateAcquisition().isAfter(LocalDate.now(horloge.withZone(FUSEAU)))) {
            throw new IllegalArgumentException("La date d'acquisition ne peut pas être dans le futur");
        }
        e.definir(d.articleId(), Textes.obligatoire(designation, "La désignation", 150), numero,
                Textes.facultatif(d.marque(), "La marque", 60), Textes.facultatif(d.numeroSerie(), "Le numéro de série", 60),
                d.dateAcquisition(), Textes.montant(d.valeur(), "La valeur"),
                Textes.facultatif(d.observations(), "Les observations", 500));
    }

    /** Code de l'atelier, année, rang : ELEC-2026-007. */
    private String numeroAutomatique(Atelier atelier) {
        String prefixe = atelier.getCode() + "-" + LocalDate.now(horloge.withZone(FUSEAU)).getYear() + "-";
        int rang = 1;
        while (equipements.existsByNumeroInventaire(prefixe + String.format("%03d", rang))) {
            rang++;
        }
        return prefixe + String.format("%03d", rang);
    }

    private static EquipementVue vue(Equipement e, Panne ouverte, Map<UUID, String> parNom) {
        return new EquipementVue(e.getId(), e.getAtelierId(), e.getArticleId(), e.getDesignation(),
                e.getNumeroInventaire(), e.getMarque(), e.getNumeroSerie(), e.getDateAcquisition(), e.getValeur(),
                e.getEtat(), e.getObservations(), ouverte == null ? null : panne(ouverte, parNom));
    }

    private static PanneVue panne(Panne p, Map<UUID, String> parNom) {
        return new PanneVue(p.getId(), p.getEquipementId(), p.getDescription(),
                p.getSignaleePar() == null ? null : parNom.get(p.getSignaleePar()), p.getSignaleeLe(), p.getStatut(),
                p.getIntervention(), p.getCout(), p.getClotureePar() == null ? null : parNom.get(p.getClotureePar()),
                p.getClotureeLe());
    }
}
