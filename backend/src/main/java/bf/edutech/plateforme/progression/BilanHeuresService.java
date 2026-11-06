package bf.edutech.plateforme.progression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.progression.Vues.Domaine;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;

/**
 * Bilan des heures (v0.50) : pour chaque matière de chaque classe, le volume annuel dû (fixé par
 * le référentiel), le dû à ce jour, le réalisé tiré du cahier de textes, le reste à faire et le
 * taux de réalisation ; puis les cumuls par enseignant, par matière (discipline) et par classe.
 *
 * <p>Le dû à ce jour est le volume annuel au prorata des jours de cours écoulés, comptés sur les
 * périodes (trimestres ou semestres) du profil pédagogique de la classe, ou sur l'année scolaire
 * si les périodes ne sont pas encore définies.
 *
 * <p>Quand le volume annuel n'est pas saisi mais que le volume hebdomadaire l'est, le volume
 * annuel est <em>estimé</em> (heures par semaine × semaines de cours) et signalé comme tel.
 */
@Service
public class BilanHeuresService {

    static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final BigDecimal CENT = BigDecimal.valueOf(100);
    /** En deçà de 90 % du dû à ce jour : en retard ; au-delà de 110 % : en avance. */
    private static final BigDecimal SEUIL_RETARD = new BigDecimal("0.90");
    private static final BigDecimal SEUIL_AVANCE = new BigDecimal("1.10");

    public enum SourceVolume {
        REFERENTIEL,
        ESTIME,
        AUCUN
    }

    public enum EtatHeures {
        SANS_VOLUME,
        PAS_COMMENCE,
        EN_RETARD,
        A_JOUR,
        EN_AVANCE,
        TERMINE
    }

    public record Cumul(BigDecimal volumeAnnuel, BigDecimal duADate, BigDecimal realise, BigDecimal reste,
            BigDecimal tauxRealisation, BigDecimal tauxADate, BigDecimal ecart) {
    }

    public record Intervention(UUID engagementId, String enseignant, BigDecimal heures, int seances) {
    }

    public record LigneBilan(UUID classeId, String classeCode, String niveau, UUID filiereId, String filiereCode,
            UUID matiereId, String matiereCode, String matiereLibelle, TypeMatiere type, Domaine domaine,
            UUID engagementId, String enseignant, String statutEnseignant, BigDecimal volumeHebdo,
            SourceVolume sourceVolume, Cumul cumul, int seances, LocalDate derniereSeance, EtatHeures etat,
            List<Intervention> intervenants) {
    }

    public record TotalEnseignant(UUID engagementId, String enseignant, String statutEnseignant, int classes,
            int matieres, int sansVolume, int enRetard, Cumul cumul) {
    }

    public record TotalMatiere(UUID matiereId, String matiereCode, String matiereLibelle, TypeMatiere type,
            int classes, int sansVolume, int enRetard, Cumul cumul) {
    }

    public record TotalClasse(UUID classeId, String classeCode, String niveau, String filiereCode, int matieres,
            int sansVolume, int enRetard, Cumul cumul) {
    }

    public record BilanHeuresVue(UUID anneeId, String annee, LocalDate jour, BigDecimal avancementAnnee,
            List<LigneBilan> lignes, List<TotalEnseignant> enseignants, List<TotalMatiere> matieres,
            List<TotalClasse> classes, Cumul total, int sansVolume, int sansEnseignant, int enRetard) {
    }

    private final JdbcTemplate jdbc;
    private final AnneesService annees;
    private final EnseignantsService enseignants;
    private final Supervision supervision;
    private final AccesMatiere acces;
    private final Clock horloge;

    BilanHeuresService(JdbcTemplate jdbc, AnneesService annees, EnseignantsService enseignants,
            Supervision supervision, AccesMatiere acces, Clock horloge) {
        this.jdbc = jdbc;
        this.annees = annees;
        this.enseignants = enseignants;
        this.supervision = supervision;
        this.acces = acces;
        this.horloge = horloge;
    }

    /** Direction : toutes les matières de son domaine (général pour le censeur, technique pour le chef des travaux). */
    @Transactional(readOnly = true)
    public BilanHeuresVue bilan(UUID anneeId) {
        Set<Domaine> domaines = supervision.domaines();
        if (domaines.isEmpty()) {
            throw new AccesRefuseException("Le bilan des heures est réservé à la direction");
        }
        return calculer(annees.trouver(anneeId), l -> domaines.contains(l.domaine()), null);
    }

    /** Enseignant : ses matières de l'année active, et celles où il a fait des heures avant un changement d'enseignant. */
    @Transactional(readOnly = true)
    public BilanHeuresVue monBilan() {
        UUID moi = acces.monEngagement()
                .orElseThrow(() -> new AccesRefuseException("Réservé aux enseignants en fonction"));
        return calculer(annees.active(), l -> moi.equals(l.engagementId())
                || l.intervenants().stream().anyMatch(i -> moi.equals(i.engagementId())), moi);
    }

