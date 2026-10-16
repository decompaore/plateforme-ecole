package bf.edutech.plateforme.recrutement;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.recrutement.Vues.ClassementOffre;
import bf.edutech.plateforme.recrutement.Vues.CommissionVue;
import bf.edutech.plateforme.recrutement.Vues.LigneClassement;
import bf.edutech.plateforme.recrutement.Vues.MembreCommissionVue;
import bf.edutech.plateforme.recrutement.Vues.OffreVue;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.documents.RenduDocument;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.export.ExportTableaux;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;
import bf.edutech.plateforme.utilisateurs.Signatures;

/**
 * Documents de la commission de recrutement (v0.49) : procès-verbal de délibération (PDF, en-tête officiel, signature
 * numérisée du président) et résultats à afficher, par niveau et filière (Excel ou PDF).
 */
@Component
class DocumentsRecrutement {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    private final RecrutementService service;
    private final RenduDocument rendu;
    private final EntetesOfficiels entetes;
    private final Signatures signatures;

    DocumentsRecrutement(RecrutementService service, RenduDocument rendu, EntetesOfficiels entetes,
            Signatures signatures) {
        this.service = service;
        this.rendu = rendu;
        this.entetes = entetes;
        this.signatures = signatures;
    }

    public byte[] procesVerbal(UUID id) {
        CommissionVue c = service.commission(id);
        if (c.statut() == StatutCommission.PREPARATION) {
            throw new RegleMetierException("COMMISSION_NON_DELIBEREE", "Le procès-verbal suit la délibération");
        }
        List<ClassementOffre> classement = service.classement(id);
        MembreCommissionVue president = c.membres().stream().filter(m -> m.qualite() == Qualite.PRESIDENT).findFirst()
                .orElse(null);
        MembreCommissionVue rapporteur = c.membres().stream().filter(m -> m.qualite() == Qualite.RAPPORTEUR).findFirst()
                .orElse(null);
        List<String[]> rubriques = new ArrayList<>();
        rubriques.add(new String[] { "Année scolaire", c.annee() });
        rubriques.add(new String[] { "Session", c.dateSession().format(JOUR) + (c.lieu() == null ? "" : ", " + c.lieu()) });
        rubriques.add(new String[] { "Objet", "Complément d'effectif — " + c.intitule() });

        StringBuilder t = new StringBuilder();
        t.append("Le ").append(c.dateSession().format(JOUR)).append(", la commission de recrutement s'est réunie")
                .append(c.lieu() == null ? "" : " (" + c.lieu() + ")")
                .append(" pour examiner les candidatures au complément d'effectif de l'année scolaire ").append(c.annee())
                .append(".\n\nÉtaient présents :");
        for (MembreCommissionVue m : c.membres()) {
            if (m.present()) {
                t.append("\n- ").append(m.nom()).append(", ").append(m.fonction()).append(" (")
                        .append(m.qualite().libelle().toLowerCase(Locale.FRENCH)).append(")");
            }
        }
        List<MembreCommissionVue> absents = c.membres().stream().filter(m -> !m.present()).toList();
        if (!absents.isEmpty()) {
            t.append("\n\nAbsents :");
            absents.forEach(m -> t.append("\n- ").append(m.nom()).append(", ").append(m.fonction()));
        }
        t.append("\n\nCritères retenus : moyenne minimale de ")
                .append(c.moyenneMinimale().stripTrailingZeros().toPlainString()).append("/20");
        if (c.poidsExamen().signum() > 0) {
            t.append(" ; score pondéré avec la note d'examen (poids ")
                    .append(c.poidsExamen().stripTrailingZeros().toPlainString()).append(")");
        }
        t.append(" ; à égalité, priorité au plus jeune ; liste d'attente de ").append(c.tailleListeAttente())
                .append(" candidat(s) par niveau.");
        if (c.criteres() != null) {
            t.append("\n").append(c.criteres());
        }
        t.append("\n\nPlaces offertes :");
        for (OffreVue o : c.offres()) {
            if (o.places() > 0 || o.candidatures() > 0) {
                t.append("\n- ").append(o.niveau()).append(" ").append(o.filiere()).append(" : ").append(o.places())
                        .append(" place(s), ").append(o.candidatures()).append(" candidature(s)");
            }
        }
        for (ClassementOffre o : classement) {
            if (o.lignes().isEmpty()) {
                continue;
            }
            t.append("\n\n").append(o.offre().toUpperCase(Locale.FRENCH)).append(" — ").append(o.places())
                    .append(" place(s)");
            section(t, "Candidats retenus", o, StatutCandidature.RETENUE, StatutCandidature.INSCRITE);
            section(t, "Liste d'attente", o, StatutCandidature.LISTE_ATTENTE);
            section(t, "Non retenus", o, StatutCandidature.NON_RETENUE);
            section(t, "Candidatures irrecevables", o, StatutCandidature.IRRECEVABLE);
        }
        if (c.observationsPv() != null) {
            t.append("\n\nObservations :\n").append(c.observationsPv());
        }
        if (rapporteur != null) {
            t.append("\n\nLe rapporteur : ").append(rapporteur.nom());
        }
        byte[] signature = president == null || president.utilisateurId() == null ? null
                : signatures.signature(president.utilisateurId()).orElse(null);
        return rendu.rendre(new RenduDocument.Contenu(c.numero(), "Procès-verbal de la commission de recrutement",
                rubriques, t.toString(), c.delibereeLe() == null ? c.dateSession()
                        : c.delibereeLe().atZone(RecrutementService.FUSEAU).toLocalDate(),
                "Le Président de la commission", president == null ? null : president.nom(), signature, false,
                "Procès-verbal " + c.numero() + " — " + c.intitule()));
    }

