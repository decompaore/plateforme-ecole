package bf.edutech.plateforme.scolarite;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.scolarite.Vues.ExonerationVue;
import bf.edutech.plateforme.scolarite.Vues.PriseEnChargeVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Bourses et exonérations.
 * <ul>
 * <li>Le statut de bourse (boursier, semi-boursier, non boursier) est porté par
 * l'inscription de l'année (module Élèves).</li>
 * <li>La prise en charge relie une inscription boursière à l'organisme qui paie,
 * avec son taux ; sans prise en charge, le taux par défaut du statut s'applique.</li>
 * <li>L'exonération est une réduction accordée par l'école elle-même, sur un frais.</li>
 * </ul>
 */
@Service
public class BoursesService {

    private final PriseEnChargeRepository prisesEnCharge;
    private final ExonerationRepository exonerations;
    private final OrganismesService organismes;
    private final FraisService frais;
    private final ParametresScolariteService parametres;
    private final InscriptionsService inscriptions;
    private final AuditService audit;
    private final Clock horloge;

    BoursesService(PriseEnChargeRepository prisesEnCharge, ExonerationRepository exonerations,
            OrganismesService organismes, FraisService frais, ParametresScolariteService parametres,
            InscriptionsService inscriptions, AuditService audit, Clock horloge) {
        this.prisesEnCharge = prisesEnCharge;
        this.exonerations = exonerations;
        this.organismes = organismes;
        this.frais = frais;
        this.parametres = parametres;
        this.inscriptions = inscriptions;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public Optional<PriseEnChargeVue> priseEnCharge(UUID inscriptionId) {
        UtilisateurConnecte.etablissementActif();
        return prisesEnCharge.findByInscriptionId(inscriptionId).map(this::vue);
    }

    /** Enregistre ou remplace la prise en charge ; taux absent : taux par défaut du statut. */
    @Transactional
    public PriseEnChargeVue definirPriseEnCharge(UUID inscriptionId, UUID organismeId, BigDecimal taux,
            String referenceDecision, LocalDate dateDecision) {
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        if (i.statutBourse() == StatutBourse.NON_BOURSIER) {
            throw new RegleMetierException("ELEVE_NON_BOURSIER", i.prenoms() + " " + i.nom()
                    + " n'est pas boursier cette année : changez d'abord son statut de bourse");
        }
        OrganismeFinanceur organisme = organismes.trouver(organismeId);
        if (!organisme.isActif()) {
            throw new RegleMetierException("ORGANISME_INACTIF", "L'organisme « " + organisme.getNom()
                    + " » est désactivé");
        }
        BigDecimal t = taux;
        if (t != null && (t.signum() <= 0 || t.compareTo(BigDecimal.valueOf(100)) > 0 || t.scale() > 2)) {
            throw new IllegalArgumentException("Le taux de prise en charge est compris entre 0,01 et 100 %");
        }
        if (t == null) {
            t = parametres.valeurs().tauxParDefaut(i.statutBourse());
        }
        if (referenceDecision != null && referenceDecision.trim().length() > 60) {
            throw new IllegalArgumentException("Référence de la décision : 60 caractères au plus");
        }
        PriseEnCharge pec = prisesEnCharge.findByInscriptionId(inscriptionId)
                .orElseGet(() -> new PriseEnCharge(inscriptionId));
        pec.definir(organismeId, t, vide(referenceDecision), dateDecision,
                UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant());
        prisesEnCharge.save(pec);
        audit.enregistrer("PRISE_EN_CHARGE_DEFINIE", i.matricule(),
                Map.of("organisme", organisme.getNom(), "taux", t));
        return vue(pec);
    }

    @Transactional
    public void supprimerPriseEnCharge(UUID inscriptionId) {
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        prisesEnCharge.findByInscriptionId(inscriptionId).ifPresent(pec -> {
            prisesEnCharge.delete(pec);
            audit.enregistrer("PRISE_EN_CHARGE_SUPPRIMEE", i.matricule(), null);
        });
    }

    /** Accorde (ou modifie) une exonération sur un frais de l'année de l'inscription. */
    @Transactional
    public ExonerationVue accorderExoneration(UUID inscriptionId, UUID fraisId, Long montant, String motif) {
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        FraisScolarite f = frais.trouver(fraisId);
        if (!f.getAnneeId().equals(i.anneeId())) {
            throw new RegleMetierException("FRAIS_HORS_ANNEE", "Ce frais ne concerne pas l'année de l'inscription");
        }
        if (montant == null || montant <= 0 || montant > f.getMontant()) {
            throw new IllegalArgumentException("L'exonération est comprise entre 1 et " + Montants.fcfa(f.getMontant()));
        }
        if (motif == null || motif.isBlank() || motif.trim().length() > 200) {
            throw new IllegalArgumentException("Le motif de l'exonération est obligatoire (200 caractères au plus)");
        }
        Exoneration e = exonerations.findByInscriptionIdAndFraisId(inscriptionId, fraisId)
                .orElseGet(() -> new Exoneration(inscriptionId, fraisId));
        e.definir(montant, motif.trim(), UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant());
        exonerations.save(e);
        audit.enregistrer("EXONERATION_ACCORDEE", i.matricule() + " / " + f.getLibelle(),
                Map.of("montant", montant, "motif", motif.trim()));
        return new ExonerationVue(fraisId, f.getLibelle(), montant, motif.trim());
    }

    @Transactional
    public void retirerExoneration(UUID inscriptionId, UUID fraisId) {
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        exonerations.findByInscriptionIdAndFraisId(inscriptionId, fraisId).ifPresent(e -> {
            exonerations.delete(e);
            audit.enregistrer("EXONERATION_RETIREE", i.matricule() + " / " + fraisId, null);
        });
    }

    private PriseEnChargeVue vue(PriseEnCharge p) {
        return new PriseEnChargeVue(p.getInscriptionId(), p.getOrganismeId(),
                organismes.trouver(p.getOrganismeId()).getNom(), p.getTaux(), p.getReferenceDecision(),
                p.getDateDecision());
    }

    private static String vide(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