    // ------------------------------------------------------------------ calcul

    private record Brute(UUID classeId, String classeCode, String niveau, UUID filiereId, String filiereCode,
            UUID profilId, UUID matiereId, String matiereCode, String matiereLibelle, TypeMatiere type,
            BigDecimal volumeHebdo, BigDecimal volumeTotal, UUID engagementId) {
    }

    private record Fait(UUID engagementId, int seances, BigDecimal heures, LocalDate derniere) {
    }

    /** Jours de cours de l'année et jours écoulés, pour un profil. */
    private record Calendrier(long jours, long ecoules) {

        BigDecimal fraction() {
            return jours == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(ecoules).divide(BigDecimal.valueOf(jours), 4, RoundingMode.HALF_UP);
        }

        BigDecimal semaines() {
            return BigDecimal.valueOf(jours).divide(BigDecimal.valueOf(7), 0, RoundingMode.HALF_UP);
        }
    }

    private BilanHeuresVue calculer(AnneeVue annee, Predicate<LigneBilan> retenue, UUID engagementSeul) {
        LocalDate jour = LocalDate.now(horloge.withZone(FUSEAU));
        List<Brute> brutes = jdbc.query("""
                select c.id, c.code, c.niveau, f.id, f.code, f.profil_id, m.id, m.code, m.libelle, m.type,
                       cm.volume_hebdo, cm.volume_total, cm.engagement_id
                from classe_matiere cm
                join classe c on c.id = cm.classe_id
                join filiere f on f.id = c.filiere_id
                join matiere m on m.id = cm.matiere_id
                where c.annee_id = ?
                order by c.code, m.libelle""",
                (rs, i) -> new Brute(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, UUID.class),
                        rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                        TypeMatiere.valueOf(rs.getString(10)), rs.getBigDecimal(11), rs.getBigDecimal(12),
                        rs.getObject(13, UUID.class)),
                annee.id());

        Map<String, List<Fait>> faits = new HashMap<>();
        jdbc.query("""
                select s.classe_id, s.matiere_id, s.engagement_id, count(*),
                       coalesce(sum(round(extract(epoch from (s.heure_fin - s.heure_debut)) / 3600.0, 1)), 0),
                       max(s.date_seance)
                from seance_cahier s
                join classe c on c.id = s.classe_id
                where c.annee_id = ?
                group by s.classe_id, s.matiere_id, s.engagement_id""", rs -> {
            faits.computeIfAbsent(cle(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), k -> new ArrayList<>())
                    .add(new Fait(rs.getObject(3, UUID.class), rs.getInt(4), rs.getBigDecimal(5).setScale(1, RoundingMode.HALF_UP),
                            rs.getObject(6, LocalDate.class)));
        }, annee.id());

