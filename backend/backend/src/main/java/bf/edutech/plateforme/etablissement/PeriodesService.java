package bf.edutech.plateforme.etablissement;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.pedagogie.Decoupage;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;

/**
 * Périodes d'évaluation d'une année, par profil pédagogique : génération
 * automatique (trimestres, semestres), saisie manuelle (modules), verrouillage.
 */
@Service
public class PeriodesService {

    /** Intervalle de dates inclusif. */
    record Intervalle(LocalDate debut, LocalDate fin) {
    }

    private final PeriodeRepository periodes;
    private final AccesAnnees annees;
    private final ProfilsService profils;
    private final AuditService audit;

    PeriodesService(PeriodeRepository periodes, AccesAnnees annees, ProfilsService profils, AuditService audit) {
        this.periodes = periodes;
        this.annees = annees;
        this.profils = profils;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PeriodeVue> lister(UUID anneeId) {
        annees.charger(anneeId);
        return periodes.findByAnneeIdOrderByProfilIdAscOrdreAsc(anneeId).stream().map(PeriodeVue::depuis).toList();
    }

    /** Découpe l'année en trimestres ou semestres de durées proches, selon le profil. */
    @Transactional
    public List<PeriodeVue> generer(UUID anneeId, UUID profilId) {
        AnneeScolaire annee = annees.chargerModifiable(anneeId);
        ProfilVue profil = profils.trouver(profilId);
        Decoupage decoupage = profil.decoupage();
        if (decoupage.nombrePeriodes() == 0) {
            throw new RegleMetierException("DECOUPAGE_MANUEL",
                    "Le profil « " + profil.libelle() + " » est organisé en modules : saisissez les périodes une à une");
        }
        if (periodes.existsByAnneeIdAndProfilId(anneeId, profilId)) {
            throw new RegleMetierException("PERIODES_EXISTANTES",
                    "Des périodes existent déjà pour ce profil : modifiez-les ou supprimez-les d'abord");
        }
        List<Intervalle> intervalles = decouper(annee.getDebut(), annee.getFin(), decoupage.nombrePeriodes());
        List<PeriodeVue> resultat = new ArrayList<>();
        for (int i = 0; i < intervalles.size(); i++) {
            Intervalle intervalle = intervalles.get(i);
            Periode p = periodes.save(new Periode(anneeId, profilId, libelle(decoupage, i + 1), (short) (i + 1),
                    intervalle.debut(), intervalle.fin()));
            resultat.add(PeriodeVue.depuis(p));
        }
        audit.enregistrer("PERIODES_GENEREES", annee.getLibelle(), Map.of("profil", profil.code(),
                "nombre", intervalles.size()));
        return resultat;
    }

    /** Ajoute une période (module ou session) à la suite des périodes existantes du profil. */
    @Transactional
    public PeriodeVue creer(UUID anneeId, UUID profilId, String libelle, LocalDate debut, LocalDate fin) {
        AnneeScolaire annee = annees.chargerModifiable(anneeId);
        profils.trouver(profilId);
        List<Periode> existantes = periodes.findByAnneeIdAndProfilIdOrderByOrdreAsc(anneeId, profilId);
        verifierDates(annee, debut, fin, existantes, null);
        short ordre = (short) (existantes.isEmpty() ? 1 : existantes.get(existantes.size() - 1).getOrdre() + 1);
        return PeriodeVue.depuis(periodes.save(new Periode(anneeId, profilId, libelle.trim(), ordre, debut, fin)));
    }

    @Transactional
    public PeriodeVue modifier(UUID periodeId, String libelle, LocalDate debut, LocalDate fin) {
        Periode periode = charger(periodeId);
        AnneeScolaire annee = annees.chargerModifiable(periode.getAnneeId());
        exigerNonVerrouillee(periode);
        verifierDates(annee, debut, fin,
                periodes.findByAnneeIdAndProfilIdOrderByOrdreAsc(periode.getAnneeId(), periode.getProfilId()),
                periode.getId());
        periode.modifier(libelle.trim(), debut, fin);
        return PeriodeVue.depuis(periode);
    }

    @Transactional
    public void supprimer(UUID periodeId) {
        Periode periode = charger(periodeId);
        annees.chargerModifiable(periode.getAnneeId());
        exigerNonVerrouillee(periode);
        periodes.delete(periode);
    }

    /** Verrouille la période : plus aucune note ne pourra y être saisie ou modifiée. */
    @Transactional
    public PeriodeVue verrouiller(UUID periodeId) {
        Periode periode = charger(periodeId);
        AnneeScolaire annee = annees.chargerModifiable(periode.getAnneeId());
        periode.verrouiller();
        audit.enregistrer("PERIODE_VERROUILLEE", annee.getLibelle() + " / " + periode.getLibelle(), null);
        return PeriodeVue.depuis(periode);
    }

    /** Déverrouillage exceptionnel (journalisé), impossible une fois l'année clôturée. */
    @Transactional
    public PeriodeVue deverrouiller(UUID periodeId) {
        Periode periode = charger(periodeId);
        AnneeScolaire annee = annees.chargerModifiable(periode.getAnneeId());
        periode.deverrouiller();
        audit.enregistrer("PERIODE_DEVERROUILLEE", annee.getLibelle() + " / " + periode.getLibelle(), null);
        return PeriodeVue.depuis(periode);
    }

    /** Découpe [debut, fin] en {@code n} intervalles contigus de durées aussi proches que possible. */
    static List<Intervalle> decouper(LocalDate debut, LocalDate fin, int n) {
        long jours = ChronoUnit.DAYS.between(debut, fin) + 1;
        List<Intervalle> resultat = new ArrayList<>();
        LocalDate courant = debut;
        for (int i = 1; i <= n; i++) {
            LocalDate finIntervalle = (i == n) ? fin : debut.plusDays(jours * i / n - 1);
            resultat.add(new Intervalle(courant, finIntervalle));
            courant = finIntervalle.plusDays(1);
        }
        return resultat;
    }

    private static String libelle(Decoupage decoupage, int rang) {
        String unite = decoupage == Decoupage.SEMESTRE ? "semestre" : "trimestre";
        return (rang == 1 ? "1er " : rang + "e ") + unite;
    }

    private Periode charger(UUID periodeId) {
        return periodes.findById(periodeId).orElseThrow(() -> new RessourceIntrouvableException("Période introuvable"));
    }

    private static void exigerNonVerrouillee(Periode periode) {
        if (periode.isVerrouillee()) {
            throw new RegleMetierException("PERIODE_VERROUILLEE",
                    "La période « " + periode.getLibelle() + " » est verrouillée");
        }
    }

    private static void verifierDates(AnneeScolaire annee, LocalDate debut, LocalDate fin, List<Periode> existantes,
            UUID ignoree) {
        if (fin.isBefore(debut)) {
            throw new IllegalArgumentException("La fin de la période doit suivre son début");
        }
        if (!annee.contient(debut) || !annee.contient(fin)) {
            throw new RegleMetierException("HORS_ANNEE", "La période doit être comprise dans l'année "
                    + annee.getLibelle() + " (" + annee.getDebut() + " – " + annee.getFin() + ")");
        }
        for (Periode p : existantes) {
            if (!p.getId().equals(ignoree) && p.chevauche(debut, fin)) {
                throw new RegleMetierException("CHEVAUCHEMENT",
                        "La période chevauche « " + p.getLibelle() + " » (" + p.getDebut() + " – " + p.getFin() + ")");
            }
        }
    }
}
