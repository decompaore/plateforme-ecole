package bf.edutech.plateforme.utilisateurs;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.images.Images;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Photos d'identité des membres de l'établissement actif : enseignants et personnel (v0.44). Chaque
 * établissement garde la sienne (Row-Level Security). L'administrateur la dépose pour tous ; chacun peut
 * déposer la sienne. Lecture : personnel administratif, ou la personne elle-même.
 */
@Service
public class PhotosPersonnes {

    /** Photo : 480 × 600 pixels au plus ; miniature des listes : 96 × 120. */
    static final int LARGEUR = 480;
    static final int HAUTEUR = 600;
    static final int LARGEUR_MINIATURE = 96;
    static final int HAUTEUR_MINIATURE = 120;

    /** Rôles qui voient les photos du personnel (fiches, listes, cartes). */
    static final String[] LECTEURS = { "ADMIN_ECOLE", "CENSEUR", "CHEF_TRAVAUX", "SECRETARIAT", "INTENDANT", "SURVEILLANT" };

    public record Photo(String type, byte[] contenu) {
    }

    private final JdbcTemplate jdbc;
    private final MembreEtablissementRepository membres;
    private final AuditService audit;

    PhotosPersonnes(JdbcTemplate jdbc, MembreEtablissementRepository membres, AuditService audit) {
        this.jdbc = jdbc;
        this.membres = membres;
        this.audit = audit;
    }

    /** Photo (ou sa miniature) d'un membre, pour l'affichage ; contrôle des droits de lecture. */
    @Transactional(readOnly = true)
    public Photo lire(UUID utilisateurId, boolean miniature) {
        exigerLecture(utilisateurId);
        return photo(utilisateurId, miniature)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune photo"));
    }

    /** Photo d'un membre pour un document (carte, liste) : sans contrôle, à l'appelant de vérifier ses droits. */
    @Transactional(readOnly = true)
    public Optional<Photo> photo(UUID utilisateurId, boolean miniature) {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("select type_contenu, " + (miniature ? "miniature" : "contenu")
                        + " from photo_personne where utilisateur_id = ?",
                (l, n) -> new Photo(miniature ? "image/jpeg" : l.getString(1), l.getBytes(2)), utilisateurId)
                .stream().findFirst();
    }

    /** Comptes de l'établissement qui ont une photo (pour n'afficher que celles qui existent). */
    @Transactional(readOnly = true)
    public Set<UUID> avecPhoto() {
        UtilisateurConnecte.etablissementActif();
        if (!UtilisateurConnecte.aUnRole(LECTEURS)) {
            throw new AccesRefuseException("Réservé au personnel administratif");
        }
        return new HashSet<>(jdbc.queryForList("select utilisateur_id from photo_personne", UUID.class));
    }

    @Transactional
    public void enregistrer(UUID utilisateurId, byte[] contenu) {
        exigerEcriture(utilisateurId);
        Images.Image photo = Images.photo(contenu, LARGEUR, HAUTEUR);
        Images.Image miniature = Images.photo(photo.contenu(), LARGEUR_MINIATURE, HAUTEUR_MINIATURE);
        jdbc.update("""
                insert into photo_personne (tenant_id, utilisateur_id, type_contenu, contenu, miniature, modifie_le, modifie_par)
                values (tenant_courant(), ?, ?, ?, ?, now(), ?)
                on conflict (tenant_id, utilisateur_id) do update set type_contenu = excluded.type_contenu,
                    contenu = excluded.contenu, miniature = excluded.miniature, modifie_le = excluded.modifie_le,
                    modifie_par = excluded.modifie_par""",
                utilisateurId, photo.type(), photo.contenu(), miniature.contenu(), UtilisateurConnecte.id());
        audit.enregistrer("PHOTO_PERSONNE", utilisateurId.toString(), Map.of("taille", photo.contenu().length));
    }

    @Transactional
    public void supprimer(UUID utilisateurId) {
        exigerEcriture(utilisateurId);
        if (jdbc.update("delete from photo_personne where utilisateur_id = ?", utilisateurId) > 0) {
            audit.enregistrer("PHOTO_PERSONNE_SUPPRIMEE", utilisateurId.toString(), null);
        }
    }

    /** Photos de plusieurs membres (cartes professionnelles). */
    @Transactional(readOnly = true)
    public Map<UUID, byte[]> photos(List<UUID> utilisateurs) {
        UtilisateurConnecte.etablissementActif();
        Map<UUID, byte[]> resultat = new java.util.HashMap<>();
        for (UUID id : new HashSet<>(utilisateurs)) {
            photo(id, false).ifPresent(p -> resultat.put(id, p.contenu()));
        }
        return resultat;
    }

    private void exigerLecture(UUID utilisateurId) {
        UtilisateurConnecte.etablissementActif();
        boolean soi = utilisateurId.equals(UtilisateurConnecte.idSiConnecte().orElse(null));
        if (!soi && !UtilisateurConnecte.aUnRole(LECTEURS)) {
            throw new AccesRefuseException("Réservé au personnel administratif");
        }
        exigerMembre(utilisateurId);
    }

    private void exigerEcriture(UUID utilisateurId) {
        UtilisateurConnecte.etablissementActif();
        boolean soi = utilisateurId.equals(UtilisateurConnecte.idSiConnecte().orElse(null));
        if (!soi && !UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Seul l'administrateur change la photo d'un membre (ou la personne elle-même)");
        }
        exigerMembre(utilisateurId);
    }

    private void exigerMembre(UUID utilisateurId) {
        if (!membres.existsByUtilisateurId(utilisateurId)) {
            throw new RessourceIntrouvableException("Ce compte n'est pas membre de l'établissement");
        }
    }
}