        Map<UUID, List<LocalDate[]>> periodes = new HashMap<>();
        jdbc.query("select profil_id, debut, fin from periode where annee_id = ?", rs -> {
            periodes.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                    .add(new LocalDate[] { rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class) });
        }, annee.id());
        Map<UUID, Calendrier> calendriers = new HashMap<>();

        List<UUID> engagements = new ArrayList<>();
        brutes.forEach(b -> {
            if (b.engagementId() != null) {
                engagements.add(b.engagementId());
            }
        });
        faits.values().forEach(l -> l.forEach(f -> engagements.add(f.engagementId())));
        List<UUID> distincts = engagements.stream().distinct().toList();
        Map<UUID, String> noms = enseignants.nomsParEngagement(distincts);
        Map<UUID, String> statuts = new HashMap<>();
        if (!distincts.isEmpty()) {
            jdbc.query("select id, type from engagement_enseignant where id = any(?)",
                    rs -> {
                        statuts.put(rs.getObject(1, UUID.class), rs.getString(2));
                    }, (Object) distincts.toArray(UUID[]::new));
        }

        BigDecimal avancementAnnee = calendrier(null, annee, periodes, jour).fraction().multiply(CENT)
                .setScale(0, RoundingMode.HALF_UP);
        List<LigneBilan> lignes = new ArrayList<>();
        for (Brute b : brutes) {
            Calendrier cal = calendriers.computeIfAbsent(b.profilId(), p -> calendrier(p, annee, periodes, jour));
            List<Fait> fl = faits.getOrDefault(cle(b.classeId(), b.matiereId()), List.of());
            SourceVolume source;
            BigDecimal volume;
            if (b.volumeTotal() != null) {
                source = SourceVolume.REFERENTIEL;
                volume = b.volumeTotal();
            } else if (b.volumeHebdo() != null && cal.jours() > 0) {
                source = SourceVolume.ESTIME;
                volume = b.volumeHebdo().multiply(cal.semaines()).setScale(1, RoundingMode.HALF_UP);
            } else {
                source = SourceVolume.AUCUN;
                volume = null;
            }
            BigDecimal du = volume == null ? null : volume.multiply(cal.fraction()).setScale(1, RoundingMode.HALF_UP);
            BigDecimal realise = fl.stream().map(Fait::heures).reduce(BigDecimal.ZERO, BigDecimal::add);
            int seances = fl.stream().mapToInt(Fait::seances).sum();
            LocalDate derniere = fl.stream().map(Fait::derniere).max(Comparator.naturalOrder()).orElse(null);
            List<Intervention> intervenants = fl.stream()
                    .sorted(Comparator.comparing(Fait::heures).reversed())
                    .map(f -> new Intervention(f.engagementId(), noms.get(f.engagementId()), f.heures(), f.seances()))
                    .toList();
            Cumul cumul = cumul(volume, du, realise);
            lignes.add(new LigneBilan(b.classeId(), b.classeCode(), b.niveau(), b.filiereId(), b.filiereCode(),
                    b.matiereId(), b.matiereCode(), b.matiereLibelle(), b.type(), Domaine.de(b.type()),
                    b.engagementId(), b.engagementId() == null ? null : noms.get(b.engagementId()),
                    b.engagementId() == null ? null : statuts.get(b.engagementId()), b.volumeHebdo(), source, cumul,
                    seances, derniere, etat(volume, du, realise, seances), intervenants));
        }
        List<LigneBilan> retenues = lignes.stream().filter(retenue).toList();
        return new BilanHeuresVue(annee.id(), annee.libelle(), jour, avancementAnnee, retenues,
                parEnseignant(retenues, noms, statuts, engagementSeul), parMatiere(retenues), parClasse(retenues),
                total(retenues), (int) retenues.stream().filter(l -> l.sourceVolume() == SourceVolume.AUCUN).count(),
                (int) retenues.stream().filter(l -> l.engagementId() == null).count(),
                (int) retenues.stream().filter(l -> l.etat() == EtatHeures.EN_RETARD).count());
    }

    private static Calendrier calendrier(UUID profilId, AnneeVue annee, Map<UUID, List<LocalDate[]>> periodes,
            LocalDate jour) {
        List<LocalDate[]> liste = profilId == null ? null : periodes.get(profilId);
        if (liste == null || liste.isEmpty()) {
            liste = List.<LocalDate[]>of(new LocalDate[] { annee.debut(), annee.fin() });
        }
        long jours = 0;
        long ecoules = 0;
        for (LocalDate[] p : liste) {
            jours += ChronoUnit.DAYS.between(p[0], p[1]) + 1;
            if (!jour.isBefore(p[0])) {
                LocalDate borne = jour.isAfter(p[1]) ? p[1] : jour;
                ecoules += ChronoUnit.DAYS.between(p[0], borne) + 1;
            }
        }
        return new Calendrier(jours, ecoules);
    }

    static EtatHeures etat(BigDecimal volume, BigDecimal du, BigDecimal realise, int seances) {
        if (volume == null) {
            return EtatHeures.SANS_VOLUME;
        }
        if (realise.compareTo(volume) >= 0) {
            return EtatHeures.TERMINE;
        }
        if (seances == 0) {
            return du.signum() > 0 ? EtatHeures.EN_RETARD : EtatHeures.PAS_COMMENCE;
        }
        if (du.signum() == 0) {
            return EtatHeures.EN_AVANCE;
        }
        if (realise.compareTo(du.multiply(SEUIL_RETARD)) < 0) {
            return EtatHeures.EN_RETARD;
        }
        if (realise.compareTo(du.multiply(SEUIL_AVANCE)) > 0) {
            return EtatHeures.EN_AVANCE;
        }
        return EtatHeures.A_JOUR;
    }

    static Cumul cumul(BigDecimal volume, BigDecimal du, BigDecimal realise) {
        BigDecimal reste = volume == null ? null : volume.subtract(realise).max(BigDecimal.ZERO);
        BigDecimal taux = volume == null || volume.signum() == 0 ? null
                : realise.multiply(CENT).divide(volume, 1, RoundingMode.HALF_UP);
        BigDecimal tauxADate = du == null || du.signum() == 0 ? null
                : realise.multiply(CENT).divide(du, 1, RoundingMode.HALF_UP);
        BigDecimal ecart = du == null ? null : realise.subtract(du);
        return new Cumul(volume, du, realise, reste, taux, tauxADate, ecart);
    }

    /** Cumul de lignes : dû et volume sur les lignes qui en ont un, réalisé sur toutes. */
    private static Cumul somme(List<LigneBilan> lignes, Function<LigneBilan, BigDecimal> realise) {
        BigDecimal volume = null;
        BigDecimal du = null;
        BigDecimal fait = BigDecimal.ZERO;
        for (LigneBilan l : lignes) {
            if (l.cumul().volumeAnnuel() != null) {
                volume = (volume == null ? BigDecimal.ZERO : volume).add(l.cumul().volumeAnnuel());
                du = (du == null ? BigDecimal.ZERO : du).add(l.cumul().duADate());
            }
            fait = fait.add(realise.apply(l));
        }
        return cumul(volume, du, fait);
    }

    private static Cumul total(List<LigneBilan> lignes) {
        return somme(lignes, l -> l.cumul().realise());
    }

    /**
     * Par enseignant : le dû porte sur les matières qui lui sont confiées aujourd'hui ; le réalisé
     * compte toutes les heures qu'il a faites dans l'année, y compris dans une classe reprise depuis
     * par un autre enseignant.
     */
    private static List<TotalEnseignant> parEnseignant(List<LigneBilan> lignes, Map<UUID, String> noms,
            Map<UUID, String> statuts, UUID engagementSeul) {
        Map<UUID, List<LigneBilan>> confiees = new LinkedHashMap<>();
        Map<UUID, BigDecimal> horsConfiees = new HashMap<>();
        for (LigneBilan l : lignes) {
            if (l.engagementId() != null) {
                confiees.computeIfAbsent(l.engagementId(), k -> new ArrayList<>()).add(l);
            }
            for (Intervention i : l.intervenants()) {
                if (!i.engagementId().equals(l.engagementId())) {
                    horsConfiees.merge(i.engagementId(), i.heures(), BigDecimal::add);
                    confiees.computeIfAbsent(i.engagementId(), k -> new ArrayList<>());
                }
            }
        }
        List<TotalEnseignant> totaux = new ArrayList<>();
        confiees.forEach((e, ls) -> {
            if (engagementSeul != null && !engagementSeul.equals(e)) {
                return;
            }
            Cumul c = somme(ls, l -> heuresDe(l, e));
            BigDecimal hors = horsConfiees.getOrDefault(e, BigDecimal.ZERO);
            if (hors.signum() > 0) {
                c = cumul(c.volumeAnnuel(), c.duADate(), c.realise().add(hors));
            }
            totaux.add(new TotalEnseignant(e, noms.get(e), statuts.get(e),
                    (int) ls.stream().map(LigneBilan::classeId).distinct().count(), ls.size(),
                    (int) ls.stream().filter(l -> l.sourceVolume() == SourceVolume.AUCUN).count(),
                    (int) ls.stream().filter(l -> l.etat() == EtatHeures.EN_RETARD).count(), c));
        });
        totaux.sort(Comparator.comparing(t -> t.enseignant() == null ? "" : t.enseignant(), String.CASE_INSENSITIVE_ORDER));
        return totaux;
    }

    private static BigDecimal heuresDe(LigneBilan l, UUID engagement) {
        return l.intervenants().stream().filter(i -> engagement.equals(i.engagementId())).map(Intervention::heures)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<TotalMatiere> parMatiere(List<LigneBilan> lignes) {
        Map<UUID, List<LigneBilan>> groupes = lignes.stream()
                .collect(Collectors.groupingBy(LigneBilan::matiereId, LinkedHashMap::new, Collectors.toList()));
        return groupes.values().stream().map(ls -> {
            LigneBilan p = ls.get(0);
            return new TotalMatiere(p.matiereId(), p.matiereCode(), p.matiereLibelle(), p.type(), ls.size(),
                    (int) ls.stream().filter(l -> l.sourceVolume() == SourceVolume.AUCUN).count(),
                    (int) ls.stream().filter(l -> l.etat() == EtatHeures.EN_RETARD).count(), total(ls));
        }).sorted(Comparator.comparing(TotalMatiere::matiereLibelle, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static List<TotalClasse> parClasse(List<LigneBilan> lignes) {
        Map<UUID, List<LigneBilan>> groupes = lignes.stream()
                .collect(Collectors.groupingBy(LigneBilan::classeId, LinkedHashMap::new, Collectors.toList()));
        return groupes.values().stream().map(ls -> {
            LigneBilan p = ls.get(0);
            return new TotalClasse(p.classeId(), p.classeCode(), p.niveau(), p.filiereCode(), ls.size(),
                    (int) ls.stream().filter(l -> l.sourceVolume() == SourceVolume.AUCUN).count(),
                    (int) ls.stream().filter(l -> l.etat() == EtatHeures.EN_RETARD).count(), total(ls));
        }).toList();
    }

    private static String cle(UUID classe, UUID matiere) {
        return classe + "/" + matiere;
    }
}
