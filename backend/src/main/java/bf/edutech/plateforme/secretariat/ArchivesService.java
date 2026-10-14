package bf.edutech.plateforme.secretariat;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.secretariat.Secretariat.Fichier;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;

/**
 * Archives numériques et classement : pièces scannées du dossier d'un élève (acte de naissance, bulletins
 * antérieurs, décision d'orientation…), d'un membre du personnel (arrêté d'affectation, procès-verbal de prise de
 * service, diplômes…) ou de l'établissement (arrêtés, conventions). Chaque pièce porte une catégorie et une cote.
 */
@Service
public class ArchivesService {

    public enum Dossier { ELEVE, PERSONNEL, ETABLISSEMENT }

    public record Description(String dossier, UUID eleveId, UUID utilisateurId, String categorie, String titre,
            String cote, LocalDate dateDocument) {
    }

    public record PieceVue(UUID id, Dossier dossier, UUID eleveId, UUID utilisateurId, String categorie,
            String titre, String cote, LocalDate dateDocument, String fichierNom, String fichierType, int taille,
            String ajoutePar, Instant ajouteLe) {
    }

    private final JdbcTemplate jdbc;
    private final ElevesService eleves;
    private final MembresService membres;
    private final AuditService audit;

    ArchivesService(JdbcTemplate jdbc, ElevesService eleves, MembresService membres, AuditService audit) {
        this.jdbc = jdbc;
        this.eleves = eleves;
        this.membres = membres;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PieceVue> lister(String dossier, UUID eleveId, UUID utilisateurId) {
        Secretariat.exigerGestionnaire();
        Dossier d = Secretariat.valeur(Dossier.class, dossier, "Dossier");
        return switch (d) {
            case ELEVE -> jdbc.query(SELECT + " where a.dossier = 'ELEVE' and a.eleve_id = ? order by a.categorie, a.date_document",
                    VUE, exiger(eleveId, "l'élève"));
            case PERSONNEL -> jdbc.query(SELECT + " where a.dossier = 'PERSONNEL' and a.utilisateur_id = ? "
                    + "order by a.categorie, a.date_document", VUE, exiger(utilisateurId, "la personne"));
            case ETABLISSEMENT -> jdbc.query(SELECT + " where a.dossier = 'ETABLISSEMENT' order by a.categorie, a.cote, "
                    + "a.date_document", VUE);
        };
    }

    @Transactional
    public PieceVue ajouter(Description d, byte[] contenu, String nom) {
        Secretariat.exigerGestionnaire();
        if (d == null) {
            throw new IllegalArgumentException("Décrivez la pièce (dossier, catégorie, titre)");
        }
        Dossier dossier = Secretariat.valeur(Dossier.class, d.dossier(), "Dossier");
        UUID eleveId = null;
        UUID utilisateurId = null;
        if (dossier == Dossier.ELEVE) {
            eleveId = exiger(d.eleveId(), "l'élève");
            eleves.trouver(eleveId);                                   // introuvable dans l'établissement : 404
        } else if (dossier == Dossier.PERSONNEL) {
            utilisateurId = exiger(d.utilisateurId(), "la personne");
            UUID u = utilisateurId;
            if (membres.lister().stream().noneMatch(m -> m.utilisateurId().equals(u))) {
                throw new IllegalArgumentException("Cette personne n'est pas membre de l'établissement");
            }
        }
        Fichier f = Secretariat.fichier(contenu, nom);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into archive_document (tenant_id, id, dossier, eleve_id, utilisateur_id, categorie, titre, cote,
                                              date_document, fichier, fichier_type, fichier_nom, taille, ajoute_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, dossier.name(), eleveId, utilisateurId, Secretariat.obligatoire(d.categorie(), "La catégorie", 60),
                Secretariat.obligatoire(d.titre(), "Le titre", 200), Secretariat.facultatif(d.cote(), "La cote", 40),
                d.dateDocument() == null ? null : Date.valueOf(d.dateDocument()), f.contenu(), f.type(), f.nom(),
                f.contenu().length, UtilisateurConnecte.idSiConnecte().orElse(null));
        audit.enregistrer("PIECE_ARCHIVEE", d.titre(), Map.of("dossier", dossier.name()));
        return charger(id);
    }

    @Transactional(readOnly = true)
    public Fichier fichier(UUID id) {
        Secretariat.exigerGestionnaire();
        return jdbc.query("select fichier, fichier_type, fichier_nom from archive_document where id = ?",
                (r, n) -> new Fichier(r.getBytes(1), r.getString(2), r.getString(3)), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Pièce introuvable"));
    }

    @Transactional
    public void supprimer(UUID id) {
        Secretariat.exigerGestionnaire();
        PieceVue p = charger(id);
        jdbc.update("delete from archive_document where id = ?", id);
        audit.enregistrer("PIECE_SUPPRIMEE", p.titre(), Map.of("dossier", p.dossier().name()));
    }

    private static UUID exiger(UUID id, String quoi) {
        if (id == null) {
            throw new IllegalArgumentException("Indiquez " + quoi);
        }
        return id;
    }

    private PieceVue charger(UUID id) {
        return jdbc.query(SELECT + " where a.id = ?", VUE, id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Pièce introuvable"));
    }

    private static final String SELECT = """
            select a.id, a.dossier, a.eleve_id, a.utilisateur_id, a.categorie, a.titre, a.cote, a.date_document,
                   a.fichier_nom, a.fichier_type, a.taille, u.nom || ' ' || u.prenoms, a.ajoute_le
              from archive_document a left join utilisateur u on u.id = a.ajoute_par""";

    private static final RowMapper<PieceVue> VUE = (r, n) -> new PieceVue(r.getObject(1, UUID.class),
            Dossier.valueOf(r.getString(2)), r.getObject(3, UUID.class), r.getObject(4, UUID.class), r.getString(5),
            r.getString(6), r.getString(7), r.getDate(8) == null ? null : r.getDate(8).toLocalDate(), r.getString(9),
            r.getString(10), r.getInt(11), r.getString(12), r.getObject(13, OffsetDateTime.class).toInstant());
}
