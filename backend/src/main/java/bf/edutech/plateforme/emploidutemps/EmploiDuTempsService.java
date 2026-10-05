package bf.edutech.plateforme.emploidutemps;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.AtelierCourtVue;
import bf.edutech.plateforme.emploidutemps.Generateur.Besoin;
import bf.edutech.plateforme.emploidutemps.Generateur.Case;
import bf.edutech.plateforme.emploidutemps.Generateur.Placement;
import bf.edutech.plateforme.emploidutemps.Vues.AtelierEmploiVue;
import bf.edutech.plateforme.emploidutemps.Vues.ClasseEmploiVue;
import bf.edutech.plateforme.emploidutemps.Vues.CreneauVue;
import bf.edutech.plateforme.emploidutemps.Vues.DemandeGeneration;
import bf.edutech.plateforme.emploidutemps.Vues.Domaine;
import bf.edutech.plateforme.emploidutemps.Vues.DonneesGrille;
import bf.edutech.plateforme.emploidutemps.Vues.DonneesSeance;
import bf.edutech.plateforme.emploidutemps.Vues.DroitsEmploi;
import bf.edutech.plateforme.emploidutemps.Vues.EmploiDuTempsVue;
import bf.edutech.plateforme.emploidutemps.Vues.EnseignantCourtVue;
import bf.edutech.plateforme.emploidutemps.Vues.MaSeanceVue;
import bf.edutech.plateforme.emploidutemps.Vues.ManqueVue;
import bf.edutech.plateforme.emploidutemps.Vues.MatiereClasseVue;
import bf.edutech.plateforme.emploidutemps.Vues.MonEmploiVue;
import bf.edutech.plateforme.emploidutemps.Vues.OccupationVue;
import bf.edutech.plateforme.emploidutemps.Vues.ResultatGeneration;
import bf.edutech.plateforme.emploidutemps.Vues.SaisieCreneau;
import bf.edutech.plateforme.emploidutemps.Vues.SeanceVue;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Emplois du temps : grille horaire, placement des séances (avec contrôle des conflits),
 * génération automatique, publication et emploi du temps de l'enseignant.
 */
@Service
public class EmploiDuTempsService {

    static final int CRENEAUX_MAX = 16;
    static final LocalTime PREMIERE_HEURE = LocalTime.of(6, 0);
    static final LocalTime DERNIERE_HEURE = LocalTime.of(20, 0);

    private final CreneauRepository creneaux;
    private final SeanceEmploiRepository seances;
    private final PublicationRepository publications;
    private final Chargement chargement;
    private final Responsabilites responsabilites;
    private final AnneesService annees;
    private final EnseignantsService enseignants;
    private final AuditService audit;
    private final Clock horloge;

