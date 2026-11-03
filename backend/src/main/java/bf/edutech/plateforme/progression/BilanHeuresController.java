package bf.edutech.plateforme.progression;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.progression.BilanHeuresService.BilanHeuresVue;
import bf.edutech.plateforme.progression.BilanHeuresService.Cumul;
import bf.edutech.plateforme.progression.BilanHeuresService.EtatHeures;
import bf.edutech.plateforme.progression.BilanHeuresService.LigneBilan;
import bf.edutech.plateforme.progression.BilanHeuresService.SourceVolume;
import bf.edutech.plateforme.progression.BilanHeuresService.TotalClasse;
import bf.edutech.plateforme.progression.BilanHeuresService.TotalEnseignant;
import bf.edutech.plateforme.progression.BilanHeuresService.TotalMatiere;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.export.ExportTableaux;
import bf.edutech.plateforme.socle.export.ExportTableaux.Format;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;

/** Bilan des heures dues et réalisées (v0.50) : direction, enseignant, exports Excel et PDF. */
@RestController
public class BilanHeuresController {

    static final Map<EtatHeures, String> ETATS = Map.of(EtatHeures.SANS_VOLUME, "Volume non saisi",
            EtatHeures.PAS_COMMENCE, "Pas commencé", EtatHeures.EN_RETARD, "En retard", EtatHeures.A_JOUR, "À jour",
            EtatHeures.EN_AVANCE, "En avance", EtatHeures.TERMINE, "Terminé");
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BilanHeuresService service;
    private final EntetesOfficiels entetes;

    BilanHeuresController(BilanHeuresService service, EntetesOfficiels entetes) {
        this.service = service;
        this.entetes = entetes;
    }

    @GetMapping("/api/v1/annees/{anneeId}/bilan-heures")
    @PreAuthorize(ProgressionController.SUPERVISION)
    public BilanHeuresVue bilan(@PathVariable UUID anneeId) {
        return service.bilan(anneeId);
    }

    @GetMapping("/api/v1/espace-enseignant/bilan-heures")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public BilanHeuresVue monBilan() {
        return service.monBilan();
    }

    /** {@code format} : xlsx (par défaut) ou pdf. */
    @GetMapping("/api/v1/annees/{anneeId}/bilan-heures/export")
    @PreAuthorize(ProgressionController.SUPERVISION)
    public ResponseEntity<byte[]> exporter(@PathVariable UUID anneeId,
            @RequestParam(required = false) String format) {
        return fichier(service.bilan(anneeId), Format.lire(format), "bilan-heures");
    }

    @GetMapping("/api/v1/espace-enseignant/bilan-heures/export")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public ResponseEntity<byte[]> exporterMonBilan(@RequestParam(required = false) String format) {
        return fichier(service.monBilan(), Format.lire(format), "mes-heures");
    }