    private static void section(StringBuilder t, String titre, ClassementOffre o, StatutCandidature... statuts) {
        List<LigneClassement> l = o.lignes().stream().filter(x -> List.of(statuts).contains(x.decision())).toList();
        if (l.isEmpty()) {
            return;
        }
        t.append("\n").append(titre).append(" :");
        for (LigneClassement x : l) {
            t.append("\n").append(x.rang() == null ? "-" : x.rang() + ".").append(" ").append(x.nom()).append(" ")
                    .append(x.prenoms()).append(" (").append(x.numero()).append(")");
            if (x.score() != null) {
                t.append(" — ").append(x.score().stripTrailingZeros().toPlainString().replace('.', ','));
            }
            if (x.motif() != null && (x.decision() == StatutCandidature.IRRECEVABLE
                    || x.decision() == StatutCandidature.NON_RETENUE)) {
                t.append(" — ").append(x.motif());
            }
        }
    }

    /** Résultats à afficher : retenus et liste d'attente, par niveau et filière. */
    public ResponseEntity<byte[]> resultats(UUID id, ExportTableaux.Format format) {
        CommissionVue c = service.commission(id);
        if (c.statut() == StatutCommission.PREPARATION) {
            throw new RegleMetierException("COMMISSION_NON_DELIBEREE", "Les résultats suivent la délibération");
        }
        List<Tableau> tableaux = new ArrayList<>();
        for (ClassementOffre o : service.classement(id)) {
            List<LigneClassement> lignes = o.lignes().stream().filter(x -> x.decision() == StatutCandidature.RETENUE
                    || x.decision() == StatutCandidature.INSCRITE || x.decision() == StatutCandidature.LISTE_ATTENTE)
                    .toList();
            if (lignes.isEmpty()) {
                continue;
            }
            Tableau t = new Tableau(o.offre().length() > 28 ? o.offre().substring(0, 28) : o.offre(),
                    "Résultats du complément d'effectif — " + o.offre())
                    .sousTitre("Commission " + c.numero() + " du " + c.dateSession().format(JOUR) + " · " + o.places()
                            + " place(s)")
                    .colonnes(Colonne.nombre("Rang", 0.6f), Colonne.texte("N°", 1.4f), Colonne.texte("Nom", 1.6f),
                            Colonne.texte("Prénoms", 2), Colonne.texte("Sexe", 0.6f), Colonne.texte("Score", 0.8f),
                            Colonne.texte("Décision", 1.4f));
            for (LigneClassement x : lignes) {
                t.ligne(x.rang(), x.numero(), x.nom(), x.prenoms(), x.sexe().name(),
                        x.score() == null ? "" : x.score().stripTrailingZeros().toPlainString().replace('.', ','),
                        x.decision() == StatutCandidature.LISTE_ATTENTE ? "Liste d'attente" : "Retenu(e)");
            }
            t.note("Les candidats retenus se présentent à l'intendance pour leur inscription. En cas de désistement, "
                    + "la place revient au premier de la liste d'attente.");
            tableaux.add(t);
        }
        if (tableaux.isEmpty()) {
            throw new RegleMetierException("AUCUN_RETENU", "Aucun candidat retenu");
        }
        return ExportTableaux.reponse(entetes.courant(), "resultats-" + c.numero().toLowerCase(Locale.ROOT), format,
                tableaux.toArray(Tableau[]::new));
    }
}
