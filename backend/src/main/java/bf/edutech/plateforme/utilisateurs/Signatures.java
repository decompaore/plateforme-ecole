package bf.edutech.plateforme.utilisateurs;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.images.Images;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Signature manuscrite numérisée d'un compte (v0.44) : dessinée au doigt sur le téléphone ou photographiée
 * sur papier blanc. Elle appartient à la personne (valable dans tous ses établissements) : seul son titulaire la
 * dépose, la consulte ou la retire. Les bulletins l'impriment devant la matière que l'enseignant a visée.
 */
@Service
public class Signatures {

    /** Image gardée : 600 × 200 pixels au plus, PNG (fond transparent conservé). */
    static final int LARGEUR = 600;
    static final int HAUTEUR = 200;

    public record EtatSignature(boolean presente, Instant modifieeLe) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    Signatures(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public EtatSignature etat() {
        return jdbc.query("select modifie_le from signature_utilisateur where utilisateur_id = ?",
                (l, n) -> new EtatSignature(true, l.getTimestamp(1).toInstant()), UtilisateurConnecte.id())
                .stream().findFirst().orElse(new EtatSignature(false, null));
    }

    /** La signature du compte connecté (pour la revoir avant de viser). */
    @Transactional(readOnly = true)
    public byte[] mienne() {
        return signature(UtilisateurConnecte.id())
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune signature enregistrée"));
    }

    @Transactional
    public EtatSignature enregistrer(byte[] contenu) {
        UUID moi = UtilisateurConnecte.id();
        Images.Image image = Images.signature(contenu, LARGEUR, HAUTEUR);
        jdbc.update("""
                insert into signature_utilisateur (utilisateur_id, type_contenu, contenu, modifie_le)
                values (?, ?, ?, now())
                on conflict (utilisateur_id) do update set type_contenu = excluded.type_contenu,
                    contenu = excluded.contenu, modifie_le = excluded.modifie_le""",
                moi, image.type(), image.contenu());
        if (UtilisateurConnecte.idSiConnecte().isPresent() && estDansUnEtablissement()) {
            audit.enregistrer("SIGNATURE_DEPOSEE", moi.toString(), Map.of("taille", image.contenu().length));
        }
        return etat();
    }

    @Transactional
    public EtatSignature supprimer() {
        UUID moi = UtilisateurConnecte.id();
        if (jdbc.update("delete from signature_utilisateur where utilisateur_id = ?", moi) > 0 && estDansUnEtablissement()) {
            audit.enregistrer("SIGNATURE_RETIREE", moi.toString(), null);
        }
        return etat();
    }

    /** Signature d'un compte, pour l'impression d'un document (bulletin) : à l'appelant de vérifier le contexte. */
    @Transactional(readOnly = true)
    public Optional<byte[]> signature(UUID utilisateurId) {
        return jdbc.query("select contenu from signature_utilisateur where utilisateur_id = ?",
                (l, n) -> l.getBytes(1), utilisateurId).stream().findFirst();
    }

    /** Signatures de plusieurs comptes (génération des bulletins d'une classe). */
    @Transactional(readOnly = true)
    public Map<UUID, byte[]> signatures(List<UUID> utilisateurs) {
        Map<UUID, byte[]> resultat = new HashMap<>();
        for (UUID id : new HashSet<>(utilisateurs)) {
            signature(id).ifPresent(s -> resultat.put(id, s));
        }
        return resultat;
    }

    private static boolean estDansUnEtablissement() {
        try {
            UtilisateurConnecte.etablissementActif();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
