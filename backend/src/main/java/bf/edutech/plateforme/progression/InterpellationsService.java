package bf.edutech.plateforme.progression;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.messagerie.MessagerieService;
import bf.edutech.plateforme.progression.AccesMatiere.Contexte;
import bf.edutech.plateforme.progression.Vues.Domaine;
import bf.edutech.plateforme.progression.Vues.Retard;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.documents.RenduDocument;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;
import bf.edutech.plateforme.utilisateurs.Signatures;

/**
 * Interpellation des enseignants (v0.48.1). Au vu de la progression et du cahier de textes d'une matière, le censeur
 * (matières générales), le chef des travaux (matières techniques et pratiques) ou le chef d'établissement adresse à
 * l'enseignant :
 * <ul>
 * <li>un <b>rappel</b> : message de rappel au programme, sans numéro ;</li>
 * <li>une <b>demande d'explication</b> : lettre numérotée (DE-AAAA-NNNN) avec délai de réponse, imprimable avec
 * l'en-tête officiel ; le chef d'établissement en reçoit copie.</li>
 * </ul>
 * Le constat chiffré (heures prévues à date, heures faites, séquences échues sans séance) est figé à l'émission.
 * L'enseignant la reçoit dans sa messagerie, la lit et répond dans l'application ; la direction note la suite donnée
 * et clôt.
 */