    EmploiDuTempsService(CreneauRepository creneaux, SeanceEmploiRepository seances, PublicationRepository publications,
            Chargement chargement, Responsabilites responsabilites, AnneesService annees,
            EnseignantsService enseignants, AuditService audit, Clock horloge) {
        this.creneaux = creneaux;
        this.seances = seances;
        this.publications = publications;
        this.chargement = chargement;
        this.responsabilites = responsabilites;
        this.annees = annees;
        this.enseignants = enseignants;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ grille horaire

    @Transactional(readOnly = true)
    public List<CreneauVue> creneaux(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        annees.trouver(anneeId);
        return creneaux.findByAnneeIdOrderByHeureDebutAsc(anneeId).stream().map(EmploiDuTempsService::vue).toList();
    }

    /**
     * Remplace la grille de l'année : les créneaux conservés gardent leurs séances (même si
     * leurs heures changent) ; un créneau, ou un jour d'un créneau, ne peut disparaître que vide.
     */
    @Transactional
    public List<CreneauVue> definirGrille(UUID anneeId, DonneesGrille d) {
        UtilisateurConnecte.etablissementActif();
        if (!responsabilites.grille()) {
            throw new AccesRefuseException("La grille horaire est fixée par le censeur");
        }
        exigerModifiable(annees.trouver(anneeId));
        List<SaisieCreneau> saisies = d == null || d.creneaux() == null ? List.of() : d.creneaux();
        valider(saisies);
        Map<UUID, Creneau> existants = creneaux.findByAnneeIdOrderByHeureDebutAsc(anneeId).stream()
                .collect(Collectors.toMap(Creneau::getId, c -> c));
        Set<UUID> gardes = new HashSet<>();
        for (SaisieCreneau s : saisies) {
            if (s.id() != null && existants.containsKey(s.id())) {
                Creneau c = existants.get(s.id());
                for (int jour : c.jours()) {
                    if (!s.jours().contains(jour) && seances.existsByCreneauIdAndJour(c.getId(), (short) jour)) {
                        throw new RegleMetierException("CRENEAU_UTILISE", "Des séances sont placées le "
                                + Instantane.JOURS[jour] + " à " + Instantane.heure(c.getHeureDebut())
                                + " : déplacez-les avant de retirer ce jour");
                    }
                }
                gardes.add(c.getId());
            }
        }
        for (Creneau c : existants.values()) {
            if (!gardes.contains(c.getId())) {
                if (seances.existsByCreneauId(c.getId())) {
                    throw new RegleMetierException("CRENEAU_UTILISE", "Des séances sont placées sur le créneau de "
                            + Instantane.heure(c.getHeureDebut()) + " : déplacez-les avant de le supprimer");
                }
                creneaux.delete(c);
            }
        }
        creneaux.flush();
        // Les heures changent : on écarte d'abord les créneaux gardés pour respecter l'unicité de l'heure de début
        decaler(gardes.stream().map(existants::get).toList());
        List<Creneau> aEnregistrer = new ArrayList<>();
        for (SaisieCreneau s : saisies) {
            Creneau c = s.id() != null && existants.containsKey(s.id()) ? existants.get(s.id()) : new Creneau(anneeId);
            c.definir(s.heureDebut(), s.heureFin(), new TreeSet<>(s.jours()));
            aEnregistrer.add(c);
        }
        creneaux.saveAllAndFlush(aEnregistrer);
        audit.enregistrer("GRILLE_HORAIRE_MODIFIEE", annees.trouver(anneeId).libelle(),
                Map.of("creneaux", aEnregistrer.size()));
        return creneaux(anneeId);
    }

    /** Écarte temporairement les créneaux modifiés de l'heure qu'ils vont prendre (contrainte d'unicité). */
    private void decaler(List<Creneau> gardes) {
        if (gardes.isEmpty()) {
            return;
        }
        int i = 0;
        for (Creneau c : gardes) {
            LocalTime t = LocalTime.of(0, i++);
            c.definir(t, t.plusSeconds(30), c.jours());
        }
        creneaux.saveAllAndFlush(gardes);
    }

    private static void valider(List<SaisieCreneau> saisies) {
        if (saisies.size() > CRENEAUX_MAX) {
            throw new IllegalArgumentException(CRENEAUX_MAX + " créneaux au plus");
        }
        List<SaisieCreneau> tries = new ArrayList<>(saisies);
        for (SaisieCreneau s : tries) {
            if (s.heureDebut() == null || s.heureFin() == null) {
                throw new IllegalArgumentException("Indiquez l'heure de début et l'heure de fin de chaque créneau");
            }
            if (!s.heureFin().isAfter(s.heureDebut())) {
                throw new IllegalArgumentException("Créneau de " + Instantane.heure(s.heureDebut())
                        + " : l'heure de fin doit suivre l'heure de début");
            }
            if (s.heureDebut().isBefore(PREMIERE_HEURE) || s.heureFin().isAfter(DERNIERE_HEURE)) {
                throw new IllegalArgumentException("Les cours ont lieu entre 06h00 et 20h00");
            }
            if (java.time.Duration.between(s.heureDebut(), s.heureFin()).toMinutes() > 240) {
                throw new IllegalArgumentException("Un créneau dure 4 heures au plus : découpez-le");
            }
            if (s.jours() == null || s.jours().isEmpty() || s.jours().stream().anyMatch(j -> j == null || j < 1 || j > 7)) {
                throw new IllegalArgumentException("Créneau de " + Instantane.heure(s.heureDebut())
                        + " : choisissez au moins un jour");
            }
        }
        tries.sort(Comparator.comparing(SaisieCreneau::heureDebut));
        for (int i = 1; i < tries.size(); i++) {
            if (tries.get(i).heureDebut().isBefore(tries.get(i - 1).heureFin())) {
                throw new IllegalArgumentException("Les créneaux de " + Instantane.heure(tries.get(i - 1).heureDebut())
                        + " et de " + Instantane.heure(tries.get(i).heureDebut()) + " se chevauchent");
            }
        }
    }

    // ------------------------------------------------------------------ consultation

    @Transactional(readOnly = true)
    public EmploiDuTempsVue emploi(UUID anneeId) {
        return vue(chargement.charger(anneeId));
    }

    @Transactional(readOnly = true)
    Instant publieLe(UUID anneeId) {
        return publications.findByAnneeId(anneeId).map(PublicationEmploi::getPublieLe).orElse(null);
    }

    @Transactional(readOnly = true)
    Instantane instantane(UUID anneeId) {
        return chargement.charger(anneeId);
    }

    // ------------------------------------------------------------------ placement à la main

    /**
     * Crée ou déplace une séance (identifiant choisi par l'application : un renvoi ne crée pas de
     * doublon). Refusée si la classe, l'enseignant (ici ou dans un autre établissement) ou
     * l'atelier est déjà pris.
     */
    @Transactional
    public EmploiDuTempsVue enregistrer(UUID id, DonneesSeance d) {
        if (d == null || d.anneeId() == null || d.classeId() == null || d.matiereId() == null || d.jour() == null
                || d.creneauId() == null) {
            throw new IllegalArgumentException("Indiquez la classe, la matière, le jour et l'heure");
        }
        if (d.jour() < 1 || d.jour() > 7) {
            throw new IllegalArgumentException("Jour invalide (1 = lundi … 7 = dimanche)");
        }
        Instantane etat = chargement.charger(d.anneeId());
        exigerModifiable(etat.annee);
        Set<Domaine> domaines = responsabilites.domaines();
        SeanceEmploi seance = seances.findById(id).orElse(null);
        if (seance != null) {
            if (!seance.getAnneeId().equals(d.anneeId())) {
                throw new IllegalArgumentException("Cette séance appartient à une autre année");
            }
            exigerDomaine(domaines, etat.domaine(seance));
        }
        if (!etat.classeParId.containsKey(d.classeId())) {
            throw new RessourceIntrouvableException("Classe introuvable pour cette année");
        }
        MatiereDeClasseVue matiere = etat.matiere(d.classeId(), d.matiereId());
        if (matiere == null) {
            throw new RessourceIntrouvableException("Matière absente du programme de " + etat.classe(d.classeId()));
        }
        exigerDomaine(domaines, Domaine.de(matiere.type()));
        Creneau creneau = etat.creneauParId.get(d.creneauId());
        if (creneau == null) {
            throw new RessourceIntrouvableException("Créneau introuvable dans la grille de l'année");
        }
        if (!creneau.existeLe(d.jour())) {
            throw new IllegalArgumentException("Pas de cours le " + Instantane.JOURS[d.jour()] + " à "
                    + Instantane.heure(creneau.getHeureDebut()));
        }
        String groupe = texte(d.groupe(), 20, "Groupe");
        String salle = texte(d.salle(), 40, "Salle");
        if (d.atelierId() != null && !etat.atelierParId.containsKey(d.atelierId())) {
            throw new RessourceIntrouvableException("Atelier introuvable ou fermé");
        }
        for (SeanceEmploi autre : etat.seances) {
            if (autre.getId().equals(id) || autre.getJour() != d.jour() || !autre.getCreneauId().equals(d.creneauId())) {
                continue;
            }
            String conflit = etat.conflit(autre, d.classeId(), d.matiereId(), groupe, d.atelierId());
            if (conflit != null) {
                throw new RegleMetierException("CONFLIT_EMPLOI_DU_TEMPS", conflit);
            }
        }
        String ailleurs = etat.ailleurs(matiere.engagementId(), d.jour(), creneau);
        if (ailleurs != null) {
            throw new RegleMetierException("CONFLIT_EMPLOI_DU_TEMPS", ailleurs);
        }
        if (seance == null) {
            seance = new SeanceEmploi(id, d.anneeId());
        }
        seance.placer(d.classeId(), d.matiereId(), d.jour(), d.creneauId(), groupe, d.atelierId(), salle,
                UtilisateurConnecte.id(), horloge.instant());
        seances.saveAndFlush(seance);
        return emploi(d.anneeId());
    }

    @Transactional
    public EmploiDuTempsVue supprimer(UUID id) {
        UtilisateurConnecte.etablissementActif();
        SeanceEmploi seance = seances.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Séance introuvable"));
        Instantane etat = chargement.charger(seance.getAnneeId());
        exigerModifiable(etat.annee);
        exigerDomaine(responsabilites.domaines(), etat.domaine(seance));
        seances.delete(seance);
        seances.flush();
        return emploi(seance.getAnneeId());
    }

    // ------------------------------------------------------------------ génération automatique

    @Transactional
    public ResultatGeneration generer(UUID anneeId, DemandeGeneration d) {
        Instantane etat = chargement.charger(anneeId);
        exigerModifiable(etat.annee);
        Set<Domaine> miens = responsabilites.domaines();
        if (miens.isEmpty()) {
            throw new AccesRefuseException("La génération est réservée au censeur et au chef des travaux");
        }
        Set<Domaine> domaines = d == null || d.domaines() == null || d.domaines().isEmpty() ? miens
                : EnumSet.copyOf(d.domaines());
        for (Domaine dom : domaines) {
            exigerDomaine(miens, dom);
        }
        if (etat.creneaux.isEmpty()) {
            throw new RegleMetierException("GRILLE_VIDE", "Définissez d'abord la grille horaire de l'année");
        }
        Set<UUID> classes = d == null || d.classes() == null || d.classes().isEmpty() ? etat.classeParId.keySet()
                : new HashSet<>(d.classes());
        for (UUID c : classes) {
            if (!etat.classeParId.containsKey(c)) {
                throw new RessourceIntrouvableException("Classe introuvable pour cette année");
            }
        }
        boolean remplacer = d != null && Boolean.TRUE.equals(d.remplacer());
        List<SeanceEmploi> retirees = new ArrayList<>();
        if (remplacer) {
            for (SeanceEmploi s : etat.seances) {
                if (classes.contains(s.getClasseId()) && domaines.contains(etat.domaine(s))) {
                    retirees.add(s);
                }
            }
            seances.deleteAll(retirees);
            seances.flush();
            etat = chargement.charger(anneeId);
        }
        Generateur g = new Generateur(
                etat.creneaux.stream().map(c -> new Case(c.getId(), c.getHeureDebut(), c.getHeureFin(), c.jours()))
                        .toList(),
                ateliersParFiliere(etat.ateliers));
        for (SeanceEmploi s : etat.seances) {
            g.dejaPlace(s.getClasseId(), s.getMatiereId(), etat.engagement(s), s.getAtelierId(), s.getJour(),
                    s.getCreneauId());
        }
        etat.ailleurs.forEach(o -> g.prisAilleurs(o.engagementId(), o.jour(), o.debut(), o.fin()));
        List<Besoin> besoins = new ArrayList<>();
        for (ClasseVue c : etat.classes) {
            if (!classes.contains(c.id())) {
                continue;
            }
            for (MatiereDeClasseVue m : etat.programme.getOrDefault(c.id(), List.of())) {
                if (!domaines.contains(Domaine.de(m.type()))) {
                    continue;
                }
                int reste = Instantane.minutesPrevues(m.volumeHebdo()) - etat.minutesPlacees(c.id(), m.matiereId());
                if (reste > 0) {
                    besoins.add(new Besoin(c.id(), c.code(), c.filiereId(), m.matiereId(), m.matiereLibelle(),
                            m.type(), m.engagementId(), reste));
                }
            }
        }
        Generateur.Resultat r = g.generer(besoins);
        UUID par = UtilisateurConnecte.id();
        Instant maintenant = horloge.instant();
        List<SeanceEmploi> nouvelles = new ArrayList<>();
        for (Placement p : r.placements()) {
            SeanceEmploi s = new SeanceEmploi(null, anneeId);
            s.placer(p.classeId(), p.matiereId(), p.jour(), p.creneauId(), null, p.atelierId(), null, par, maintenant);
            nouvelles.add(s);
        }
        seances.saveAllAndFlush(nouvelles);
        List<ManqueVue> manques = r.manques().stream()
                .map(m -> new ManqueVue(m.besoin().classeId(), m.besoin().classeCode(), m.besoin().matiereId(),
                        m.besoin().matiereLibelle(), m.minutes(), m.raison()))
                .sorted(Comparator.comparing(ManqueVue::classe).thenComparing(ManqueVue::matiere)).toList();
        audit.enregistrer("EMPLOI_DU_TEMPS_GENERE", etat.annee.libelle(), Map.of("domaines", domaines.toString(),
                "classes", classes.size(), "placees", nouvelles.size(), "retirees", retirees.size(),
                "manques", manques.size()));
        return new ResultatGeneration(nouvelles.size(), retirees.size(), manques, emploi(anneeId));
    }

    private static Map<UUID, List<UUID>> ateliersParFiliere(List<AtelierCourtVue> ateliers) {
        Map<UUID, List<UUID>> parFiliere = new HashMap<>();
        for (AtelierCourtVue a : ateliers) {
            for (UUID f : a.filieres()) {
                parFiliere.computeIfAbsent(f, k -> new ArrayList<>()).add(a.id());
            }
        }
        return parFiliere;
    }

    // ------------------------------------------------------------------ publication

    /** Communique l'emploi du temps aux enseignants (dans leur espace) ; republier met la date à jour. */
    @Transactional
    public EmploiDuTempsVue publier(UUID anneeId) {
        Instantane etat = chargement.charger(anneeId);
        exigerPublication(etat.annee);
        if (etat.seances.isEmpty()) {
            throw new RegleMetierException("EMPLOI_DU_TEMPS_VIDE", "Placez des séances avant de publier");
        }
        long conflits = etat.conflits().size();
        if (conflits > 0) {
            throw new RegleMetierException("CONFLITS_A_REGLER",
                    "Réglez d'abord les " + conflits + " conflit(s) signalé(s) dans l'emploi du temps");
        }
        PublicationEmploi p = publications.findByAnneeId(anneeId).orElseGet(() -> new PublicationEmploi(anneeId));
        p.publier(UtilisateurConnecte.id(), horloge.instant());
        publications.saveAndFlush(p);
        audit.enregistrer("EMPLOI_DU_TEMPS_PUBLIE", etat.annee.libelle(), Map.of("seances", etat.seances.size()));
        return emploi(anneeId);
    }

    @Transactional
    public EmploiDuTempsVue retirerPublication(UUID anneeId) {
        Instantane etat = chargement.charger(anneeId);
        exigerPublication(etat.annee);
        publications.findByAnneeId(anneeId).ifPresent(p -> {
            publications.delete(p);
            publications.flush();
        });
        audit.enregistrer("EMPLOI_DU_TEMPS_DEPUBLIE", etat.annee.libelle(), null);
        return emploi(anneeId);
    }

    private void exigerPublication(AnneeVue annee) {
        if (!responsabilites.grille()) {
            throw new AccesRefuseException("L'emploi du temps est publié par le censeur");
        }
        exigerModifiable(annee);
    }

    // ------------------------------------------------------------------ espace enseignant

    /** Emploi du temps publié de l'enseignant connecté pour l'année active de l'établissement. */
    @Transactional(readOnly = true)
    public MonEmploiVue monEmploi() {
        UtilisateurConnecte.etablissementActif();
        UUID engagement = enseignants.engagementActif(UtilisateurConnecte.id())
                .orElseThrow(() -> new AccesRefuseException("Réservé aux enseignants en fonction"));
        AnneeVue annee = annees.lister().stream().filter(a -> a.etat() == EtatAnnee.ACTIVE).findFirst()
                .orElse(null);
        if (annee == null) {
            return new MonEmploiVue(null, null, null, List.of(), List.of(), List.of(), List.of());
        }
        Instant publieLe = publications.findByAnneeId(annee.id()).map(PublicationEmploi::getPublieLe).orElse(null);
        if (publieLe == null) {
            return new MonEmploiVue(annee.id(), annee.libelle(), null, List.of(), List.of(), List.of(), List.of());
        }
        Instantane etat = chargement.charger(annee.id());
        List<MaSeanceVue> miennes = new ArrayList<>();
        for (SeanceEmploi s : etat.seances) {
            if (!engagement.equals(etat.engagement(s))) {
                continue;
            }
            Creneau c = etat.creneauParId.get(s.getCreneauId());
            MatiereDeClasseVue m = etat.matiere(s.getClasseId(), s.getMatiereId());
            AtelierCourtVue a = s.getAtelierId() == null ? null : etat.atelierParId.get(s.getAtelierId());
            miennes.add(new MaSeanceVue(s.getJour(), c.getId(), c.getHeureDebut(), c.getHeureFin(),
                    etat.classe(s.getClasseId()), m.matiereLibelle(), s.getGroupe(),
                    a == null ? null : a.code() + " – " + a.nom(), s.getSalle()));
        }
        miennes.sort(Comparator.comparingInt(MaSeanceVue::jour).thenComparing(MaSeanceVue::heureDebut));
        List<OccupationVue> ailleurs = etat.ailleurs.stream().filter(o -> o.engagementId().equals(engagement))
                .map(o -> new OccupationVue(o.engagementId(), o.jour(), o.debut(), o.fin()))
                .sorted(Comparator.comparingInt(OccupationVue::jour).thenComparing(OccupationVue::heureDebut)).toList();
        return new MonEmploiVue(annee.id(), annee.libelle(), publieLe, etat.jours(),
                etat.creneaux.stream().map(EmploiDuTempsService::vue).toList(), miennes, ailleurs);
    }

    // ------------------------------------------------------------------ outils

    EmploiDuTempsVue vue(Instantane etat) {
        Set<Domaine> domaines = responsabilites.domaines();
        boolean modifiable = estModifiable(etat.annee);
        List<ClasseEmploiVue> classes = new ArrayList<>();
        for (ClasseVue c : etat.classes) {
            List<MatiereClasseVue> matieres = etat.programme.getOrDefault(c.id(), List.of()).stream()
                    .map(m -> new MatiereClasseVue(m.matiereId(), m.matiereCode(), m.matiereLibelle(), m.type(),
                            Domaine.de(m.type()), m.volumeHebdo(), m.engagementId(),
                            m.engagementId() == null ? null : etat.noms.get(m.engagementId()),
                            Instantane.minutesPrevues(m.volumeHebdo()), etat.minutesPlacees(c.id(), m.matiereId())))
                    .toList();
            classes.add(new ClasseEmploiVue(c.id(), c.code(), c.niveau(), c.filiereId(), c.filiereCode(), matieres,
                    etat.groupes(c.id())));
        }
        List<SeanceVue> vuesSeances = etat.seances.stream()
                .sorted(Comparator.comparingInt(SeanceEmploi::getJour)
                        .thenComparing(s -> etat.creneauParId.get(s.getCreneauId()).getHeureDebut()))
                .map(s -> {
                    Domaine dom = etat.domaine(s);
                    return new SeanceVue(s.getId(), s.getClasseId(), s.getMatiereId(), s.getJour(), s.getCreneauId(),
                            s.getGroupe(), s.getAtelierId(), s.getSalle(), etat.engagement(s), dom,
                            modifiable && domaines.contains(dom));
                }).toList();
        List<EnseignantCourtVue> noms = etat.noms.entrySet().stream()
                .map(e -> new EnseignantCourtVue(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(EnseignantCourtVue::nom)).toList();
        List<AtelierEmploiVue> ateliers = etat.ateliers.stream()
                .map(a -> new AtelierEmploiVue(a.id(), a.code(), a.nom(), a.filieres())).toList();
        List<OccupationVue> ailleurs = etat.ailleurs.stream()
                .map(o -> new OccupationVue(o.engagementId(), o.jour(), o.debut(), o.fin())).toList();
        boolean grille = responsabilites.grille();
        Instant publieLe = publications.findByAnneeId(etat.annee.id()).map(PublicationEmploi::getPublieLe)
                .orElse(null);
        List<Domaine> mesDomaines = domaines.stream().sorted().toList();
        return new EmploiDuTempsVue(etat.annee.id(), etat.annee.libelle(), etat.jours(),
                etat.creneaux.stream().map(EmploiDuTempsService::vue).toList(), classes, vuesSeances, noms, ateliers,
                ailleurs, etat.conflits(), new DroitsEmploi(grille && modifiable, mesDomaines, grille && modifiable,
                        modifiable),
                publieLe);
    }

    static CreneauVue vue(Creneau c) {
        return new CreneauVue(c.getId(), c.getHeureDebut(), c.getHeureFin(), List.copyOf(c.jours()), c.minutes());
    }

    private static boolean estModifiable(AnneeVue a) {
        return a.etat() == EtatAnnee.PREPARATION || a.etat() == EtatAnnee.ACTIVE;
    }

    private static void exigerModifiable(AnneeVue a) {
        if (!estModifiable(a)) {
            throw new RegleMetierException("ANNEE_FERMEE", "L'année " + a.libelle() + " est close : son emploi du temps ne change plus");
        }
    }

    private static void exigerDomaine(Set<Domaine> domaines, Domaine d) {
        if (!domaines.contains(d)) {
            throw new AccesRefuseException(d == Domaine.GENERAL
                    ? "Les matières générales sont placées par le censeur"
                    : "Les matières techniques et pratiques sont placées par le chef des travaux");
        }
    }

    private static String texte(String valeur, int max, String quoi) {
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        String v = valeur.strip();
        if (v.length() > max) {
            throw new IllegalArgumentException(quoi + " : " + max + " caractères au plus");
        }
        return v;
    }
}
