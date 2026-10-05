package bf.edutech.plateforme.emploidutemps;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.ateliers.Vues.AtelierCourtVue;
import bf.edutech.plateforme.emploidutemps.Vues.MaSeanceVue;
import bf.edutech.plateforme.emploidutemps.Vues.MonEmploiVue;
import bf.edutech.plateforme.emploidutemps.Vues.OccupationVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.export.ExportTableaux;
import bf.edutech.plateforme.socle.export.ExportTableaux.Format;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;

/**
 * Emplois du temps en Excel ou en PDF : une grille (heures en lignes, jours en colonnes) par
 * classe, par enseignant ou par atelier ; sans filtre, toutes les classes (une page chacune).
 */
@Service
public class ExportsEmploi {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] JOURS = { "", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche" };

    private final EmploiDuTempsService service;
    private final NotificationsService notifications;

    ExportsEmploi(EmploiDuTempsService service, NotificationsService notifications) {
        this.service = service;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> emploi(UUID anneeId, UUID classeId, UUID engagementId, UUID atelierId, Format format) {
        Instantane etat = service.instantane(anneeId);
        if (etat.creneaux.isEmpty()) {
            throw new RegleMetierException("GRILLE_VIDE", "La grille horaire de l'année n'est pas encore définie");
        }
        String publie = publication(service.publieLe(anneeId));
        List<Tableau> tableaux = new ArrayList<>();
        String nom;
        if (classeId != null) {
            ClasseVue c = etat.classeParId.get(classeId);
            if (c == null) {
                throw new RessourceIntrouvableException("Classe introuvable pour cette année");
            }
            tableaux.add(classe(etat, c, publie));
            nom = "emploi-du-temps-" + c.code();
        } else if (engagementId != null) {
            String enseignant = etat.noms.get(engagementId);
            if (enseignant == null) {
                throw new RessourceIntrouvableException("Enseignant introuvable");
            }
            tableaux.add(grille(etat, "Emploi du temps : " + enseignant, publie,
                    s -> engagementId.equals(etat.engagement(s)),
                    s -> etat.classe(s.getClasseId()) + groupe(s) + "\n" + matiere(etat, s) + lieu(etat, s),
                    etat.ailleurs.stream().filter(o -> o.engagementId().equals(engagementId))
                            .map(o -> new OccupationVue(o.engagementId(), o.jour(), o.debut(), o.fin())).toList()));
            nom = "emploi-du-temps-" + enseignant;
        } else if (atelierId != null) {
            AtelierCourtVue a = etat.atelierParId.get(atelierId);
            if (a == null) {
                throw new RessourceIntrouvableException("Atelier introuvable ou fermé");
            }
            tableaux.add(grille(etat, "Occupation de l'atelier " + a.code() + " – " + a.nom(), publie,
                    s -> atelierId.equals(s.getAtelierId()),
                    s -> etat.classe(s.getClasseId()) + groupe(s) + "\n" + matiere(etat, s) + enseignant(etat, s),
                    List.of()));
            nom = "atelier-" + a.code();
        } else {
            if (etat.classes.isEmpty()) {
                throw new RegleMetierException("AUCUNE_CLASSE", "Aucune classe pour cette année");
            }
            etat.classes.forEach(c -> tableaux.add(classe(etat, c, publie)));
            nom = "emplois-du-temps-" + etat.annee.libelle();
        }
        return ExportTableaux.reponse(notifications.nomEtablissement(), propre(nom), format,
                tableaux.toArray(Tableau[]::new));
    }

    /** Emploi du temps publié de l'enseignant connecté. */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> monEmploi(Format format) {
        MonEmploiVue moi = service.monEmploi();
        if (moi.publieLe() == null) {
            throw new RegleMetierException("NON_PUBLIE", "L'emploi du temps n'est pas encore publié");
        }
        Tableau t = new Tableau("Mon emploi du temps", "Mon emploi du temps")
                .sousTitre("Année " + moi.annee() + publication(moi.publieLe()));
        t.colonnes(colonnes(moi.jours()));
        for (Vues.CreneauVue c : moi.creneaux()) {
            List<Object> ligne = new ArrayList<>();
            ligne.add(Instantane.heure(c.heureDebut()) + " – " + Instantane.heure(c.heureFin()));
            for (int jour : moi.jours()) {
                if (!c.jours().contains(jour)) {
                    ligne.add("");
                    continue;
                }
                List<String> textes = new ArrayList<>();
                for (MaSeanceVue s : moi.seances()) {
                    if (s.jour() == jour && s.creneauId().equals(c.id())) {
                        textes.add(s.classe() + (s.groupe() == null ? "" : " (" + s.groupe() + ")") + "\n" + s.matiere()
                                + (s.atelier() != null ? "\n" + s.atelier() : s.salle() != null ? "\n" + s.salle() : ""));
                    }
                }
                if (textes.isEmpty() && moi.ailleurs().stream().anyMatch(o -> o.jour() == jour
                        && o.heureDebut().isBefore(c.heureFin()) && c.heureDebut().isBefore(o.heureFin()))) {
                    textes.add("Autre établissement");
                }
                ligne.add(String.join("\n—\n", textes));
            }
            t.ligne(ligne.toArray());
        }
        int minutes = moi.seances().stream()
                .mapToInt(s -> (int) java.time.Duration.between(s.heureDebut(), s.heureFin()).toMinutes()).sum();
        t.note(heures(minutes) + " de cours par semaine dans l'établissement.");
        return ExportTableaux.reponse(notifications.nomEtablissement(), "mon-emploi-du-temps", format, t);
    }

    // ------------------------------------------------------------------

    private Tableau classe(Instantane etat, ClasseVue c, String publie) {
        Tableau t = grille(etat, "Emploi du temps : " + c.code(), publie, s -> s.getClasseId().equals(c.id()),
                s -> matiere(etat, s) + groupe(s) + enseignant(etat, s) + lieu(etat, s), List.of());
        int prevu = 0;
        int place = 0;
        List<String> incompletes = new ArrayList<>();
        for (MatiereDeClasseVue m : etat.programme.getOrDefault(c.id(), List.of())) {
            int p = Instantane.minutesPrevues(m.volumeHebdo());
            int q = etat.minutesPlacees(c.id(), m.matiereId());
            prevu += p;
            place += q;
            if (q < p) {
                incompletes.add(m.matiereLibelle() + " (" + heures(q) + " sur " + heures(p) + ")");
            }
        }
        t.note("Volume horaire hebdomadaire placé : " + heures(place) + " sur " + heures(prevu) + " prévues.");
        if (!incompletes.isEmpty()) {
            t.note("À compléter : " + String.join(", ", incompletes) + ".");
        }
        return t;
    }

    private Tableau grille(Instantane etat, String titre, String publie, Predicate<SeanceEmploi> filtre,
            Function<SeanceEmploi, String> texte, List<OccupationVue> ailleurs) {
        String feuille = titre.replace("Emploi du temps : ", "").replace("Occupation de l'atelier ", "");
        Tableau t = new Tableau(feuille, titre).sousTitre("Année " + etat.annee.libelle() + publie);
        List<Integer> jours = etat.jours();
        t.colonnes(colonnes(jours));
        for (Creneau c : etat.creneaux) {
            List<Object> ligne = new ArrayList<>();
            ligne.add(Instantane.heure(c.getHeureDebut()) + " – " + Instantane.heure(c.getHeureFin()));
            for (int jour : jours) {
                if (!c.existeLe(jour)) {
                    ligne.add("");
                    continue;
                }
                List<String> textes = etat.seances.stream()
                        .filter(s -> s.getJour() == jour && s.getCreneauId().equals(c.getId()) && filtre.test(s))
                        .map(texte).toList();
                if (textes.isEmpty() && ailleurs.stream().anyMatch(o -> o.jour() == jour
                        && o.heureDebut().isBefore(c.getHeureFin()) && c.getHeureDebut().isBefore(o.heureFin()))) {
                    textes = List.of("Autre établissement");
                }
                ligne.add(String.join("\n—\n", textes));
            }
            t.ligne(ligne.toArray());
        }
        return t;
    }

    private static Colonne[] colonnes(List<Integer> jours) {
        List<Colonne> cs = new ArrayList<>();
        cs.add(Colonne.texte("Heures", 1.1f));
        jours.forEach(j -> cs.add(Colonne.texte(JOURS[j], 2f)));
        return cs.toArray(Colonne[]::new);
    }

    private static String matiere(Instantane etat, SeanceEmploi s) {
        MatiereDeClasseVue m = etat.matiere(s.getClasseId(), s.getMatiereId());
        return m == null ? "?" : m.matiereLibelle();
    }

    private static String groupe(SeanceEmploi s) {
        return s.getGroupe() == null ? "" : " (" + s.getGroupe() + ")";
    }

    private static String enseignant(Instantane etat, SeanceEmploi s) {
        UUID e = etat.engagement(s);
        return e == null ? "\nSans enseignant" : "\n" + etat.noms.getOrDefault(e, "");
    }

    private static String lieu(Instantane etat, SeanceEmploi s) {
        if (s.getAtelierId() != null) {
            AtelierCourtVue a = etat.atelierParId.get(s.getAtelierId());
            return a == null ? "" : "\nAtelier " + a.code();
        }
        return s.getSalle() == null ? "" : "\n" + s.getSalle();
    }

    private static String publication(Instant publieLe) {
        return publieLe == null ? " · version de travail (non publiée)"
                : " · publié le " + publieLe.atZone(FUSEAU).toLocalDate().format(DATE);
    }

    static String heures(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + " h" : h + " h " + String.format("%02d", m);
    }

    private static String propre(String nom) {
        return nom.replaceAll("[^A-Za-z0-9-]+", "-") + "-" + LocalDate.now(FUSEAU);
    }
}