    private ResponseEntity<byte[]> fichier(BilanHeuresVue b, Format format, String nom) {
        String sousTitre = "Année " + b.annee() + " — situation au " + b.jour().format(JOUR) + " ("
                + b.avancementAnnee() + " % de l'année écoulée)";
        Tableau te = new Tableau("Par enseignant", "Bilan des heures par enseignant").sousTitre(sousTitre)
                .colonnes(Colonne.texte("Enseignant", 12), Colonne.texte("Statut", 6), Colonne.nombre("Classes", 4),
                        Colonne.nombre("Matières", 4), Colonne.nombre("Dû annuel (h)", 6), Colonne.nombre("Dû à ce jour (h)", 6),
                        Colonne.nombre("Réalisé (h)", 6), Colonne.nombre("Reste (h)", 5), Colonne.nombre("Taux annuel %", 5),
                        Colonne.nombre("Taux à date %", 5), Colonne.nombre("Écart (h)", 5), Colonne.nombre("En retard", 4));
        for (TotalEnseignant t : b.enseignants()) {
            Cumul c = t.cumul();
            te.ligne(t.enseignant(), statut(t.statutEnseignant()), t.classes(), t.matieres(), c.volumeAnnuel(),
                    c.duADate(), c.realise(), c.reste(), c.tauxRealisation(), c.tauxADate(), c.ecart(), t.enRetard());
        }
        Cumul g = b.total();
        te.total("Total", "", null, null, g.volumeAnnuel(), g.duADate(), g.realise(), g.reste(), g.tauxRealisation(),
                g.tauxADate(), g.ecart(), b.enRetard());
        te.note("Le dû porte sur les matières confiées à l'enseignant ; le réalisé compte toutes les heures notées "
                + "au cahier de textes, y compris dans une classe reprise depuis par un autre enseignant.");

        Tableau td = new Tableau("Détail", "Bilan des heures par classe et par matière").sousTitre(sousTitre)
                .colonnes(Colonne.texte("Classe", 4), Colonne.texte("Matière", 10), Colonne.texte("Enseignant", 10),
                        Colonne.nombre("H/sem.", 3), Colonne.nombre("Dû annuel (h)", 5), Colonne.nombre("Dû à ce jour (h)", 5),
                        Colonne.nombre("Réalisé (h)", 5), Colonne.nombre("Reste (h)", 4), Colonne.nombre("Taux %", 4),
                        Colonne.nombre("Écart (h)", 4), Colonne.nombre("Séances", 4), Colonne.texte("Dernière séance", 5),
                        Colonne.texte("État", 6));
        for (LigneBilan l : b.lignes()) {
            Cumul c = l.cumul();
            String ens = l.enseignant() == null ? "aucun" : l.enseignant();
            if (l.intervenants().stream().anyMatch(i -> !i.engagementId().equals(l.engagementId()))) {
                ens += " (avec " + l.intervenants().stream().filter(i -> !i.engagementId().equals(l.engagementId()))
                        .map(i -> i.enseignant() + " : " + i.heures() + " h").collect(Collectors.joining(", ")) + ")";
            }
            td.ligne(l.classeCode(), l.matiereLibelle(), ens, l.volumeHebdo(),
                    c.volumeAnnuel() == null ? null
                            : l.sourceVolume() == SourceVolume.ESTIME ? c.volumeAnnuel() + " (estimé)" : c.volumeAnnuel(),
                    c.duADate(), c.realise(), c.reste(), c.tauxRealisation(), c.ecart(), l.seances(),
                    l.derniereSeance() == null ? "" : l.derniereSeance().format(JOUR), ETATS.get(l.etat()));
        }
        td.note("Dû à ce jour : volume annuel au prorata des jours de cours écoulés (périodes de l'année). "
                + "Volume « estimé » : heures par semaine × semaines de cours, faute de volume annuel saisi.");

        Tableau tm = new Tableau("Par matière", "Bilan des heures par matière").sousTitre(sousTitre)
                .colonnes(Colonne.texte("Matière", 12), Colonne.nombre("Classes", 4), Colonne.nombre("Dû annuel (h)", 6),
                        Colonne.nombre("Dû à ce jour (h)", 6), Colonne.nombre("Réalisé (h)", 6), Colonne.nombre("Reste (h)", 5),
                        Colonne.nombre("Taux annuel %", 5), Colonne.nombre("Écart (h)", 5), Colonne.nombre("En retard", 4));
        for (TotalMatiere t : b.matieres()) {
            Cumul c = t.cumul();
            tm.ligne(t.matiereLibelle(), t.classes(), c.volumeAnnuel(), c.duADate(), c.realise(), c.reste(),
                    c.tauxRealisation(), c.ecart(), t.enRetard());
        }

        Tableau tc = new Tableau("Par classe", "Bilan des heures par classe").sousTitre(sousTitre)
                .colonnes(Colonne.texte("Classe", 5), Colonne.texte("Filière", 5), Colonne.nombre("Matières", 4),
                        Colonne.nombre("Dû annuel (h)", 6), Colonne.nombre("Dû à ce jour (h)", 6), Colonne.nombre("Réalisé (h)", 6),
                        Colonne.nombre("Reste (h)", 5), Colonne.nombre("Taux annuel %", 5), Colonne.nombre("Écart (h)", 5),
                        Colonne.nombre("En retard", 4));
        for (TotalClasse t : b.classes()) {
            Cumul c = t.cumul();
            tc.ligne(t.classeCode(), t.filiereCode(), t.matieres(), c.volumeAnnuel(), c.duADate(), c.realise(), c.reste(),
                    c.tauxRealisation(), c.ecart(), t.enRetard());
        }
        return ExportTableaux.reponse(entetes.courant(), nom + "-" + b.jour(), format, te, td, tm, tc);
    }

    private static String statut(String s) {
        return s == null ? "" : s.equals("VACATAIRE") ? "Vacataire" : "Titulaire";
    }
}
