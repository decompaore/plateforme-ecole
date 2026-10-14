package bf.edutech.plateforme.secretariat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.StatutInscription;
import bf.edutech.plateforme.eleves.Vues.EleveVue;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Certificats de scolarité (numérotés CS-2026-0001, inscrits au registre des certificats délivrés) et appui aux
 * dossiers des examens officiels : liste des candidats d'une ou plusieurs classes avec ce qui manque à leur
 * dossier (lieu de naissance, photo d'identité, identifiant national).
 */
@Service
public class CertificatsService {

    public record CertificatVue(UUID id, String numero, UUID inscriptionId, String eleve, String classe, String annee,
            String delivrePar, Instant delivreLe) {
    }

    public record CandidatVue(UUID inscriptionId, UUID eleveId, String classe, String matricule, String nom,
            String prenoms, String sexe, LocalDate dateNaissance, String lieuNaissance, String identifiantNational,
            boolean photo, List<String> manquants) {
    }

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final JdbcTemplate jdbc;
    private final InscriptionsService inscriptions;
    private final ElevesService eleves;
    private final MembresService membres;
    private final Compteurs compteurs;
    private final RenduDocument rendu;
    private final AuditService audit;
    private final Clock horloge;

    CertificatsService(JdbcTemplate jdbc, InscriptionsService inscriptions, ElevesService eleves, MembresService membres,
            Compteurs compteurs, RenduDocument rendu, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.membres = membres;
        this.compteurs = compteurs;
        this.rendu = rendu;
        this.audit = audit;
        this.horloge = horloge;
    }

    /** Délivre un certificat de scolarité (PDF) et l'inscrit au registre. */
    @Transactional
    public byte[] certificat(UUID inscriptionId) {
        Secretariat.exigerGestionnaire();
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        EleveVue e = eleves.trouver(i.eleveId()).eleve();
        LocalDate aujourdhui = LocalDate.now(horloge.withZone(FUSEAU));
        String numero = "CS-" + aujourdhui.getYear() + "-"
                + String.format("%04d", compteurs.suivant("certificat:" + aujourdhui.getYear()));
        String nomComplet = e.nom() + " " + e.prenoms();
        jdbc.update("""
                insert into certificat_delivre (tenant_id, id, numero, inscription_id, eleve, classe, annee, delivre_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), numero, inscriptionId, nomComplet, i.classeCode(), i.anneeLibelle(),
                UtilisateurConnecte.idSiConnecte().orElse(null));
        audit.enregistrer("CERTIFICAT_SCOLARITE", numero, Map.of("eleve", e.matricule() == null ? nomComplet : e.matricule()));
        boolean fille = e.sexe() == Sexe.F;
        String ne = fille ? "née" : "né";
        String inscrit = fille ? "inscrite" : "inscrit";
        StringBuilder corps = new StringBuilder();
        corps.append("Je soussigné(e), chef d'établissement, certifie que l'élève ").append(nomComplet)
                .append(", ").append(ne).append(" le ").append(e.dateNaissance().format(JOUR))
                .append(e.lieuNaissance() != null ? " à " + e.lieuNaissance() : "")
                .append(e.matricule() != null ? ", matricule " + e.matricule() : "").append(", ");
        if (i.statut() == StatutInscription.ACTIVE) {
            corps.append("est régulièrement ").append(inscrit).append(" en classe de ").append(i.classeCode())
                    .append(" pour l'année scolaire ").append(i.anneeLibelle()).append(".");
        } else {
            corps.append("a été ").append(inscrit).append(" en classe de ").append(i.classeCode())
                    .append(" pour l'année scolaire ").append(i.anneeLibelle())
                    .append(i.dateSortie() != null ? " jusqu'au " + i.dateSortie().format(JOUR) : "").append(".");
        }
        corps.append("\n\nEn foi de quoi, le présent certificat lui est délivré pour servir et valoir ce que de droit.");
        String chef = membres.lister().stream().filter(m -> m.actif() && m.role() == Role.ADMIN_ECOLE).findFirst()
                .map(m -> m.prenoms() + " " + m.nom()).orElse(null);
        return rendu.rendre(new RenduDocument.Contenu(numero, "Certificat de scolarité", List.of(), corps.toString(),
                aujourdhui, "Le Chef d'établissement", chef, null, false, null));
    }

    /** Registre des certificats délivrés pendant une année civile. */
    @Transactional(readOnly = true)
    public List<CertificatVue> registre(int annee) {
        Secretariat.exigerGestionnaire();
        return jdbc.query("""
                select c.id, c.numero, c.inscription_id, c.eleve, c.classe, c.annee, u.nom || ' ' || u.prenoms, c.delivre_le
                  from certificat_delivre c left join utilisateur u on u.id = c.delivre_par
                 where extract(year from c.delivre_le at time zone 'Africa/Ouagadougou') = ?
                 order by c.numero desc""",
                (r, n) -> new CertificatVue(r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class),
                        r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                        r.getObject(8, OffsetDateTime.class).toInstant()), annee);
    }

    /** Candidats aux examens : élèves inscrits des classes choisies et ce qui manque à leur dossier. */
    @Transactional(readOnly = true)
    public List<CandidatVue> candidats(List<UUID> classes) {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "SECRETARIAT", "CENSEUR")) {
            throw new bf.edutech.plateforme.socle.erreurs.AccesRefuseException(
                    "Dossiers d'examen : secrétariat et direction");
        }
        if (classes == null || classes.isEmpty()) {
            throw new IllegalArgumentException("Choisissez au moins une classe");
        }
        List<CandidatVue> r = new ArrayList<>();
        String tableau = String.join(",", classes.stream().map(UUID::toString).toList());
        jdbc.query("""
                select i.id, e.id, c.code, e.matricule, e.nom, e.prenoms, e.sexe, e.date_naissance, e.lieu_naissance,
                       e.identifiant_national, p.eleve_id is not null
                  from inscription i
                  join eleve e on e.tenant_id = i.tenant_id and e.id = i.eleve_id
                  join classe c on c.tenant_id = i.tenant_id and c.id = i.classe_id
                  left join photo_eleve p on p.tenant_id = e.tenant_id and p.eleve_id = e.id
                 where i.statut = 'ACTIVE' and i.classe_id = any (?::uuid[])
                 order by c.code, e.nom, e.prenoms""", (java.sql.ResultSet x) -> {
            List<String> manquants = new ArrayList<>();
            if (x.getString(9) == null || x.getString(9).isBlank()) {
                manquants.add("lieu de naissance");
            }
            if (!x.getBoolean(11)) {
                manquants.add("photo d'identité");
            }
            if (x.getString(10) == null || x.getString(10).isBlank()) {
                manquants.add("identifiant national");
            }
            r.add(new CandidatVue(x.getObject(1, UUID.class), x.getObject(2, UUID.class), x.getString(3), x.getString(4),
                    x.getString(5), x.getString(6), x.getString(7), x.getDate(8).toLocalDate(), x.getString(9),
                    x.getString(10), x.getBoolean(11), manquants));
        }, "{" + tableau + "}");
        return r;
    }

    Tableau tableauCandidats(List<UUID> classes, String examen) {
        List<CandidatVue> liste = candidats(classes);
        Tableau t = new Tableau("Candidats", "Candidats" + (examen == null || examen.isBlank() ? "" : " au " + examen.strip()))
                .sousTitre(liste.size() + " candidat(s) · " + liste.stream().filter(c -> !c.manquants().isEmpty()).count()
                        + " dossier(s) incomplet(s)")
                .colonnes(Colonne.texte("Classe", 0.8f), Colonne.texte("Matricule", 1f), Colonne.texte("Nom", 1.2f),
                        Colonne.texte("Prénoms", 1.4f), Colonne.texte("Sexe", 0.5f), Colonne.texte("Né(e) le", 0.9f),
                        Colonne.texte("À", 1.2f), Colonne.texte("Identifiant national", 1.1f),
                        Colonne.texte("Manque", 1.6f));
        for (CandidatVue c : liste) {
            t.ligne(c.classe(), c.matricule() == null ? "" : c.matricule(), c.nom(), c.prenoms(), c.sexe(),
                    c.dateNaissance().format(JOUR), c.lieuNaissance() == null ? "" : c.lieuNaissance(),
                    c.identifiantNational() == null ? "" : c.identifiantNational(), String.join(", ", c.manquants()));
        }
        return t;
    }
}