@Service
public class InterpellationsService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter JOUR_LONG = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    static final int DELAI_PAR_DEFAUT = 7;

    public enum TypeInterpellation {
        RAPPEL, DEMANDE_EXPLICATION
    }

    public enum StatutInterpellation {
        ENVOYEE, LUE, REPONDUE, CLOSE
    }

    public record Proposition(UUID classeId, String classeCode, UUID matiereId, String matiere, Domaine domaine,
            String enseignant, Retard retard, String objet, String constat, String demandeRappel,
            String demandeExplication, LocalDate delaiReponse, int interpellationsEnCours) {
    }

    public record DonneesInterpellation(TypeInterpellation type, String objet, String constat, String demande,
            LocalDate delaiReponse) {
    }

    public record DemandeReponse(String reponse) {
    }

    public record DemandeCloture(String suite) {
    }

    public record InterpellationVue(UUID id, String numero, TypeInterpellation type, UUID classeId, String classeCode,
            UUID matiereId, String matiere, Domaine domaine, String enseignant, String objet, String constat,
            String demande, BigDecimal heuresPrevues, BigDecimal heuresRealisees, LocalDate delaiReponse,
            String emetteur, String qualiteEmetteur, Instant emiseLe, StatutInterpellation statut, Instant lueLe,
            String reponse, Instant reponduLe, String suite, Instant closeLe, String closePar, boolean delaiDepasse,
            boolean peutRepondre, boolean peutClore) {
    }

    private final JdbcTemplate jdbc;
    private final AccesMatiere acces;
    private final Supervision supervision;
    private final FicheProgressionRepository fiches;
    private final SequenceProgressionRepository sequences;
    private final SeanceCahierRepository seances;
    private final MessagerieService messagerie;
    private final MembresService membres;
    private final Signatures signatures;
    private final Compteurs compteurs;
    private final RenduDocument rendu;
    private final AuditService audit;
    private final Clock horloge;

    InterpellationsService(JdbcTemplate jdbc, AccesMatiere acces, Supervision supervision,
            FicheProgressionRepository fiches, SequenceProgressionRepository sequences, SeanceCahierRepository seances,
            MessagerieService messagerie, MembresService membres, Signatures signatures, Compteurs compteurs,
            RenduDocument rendu, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.supervision = supervision;
        this.fiches = fiches;
        this.sequences = sequences;
        this.seances = seances;
        this.messagerie = messagerie;
        this.membres = membres;
        this.signatures = signatures;
        this.compteurs = compteurs;
        this.rendu = rendu;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ================================================================== préparation

    /** Constat proposé (modifiable avant l'envoi) pour une matière d'une classe. */
    @Transactional(readOnly = true)
    public Proposition proposition(UUID classeId, UUID matiereId) {
        Contexte c = exigerSuperviseur(classeId, matiereId);
        Enseignant e = enseignant(c);
        LocalDate jour = aujourdhui();
        Etat etat = etat(c, jour);
        LocalDate delai = jour.plusDays(DELAI_PAR_DEFAUT);
        String m = c.matiere().matiereLibelle();
        String cl = c.classe().code();
        String objet = etat.retard.enRetard() || etat.fiche == null
                ? "Retard sur la progression de " + m + " en " + cl
                : "Suivi de la progression de " + m + " en " + cl;
        String rappel = "Je vous invite à prendre sans tarder les dispositions nécessaires pour rattraper ce retard, "
                + "et à tenir à jour votre cahier de textes.";
        String explication = "Je vous demande de bien vouloir me fournir par écrit, au plus tard le "
                + delai.format(JOUR_LONG) + ", les explications sur cette situation, ainsi que les dispositions que "
                + "vous comptez prendre pour rattraper le programme.";
        Integer enCours = jdbc.queryForObject("""
                select count(*) from interpellation where classe_id = ? and matiere_id = ? and statut <> 'CLOSE'""",
                Integer.class, classeId, matiereId);
        return new Proposition(classeId, cl, matiereId, m, c.domaine(), e.nom, etat.retard, objet,
                constat(c, etat, jour), rappel, explication, delai, enCours == null ? 0 : enCours);
    }

    // ================================================================== émission

    @Transactional
    public InterpellationVue emettre(UUID classeId, UUID matiereId, DonneesInterpellation d) {
        Contexte c = exigerSuperviseur(classeId, matiereId);
        if (d == null || d.type() == null) {
            throw new IllegalArgumentException("Type obligatoire : RAPPEL ou DEMANDE_EXPLICATION");
        }
        String objet = texte(d.objet(), 200, "Objet");
        String constat = texte(d.constat(), 4000, "Constat");
        String demande = d.demande() == null || d.demande().isBlank() ? null : d.demande().strip();
        if (demande != null && demande.length() > 2000) {
            throw new IllegalArgumentException("Demande de 2000 caractères au plus");
        }
        LocalDate jour = aujourdhui();
        LocalDate delai = d.delaiReponse();
        if (d.type() == TypeInterpellation.DEMANDE_EXPLICATION) {
            if (delai == null) {
                throw new IllegalArgumentException("Une demande d'explication fixe un délai de réponse");
            }
            if (!delai.isAfter(jour)) {
                throw new IllegalArgumentException("Le délai de réponse est postérieur à aujourd'hui");
            }
        }
        Enseignant e = enseignant(c);
        UUID moi = UtilisateurConnecte.id();
        if (e.utilisateurId.equals(moi)) {
            throw new RegleMetierException("AUTO_INTERPELLATION", "Vous ne pouvez pas vous interpeller vous-même");
        }
        Etat etat = etat(c, jour);
        String numero = null;
        if (d.type() == TypeInterpellation.DEMANDE_EXPLICATION) {
            numero = "DE-" + jour.getYear() + "-"
                    + String.format("%04d", compteurs.suivant("DEMANDE_EXPLICATION_" + jour.getYear()));
        }
        String qualite = qualite(c.domaine());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into interpellation (tenant_id, id, numero, type, classe_id, matiere_id, engagement_id,
                    destinataire_id, domaine, objet, constat, demande, heures_prevues, heures_realisees, delai_reponse,
                    qualite_emetteur, emise_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, numero, d.type().name(), classeId, matiereId, c.matiere().engagementId(), e.utilisateurId,
                c.domaine().name(), objet, constat, demande, etat.retard.heuresPrevuesADate(),
                etat.retard.heuresRealisees(), d.type() == TypeInterpellation.DEMANDE_EXPLICATION ? delai : null,
                qualite, moi);

        // Messagerie : l'enseignant ; copie au chef d'établissement pour une demande d'explication
        String titre = d.type() == TypeInterpellation.DEMANDE_EXPLICATION ? "Demande d'explication n° " + numero
                : "Rappel";
        String corps = constat + (demande == null ? "" : "\n\n" + demande)
                + (d.type() == TypeInterpellation.DEMANDE_EXPLICATION
                        ? "\n\nRépondez depuis « Mes interpellations » au plus tard le " + delai.format(JOUR) + "."
                        : "\n\nVous pouvez répondre depuis « Mes interpellations ».")
                + "\n\n" + qualite;
        transmettre(e.utilisateurId, titre + " — " + objet, corps);
        if (d.type() == TypeInterpellation.DEMANDE_EXPLICATION) {
            for (UUID chef : chefs()) {
                if (!chef.equals(moi)) {
                    transmettre(chef, "Copie : " + titre + " — " + e.nom,
                            "Copie de la demande d'explication adressée à " + e.nom + ".\n\n" + corps);
                }
            }
        }
        audit.enregistrer("INTERPELLATION_EMISE", numero == null ? id.toString() : numero,
                Map.of("type", d.type().name(), "classe", c.classe().code(), "matiere", c.matiere().matiereCode()));
        return detail(id);
    }

    // ================================================================== consultation

    /** Suivi de la direction : interpellations de son domaine (toutes pour le chef d'établissement). */
    @Transactional(readOnly = true)
    public List<InterpellationVue> lister(UUID anneeId, UUID classeId, UUID matiereId) {
        Set<Domaine> domaines = supervision.domaines();
        if (domaines.isEmpty()) {
            throw new AccesRefuseException("Réservé à la direction");
        }
        List<Object> p = new ArrayList<>();
        StringBuilder w = new StringBuilder(" where i.domaine = any (?::varchar[])");
        p.add("{" + String.join(",", domaines.stream().map(Enum::name).toList()) + "}");
        if (anneeId != null) {
            w.append(" and c.annee_id = ?");
            p.add(anneeId);
        }
        if (classeId != null) {
            w.append(" and i.classe_id = ?");
            p.add(classeId);
        }
        if (matiereId != null) {
            w.append(" and i.matiere_id = ?");
            p.add(matiereId);
        }
        return jdbc.query(SELECT + w + " order by i.emise_le desc", (l, n) -> vue(l, domaines), p.toArray());
    }

    /** Les interpellations reçues par l'enseignant connecté. */
    @Transactional(readOnly = true)
    public List<InterpellationVue> miennes() {
        Set<Domaine> domaines = supervision.domaines();
        return jdbc.query(SELECT + " where i.destinataire_id = ? order by i.emise_le desc", (l, n) -> vue(l, domaines),
                UtilisateurConnecte.id());
    }

    /** Détail ; l'ouverture par l'enseignant destinataire la marque lue. */
    @Transactional
    public InterpellationVue consulter(UUID id) {
        InterpellationVue v = exigerVisible(id);
        if (destinataire(id) && v.statut() == StatutInterpellation.ENVOYEE) {
            jdbc.update("update interpellation set statut = 'LUE', lue_le = now() where id = ?", id);
            v = detail(id);
        }
        return v;
    }

    // ================================================================== réponse et suite

    @Transactional
    public InterpellationVue repondre(UUID id, DemandeReponse d) {
        InterpellationVue v = detail(id);
        if (!destinataire(id)) {
            throw new AccesRefuseException("Seul l'enseignant interpellé répond");
        }
        if (v.statut() == StatutInterpellation.CLOSE) {
            throw new RegleMetierException("INTERPELLATION_CLOSE", "Cette interpellation est close");
        }
        if (v.statut() == StatutInterpellation.REPONDUE) {
            throw new RegleMetierException("DEJA_REPONDUE", "Vous avez déjà répondu à cette interpellation");
        }
        String reponse = texte(d == null ? null : d.reponse(), 4000, "Réponse");
        jdbc.update("""
                update interpellation set statut = 'REPONDUE', reponse = ?, repondue_le = now(),
                    lue_le = coalesce(lue_le, now()) where id = ?""", reponse, id);
        UUID emetteur = jdbc.queryForObject("select emise_par from interpellation where id = ?", UUID.class, id);
        transmettre(emetteur, "Réponse de " + v.enseignant() + " — " + (v.numero() == null ? v.objet() : v.numero()),
                reponse);
        audit.enregistrer("INTERPELLATION_REPONDUE", v.numero() == null ? id.toString() : v.numero(), null);
        return detail(id);
    }

    @Transactional
    public InterpellationVue clore(UUID id, DemandeCloture d) {
        InterpellationVue v = detail(id);
        exigerDomaine(v.domaine());
        if (v.statut() == StatutInterpellation.CLOSE) {
            throw new RegleMetierException("INTERPELLATION_CLOSE", "Cette interpellation est déjà close");
        }
        String suite = d == null || d.suite() == null || d.suite().isBlank() ? null : d.suite().strip();
        if (suite != null && suite.length() > 1000) {
            throw new IllegalArgumentException("Suite de 1000 caractères au plus");
        }
        if (v.type() == TypeInterpellation.DEMANDE_EXPLICATION && suite == null) {
            throw new IllegalArgumentException("Indiquez la suite donnée à la demande d'explication");
        }
        jdbc.update("""
                update interpellation set statut = 'CLOSE', suite = ?, close_par = ?, close_le = now() where id = ?""",
                suite, UtilisateurConnecte.id(), id);
        audit.enregistrer("INTERPELLATION_CLOSE", v.numero() == null ? id.toString() : v.numero(), null);
        return detail(id);
    }

    // ================================================================== lettre

    /** Lettre (PDF A4, en-tête officiel, signature numérisée de l'émetteur si elle existe). */
    @Transactional(readOnly = true)
    public byte[] lettre(UUID id) {
        InterpellationVue v = exigerVisible(id);
        UUID emetteur = jdbc.queryForObject("select emise_par from interpellation where id = ?", UUID.class, id);
        boolean explication = v.type() == TypeInterpellation.DEMANDE_EXPLICATION;
        List<String[]> rubriques = new ArrayList<>();
        rubriques.add(new String[] { "Destinataire", v.enseignant() + ", professeur de " + v.matiere() });
        rubriques.add(new String[] { "Classe", v.classeCode() });
        rubriques.add(new String[] { "Objet", v.objet() });
        if (explication) {
            rubriques.add(new String[] { "Délai de réponse", v.delaiReponse().format(JOUR_LONG) });
        }
        StringBuilder corps = new StringBuilder(v.constat());
        if (v.demande() != null) {
            corps.append("\n\n").append(v.demande());
        }
        if (v.reponse() != null) {
            corps.append("\n\nRéponse de l'enseignant (").append(JOUR.format(v.reponduLe().atZone(FUSEAU)))
                    .append(") :\n").append(v.reponse());
        }
        if (v.suite() != null) {
            corps.append("\n\nSuite donnée (").append(JOUR.format(v.closeLe().atZone(FUSEAU))).append(") :\n")
                    .append(v.suite());
        }
        byte[] signature = signatures.signature(emetteur).orElse(null);
        return rendu.rendre(new RenduDocument.Contenu(v.numero(), explication ? "Demande d'explication" : "Rappel",
                rubriques, corps.toString(), v.emiseLe().atZone(FUSEAU).toLocalDate(), v.qualiteEmetteur(),
                v.emetteur(), signature, false, (explication ? "Demande d'explication n° " + v.numero() : "Rappel")
                        + " émise le " + JOUR.format(v.emiseLe().atZone(FUSEAU)) + " — " + v.matiere() + ", "
                        + v.classeCode()));
    }

    // ================================================================== outils

    private record Enseignant(UUID utilisateurId, String nom) {
    }

    private record Etat(FicheProgression fiche, List<SequenceProgression> sequences, List<SeanceCahier> faites,
            Retard retard) {
    }

    private Contexte exigerSuperviseur(UUID classeId, UUID matiereId) {
        Contexte c = acces.contexte(classeId, matiereId);
        if (!c.superviseur()) {
            throw new AccesRefuseException(c.domaine() == Domaine.GENERAL
                    ? "Les matières générales relèvent du censeur"
                    : "Les matières techniques et pratiques relèvent du chef des travaux");
        }
        return c;
    }

    private void exigerDomaine(Domaine d) {
        if (!supervision.domaines().contains(d)) {
            throw new AccesRefuseException("Cette interpellation ne relève pas de votre domaine");
        }
    }

    private Enseignant enseignant(Contexte c) {
        if (c.matiere().engagementId() == null) {
            throw new RegleMetierException("MATIERE_SANS_ENSEIGNANT",
                    c.matiere().matiereLibelle() + " en " + c.classe().code() + " n'a pas d'enseignant");
        }
        return jdbc.query("""
                select s.utilisateur_id, s.nom || ' ' || s.prenoms from engagement_enseignant g
                join enseignant s on s.id = g.enseignant_id where g.id = ?""",
                (l, n) -> new Enseignant(l.getObject(1, UUID.class), l.getString(2)), c.matiere().engagementId())
                .stream().findFirst().orElseThrow(() -> new RessourceIntrouvableException("Enseignant introuvable"));
    }

    private Etat etat(Contexte c, LocalDate jour) {
        FicheProgression f = fiches.findByClasseIdAndMatiereId(c.classe().id(), c.matiere().matiereId()).orElse(null);
        List<SequenceProgression> seq = f == null ? List.of() : sequences.findByFicheIdOrderByOrdre(f.getId());
        List<SeanceCahier> faites = seances.findByClasseIdAndMatiereIdOrderByDateDescHeureDebutDesc(c.classe().id(),
                c.matiere().matiereId());
        return new Etat(f, seq, faites, Retards.calculer(seq, faites, c.matiere().volumeHebdo(), jour));
    }

    /** Constat chiffré proposé. */
    private static String constat(Contexte c, Etat e, LocalDate jour) {
        String m = c.matiere().matiereLibelle();
        String cl = c.classe().code();
        StringBuilder s = new StringBuilder("Au " + jour.format(JOUR) + ", d'après votre fiche de progression et le "
                + "cahier de textes de " + m + " en " + cl + " :");
        if (e.fiche == null || e.sequences.isEmpty()) {
            s.append("\n- aucune fiche de progression n'a été déposée pour cette matière ;");
        } else if (e.fiche.getStatut() == StatutFiche.BROUILLON || e.fiche.getStatut() == StatutFiche.A_REVOIR) {
            s.append("\n- la fiche de progression n'a pas encore été soumise au visa ;");
        }
        Retard r = e.retard;
        if (r.heuresPrevuesADate().signum() > 0) {
            s.append("\n- heures prévues à cette date : ").append(heures(r.heuresPrevuesADate()))
                    .append(" ; heures inscrites au cahier de textes : ").append(heures(r.heuresRealisees()));
            if (r.ecart().signum() > 0) {
                s.append(", soit un retard de ").append(heures(r.ecart()));
            }
            s.append(" ;");
        } else {
            s.append("\n- heures inscrites au cahier de textes : ").append(heures(r.heuresRealisees())).append(" ;");
        }
        List<SequenceProgression> echues = Retards.echuesSansSeance(e.sequences, e.faites, c.matiere().volumeHebdo(), jour);
        if (!echues.isEmpty()) {
            s.append("\n- ").append(echues.size()).append(" séquence(s) échue(s) sans aucune séance : ")
                    .append(String.join(", ", echues.stream().map(q -> "« " + q.getTitre() + " »").toList())).append(" ;");
        }
        if (r.joursSansSeance() == null) {
            s.append("\n- aucune séance n'est inscrite au cahier de textes.");
        } else {
            s.append("\n- dernière séance inscrite : il y a ").append(r.joursSansSeance()).append(" jour(s).");
        }
        return s.toString();
    }

    private static String heures(BigDecimal h) {
        return h.stripTrailingZeros().toPlainString().replace('.', ',') + " h";
    }

    /** Qualité de l'émetteur, imprimée sur la lettre. */
    private static String qualite(Domaine domaine) {
        if (domaine == Domaine.GENERAL && UtilisateurConnecte.aUnRole("CENSEUR")) {
            return "Le Censeur";
        }
        if (domaine == Domaine.TECHNIQUE && UtilisateurConnecte.aUnRole("CHEF_TRAVAUX")) {
            return "Le Chef des travaux";
        }
        if (UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            return "Le Chef d'établissement";
        }
        return "Le Censeur";
    }

    private List<UUID> chefs() {
        return membres.lister().stream().filter(m -> m.actif() && m.role() == Role.ADMIN_ECOLE)
                .map(m -> m.utilisateurId()).distinct().toList();
    }

    private void transmettre(UUID destinataire, String objet, String corps) {
        try {
            messagerie.transmettre(destinataire, objet.length() > 200 ? objet.substring(0, 200) : objet, corps,
                    "Progression pédagogique");
        } catch (IllegalArgumentException e) {
            // Destinataire qui n'est plus membre du personnel : l'interpellation reste consultable
        }
    }

    private boolean destinataire(UUID id) {
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        return moi != null && moi.equals(
                jdbc.queryForObject("select destinataire_id from interpellation where id = ?", UUID.class, id));
    }

    private InterpellationVue exigerVisible(UUID id) {
        InterpellationVue v = detail(id);
        if (!destinataire(id) && !supervision.domaines().contains(v.domaine())) {
            throw new RessourceIntrouvableException("Interpellation introuvable");
        }
        return v;
    }

    private InterpellationVue detail(UUID id) {
        Set<Domaine> domaines = supervision.domaines();
        return jdbc.query(SELECT + " where i.id = ?", (l, n) -> vue(l, domaines), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Interpellation introuvable"));
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge.withZone(FUSEAU));
    }

    private static String texte(String s, int max, String quoi) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(quoi + " obligatoire");
        }
        if (s.strip().length() > max) {
            throw new IllegalArgumentException(quoi + " de " + max + " caractères au plus");
        }
        return s.strip();
    }

    private static final String SELECT = """
            select i.id, i.numero, i.type, i.classe_id, c.code, i.matiere_id, m.libelle, i.domaine,
                   s.nom || ' ' || s.prenoms, i.objet, i.constat, i.demande, i.heures_prevues, i.heures_realisees,
                   i.delai_reponse, ue.nom || ' ' || ue.prenoms, i.qualite_emetteur, i.emise_le, i.statut, i.lue_le,
                   i.reponse, i.repondue_le, i.suite, i.close_le, uc.nom || ' ' || uc.prenoms, i.destinataire_id
            from interpellation i
            join classe c on c.tenant_id = i.tenant_id and c.id = i.classe_id
            join matiere m on m.tenant_id = i.tenant_id and m.id = i.matiere_id
            join engagement_enseignant g on g.tenant_id = i.tenant_id and g.id = i.engagement_id
            join enseignant s on s.id = g.enseignant_id
            join utilisateur ue on ue.id = i.emise_par
            left join utilisateur uc on uc.id = i.close_par""";

    private InterpellationVue vue(ResultSet l, Set<Domaine> domaines) throws SQLException {
        Domaine domaine = Domaine.valueOf(l.getString(8));
        StatutInterpellation statut = StatutInterpellation.valueOf(l.getString(19));
        LocalDate delai = l.getObject(15, LocalDate.class);
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        boolean destinataire = moi != null && moi.equals(l.getObject(26, UUID.class));
        boolean ouverte = statut != StatutInterpellation.CLOSE;
        return new InterpellationVue(l.getObject(1, UUID.class), l.getString(2), TypeInterpellation.valueOf(l.getString(3)),
                l.getObject(4, UUID.class), l.getString(5), l.getObject(6, UUID.class), l.getString(7), domaine,
                l.getString(9), l.getString(10), l.getString(11), l.getString(12), l.getBigDecimal(13),
                l.getBigDecimal(14), delai, l.getString(16), l.getString(17), instant(l.getTimestamp(18)), statut,
                instant(l.getTimestamp(20)), l.getString(21), instant(l.getTimestamp(22)), l.getString(23),
                instant(l.getTimestamp(24)), l.getString(25),
                delai != null && ouverte && statut != StatutInterpellation.REPONDUE && delai.isBefore(aujourdhui()),
                destinataire && ouverte && statut != StatutInterpellation.REPONDUE,
                ouverte && domaines.contains(domaine));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
