package bf.edutech.plateforme.scolarite;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.eleves.StatutBourse;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.scolarite.ParametresScolariteService.ParametresScolarite;
import bf.edutech.plateforme.scolarite.Vues.EcheanceVue;
import bf.edutech.plateforme.scolarite.Vues.ExonerationVue;
import bf.edutech.plateforme.scolarite.Vues.PaiementVue;
import bf.edutech.plateforme.scolarite.Vues.SituationResumeVue;
import bf.edutech.plateforme.scolarite.Vues.SituationVue;

/**
 * Charge en quelques requêtes tout ce qu'il faut pour calculer l'échéancier
 * d'un ensemble d'inscriptions (un élève, une classe, toute l'école), puis
 * applique {@link CalculEcheancier}. S'exécute dans la transaction de l'appelant.
 */
@Component
class Situations {

    record Situation(InscriptionVue inscription, ClasseVue classe, BigDecimal taux, PriseEnCharge priseEnCharge,
            String organisme, CalculEcheancier.Resultat resultat, List<Paiement> paiements,
            List<Exoneration> exonerations, Map<UUID, String> libellesFrais) {
    }

    private final FraisRepository frais;
    private final FraisCibleRepository cibles;
    private final TrancheRepository tranches;
    private final SouscriptionRepository souscriptions;
    private final PriseEnChargeRepository prisesEnCharge;
    private final ExonerationRepository exonerations;
    private final PaiementRepository paiements;
    private final OrganismeRepository organismes;
    private final RecuRepository recus;
    private final ParametresScolariteService parametres;
    private final ClassesService classes;
    private final Clock horloge;

    Situations(FraisRepository frais, FraisCibleRepository cibles, TrancheRepository tranches,
            SouscriptionRepository souscriptions, PriseEnChargeRepository prisesEnCharge,
            ExonerationRepository exonerations, PaiementRepository paiements, OrganismeRepository organismes,
            RecuRepository recus, ParametresScolariteService parametres, ClassesService classes, Clock horloge) {
        this.frais = frais;
        this.cibles = cibles;
        this.tranches = tranches;
        this.souscriptions = souscriptions;
        this.prisesEnCharge = prisesEnCharge;
        this.exonerations = exonerations;
        this.paiements = paiements;
        this.organismes = organismes;
        this.recus = recus;
        this.parametres = parametres;
        this.classes = classes;
        this.horloge = horloge;
    }

    LocalDate aujourdhui() {
        return LocalDate.now(horloge);
    }

    Situation calculer(InscriptionVue inscription) {
        return calculer(List.of(inscription)).get(inscription.id());
    }

    /** Situation de chaque inscription, dans l'ordre donné. */
    Map<UUID, Situation> calculer(Collection<InscriptionVue> inscriptions) {
        Map<UUID, Situation> resultat = new LinkedHashMap<>();
        if (inscriptions.isEmpty()) {
            return resultat;
        }
        List<UUID> ids = inscriptions.stream().map(InscriptionVue::id).toList();
        Set<UUID> annees = inscriptions.stream().map(InscriptionVue::anneeId).collect(Collectors.toSet());
        List<FraisScolarite> tousFrais = frais.findByAnneeIdIn(annees);
        List<UUID> fraisIds = tousFrais.stream().map(FraisScolarite::getId).toList();
        Map<UUID, List<FraisCible>> ciblesParFrais = fraisIds.isEmpty() ? Map.of()
                : cibles.findByFraisIdIn(fraisIds).stream().collect(Collectors.groupingBy(FraisCible::getFraisId));
        Map<UUID, List<TrancheFrais>> tranchesParFrais = fraisIds.isEmpty() ? Map.of()
                : tranches.findByFraisIdInOrderByNumeroAsc(fraisIds).stream()
                        .collect(Collectors.groupingBy(TrancheFrais::getFraisId));
        Map<UUID, String> libelles = tousFrais.stream()
                .collect(Collectors.toMap(FraisScolarite::getId, FraisScolarite::getLibelle));
        Set<String> souscrits = new HashSet<>();
        souscriptions.findByInscriptionIdIn(ids).forEach(s -> souscrits.add(s.getInscriptionId() + ":" + s.getFraisId()));
        Map<UUID, PriseEnCharge> pecs = prisesEnCharge.findByInscriptionIdIn(ids).stream()
                .collect(Collectors.toMap(PriseEnCharge::getInscriptionId, Function.identity()));
        Map<UUID, String> nomsOrganismes = new HashMap<>();
        organismes.findAllById(pecs.values().stream().map(PriseEnCharge::getOrganismeId).distinct().toList())
                .forEach(o -> nomsOrganismes.put(o.getId(), o.getNom()));
        Map<UUID, List<Exoneration>> exosParInscription = exonerations.findByInscriptionIdIn(ids).stream()
                .collect(Collectors.groupingBy(Exoneration::getInscriptionId));
        Map<UUID, List<Paiement>> paiementsParInscription = paiements.findByInscriptionIdIn(ids).stream()
                .sorted(Comparator.comparing(Paiement::getEnregistreLe))
                .collect(Collectors.groupingBy(Paiement::getInscriptionId));
        Map<UUID, ClasseVue> classesParId = new HashMap<>();
        ParametresScolarite param = parametres.valeurs();
        LocalDate jour = aujourdhui();

        for (InscriptionVue i : inscriptions) {
            ClasseVue classe = classesParId.computeIfAbsent(i.classeId(), classes::trouver);
            List<CalculEcheancier.FraisApplicable> applicables = new ArrayList<>();
            for (FraisScolarite f : tousFrais) {
                if (!f.getAnneeId().equals(i.anneeId())
                        || !vise(f, ciblesParFrais.getOrDefault(f.getId(), List.of()), classe)
                        || (!f.isObligatoire() && !souscrits.contains(i.id() + ":" + f.getId()))) {
                    continue;
                }
                applicables.add(new CalculEcheancier.FraisApplicable(f.getId(), f.getLibelle(),
                        f.isCouvertParBourse(), tranchesParFrais.getOrDefault(f.getId(), List.of()).stream()
                                .map(t -> new CalculEcheancier.Tranche(t.getNumero(), t.getDateLimite(),
                                        t.getMontant()))
                                .toList()));
            }
            PriseEnCharge pec = pecs.get(i.id());
            BigDecimal taux = i.statutBourse() == StatutBourse.NON_BOURSIER ? BigDecimal.ZERO
                    : pec != null ? pec.getTaux() : param.tauxParDefaut(i.statutBourse());
            List<Exoneration> exos = exosParInscription.getOrDefault(i.id(), List.of());
            Map<UUID, Long> montantsExoneres = exos.stream()
                    .collect(Collectors.toMap(Exoneration::getFraisId, Exoneration::getMontant, Long::sum));
            List<Paiement> ps = paiementsParInscription.getOrDefault(i.id(), List.of());
            long famille = ps.stream().filter(p -> !p.estAnnule() && p.getPayeur() == Payeur.FAMILLE)
                    .mapToLong(Paiement::getMontant).sum();
            // Organisme : seuls comptent les versements de l'organisme qui prend l'élève en charge
            long organisme = ps.stream().filter(p -> !p.estAnnule() && p.getPayeur() == Payeur.ORGANISME
                    && (pec == null || pec.getOrganismeId().equals(p.getOrganismeId())))
                    .mapToLong(Paiement::getMontant).sum();
            resultat.put(i.id(), new Situation(i, classe, taux, pec,
                    pec != null ? nomsOrganismes.get(pec.getOrganismeId()) : null,
                    CalculEcheancier.calculer(applicables, montantsExoneres, taux, famille, organisme, jour), ps, exos,
                    libelles));
        }
        return resultat;
    }

    /** Le frais s'applique-t-il à la classe ? */
    static boolean vise(FraisScolarite f, List<FraisCible> cibles, ClasseVue classe) {
        return switch (f.getPortee()) {
            case TOUTES -> true;
            case FILIERES -> cibles.stream().anyMatch(c -> classe.filiereId().equals(c.getFiliereId()));
            case NIVEAUX -> cibles.stream().anyMatch(c -> c.getNiveau() != null
                    && c.getNiveau().equalsIgnoreCase(classe.niveau()));
            case CLASSES -> cibles.stream().anyMatch(c -> classe.id().equals(c.getClasseId()));
        };
    }

    // ------------------------------------------------------------------ vues

    SituationVue vue(Situation s, String annee) {
        CalculEcheancier.Resultat r = s.resultat();
        InscriptionVue i = s.inscription();
        return new SituationVue(i.id(), i.eleveId(), i.matricule(), i.nom(), i.prenoms(), i.classeCode(), annee,
                i.statutBourse(), s.taux(), s.organisme(), r.total(), r.exonere(), r.totalFamille(),
                r.totalOrganisme(), r.payeFamille(), r.payeOrganisme(), r.resteFamille(), r.resteOrganisme(),
                r.retardFamille(), r.retardOrganisme(), r.avanceFamille(), r.prochaineEcheance(),
                r.lignes().stream().map(Situations::echeance).toList(),
                s.exonerations().stream().map(e -> new ExonerationVue(e.getFraisId(),
                        s.libellesFrais().get(e.getFraisId()), e.getMontant(), e.getMotif())).toList(),
                paiements(s.paiements()));
    }

    static SituationResumeVue resume(Situation s, java.time.Instant derniereRelance) {
        CalculEcheancier.Resultat r = s.resultat();
        InscriptionVue i = s.inscription();
        return new SituationResumeVue(i.id(), i.matricule(), i.nom(), i.prenoms(), i.statutBourse(),
                r.totalFamille(), r.payeFamille(), r.resteFamille(), r.retardFamille(), r.totalOrganisme(),
                r.payeOrganisme(), r.resteOrganisme(), derniereRelance);
    }

    List<PaiementVue> paiements(List<Paiement> liste) {
        if (liste.isEmpty()) {
            return List.of();
        }
        Map<UUID, Recu> parPaiement = recus.findByPaiementIdIn(liste.stream().map(Paiement::getId).toList())
                .stream().collect(Collectors.toMap(Recu::getPaiementId, Function.identity()));
        return liste.stream().map(p -> vue(p, parPaiement.get(p.getId()))).toList();
    }

    static PaiementVue vue(Paiement p, Recu r) {
        return new PaiementVue(p.getId(), p.getInscriptionId(), p.getMontant(), p.getMoyen(), p.getPayeur(),
                p.getOrganismeId(), p.getReferenceExterne(), p.getDeposant(), p.getDatePaiement(),
                p.getEnregistreLe(), r != null ? r.getNumero() : null, r != null ? r.getCodeVerification() : null,
                p.estAnnule(), p.getMotifAnnulation());
    }

    private static EcheanceVue echeance(CalculEcheancier.Ligne l) {
        return new EcheanceVue(l.fraisId(), l.libelle(), l.numero(), l.nombreTranches(), l.dateLimite(), l.montant(),
                l.exoneration(), l.partOrganisme(), l.partFamille(), l.payeFamille(), l.payeOrganisme(),
                l.resteFamille(), l.resteOrganisme(), l.enRetard());
    }
}
