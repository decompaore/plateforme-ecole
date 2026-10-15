package bf.edutech.plateforme.territoire;

import java.time.Instant;
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
import bf.edutech.plateforme.socle.securite.Portee;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Armoiries des pays (v0.48.1), affichées en filigrane sur les écrans de toutes les personnes du pays (établissements,
 * directions, administrateur pays). L'image officielle est téléversée par l'administrateur du pays ou le super
 * administrateur ; elle est réduite et enregistrée en PNG (transparence conservée).
 */
@Service
public class ArmoiriesService {

    /** Côté le plus long de l'image enregistrée. */
    static final int COTE_MAX = 800;

    public record Image(String type, byte[] contenu, Instant modifieLe) {
    }

    public record ArmoiriesVue(UUID paysId, boolean presentes, Instant modifieLe, Integer taille) {
    }

    private final JdbcTemplate jdbc;
    private final PorteeTerritoire portee;
    private final AuditService audit;

    ArmoiriesService(JdbcTemplate jdbc, PorteeTerritoire portee, AuditService audit) {
        this.jdbc = jdbc;
        this.portee = portee;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    public ArmoiriesVue etat(UUID paysId) {
        portee.verifierPays(paysId);
        exigerPays(paysId);
        return jdbc.query("select modifie_le, octet_length(contenu) from armoiries_pays where pays_id = ?",
                (l, n) -> new ArmoiriesVue(paysId, true, l.getTimestamp(1).toInstant(), l.getInt(2)), paysId).stream()
                .findFirst().orElse(new ArmoiriesVue(paysId, false, null, null));
    }

    @Transactional(readOnly = true)
    public Image armoiriesDuPays(UUID paysId) {
        portee.verifierPays(paysId);
        return lire(paysId).orElseThrow(() -> new RessourceIntrouvableException("Aucune armoirie pour ce pays"));
    }

    @Transactional
    public ArmoiriesVue enregistrer(UUID paysId, byte[] contenu) {
        portee.verifierPays(paysId);
        exigerPays(paysId);
        Images.Image image = Images.signature(contenu, COTE_MAX, COTE_MAX);
        jdbc.update("""
                insert into armoiries_pays (pays_id, type_contenu, contenu, modifie_par) values (?, ?, ?, ?)
                on conflict (pays_id) do update set type_contenu = excluded.type_contenu, contenu = excluded.contenu,
                    modifie_le = now(), modifie_par = excluded.modifie_par""",
                paysId, image.type(), image.contenu(), UtilisateurConnecte.id());
        audit.enregistrer("ARMOIRIES_MODIFIEES", paysId.toString(), Map.of("taille", image.contenu().length));
        return etat(paysId);
    }

    @Transactional
    public ArmoiriesVue supprimer(UUID paysId) {
        portee.verifierPays(paysId);
        jdbc.update("delete from armoiries_pays where pays_id = ?", paysId);
        audit.enregistrer("ARMOIRIES_SUPPRIMEES", paysId.toString(), null);
        return etat(paysId);
    }

    // ------------------------------------------------------------------ filigrane

    /**
     * Armoiries du pays de la personne connectée : celui de son établissement (d'après son rattachement), de sa
     * direction, ou du pays qu'elle administre. Aucune pour le super administrateur hors établissement.
     */
    @Transactional(readOnly = true)
    public Image armoiriesCourantes() {
        return paysCourant().flatMap(this::lire)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucune armoirie pour votre pays"));
    }

    private Optional<UUID> paysCourant() {
        Optional<UUID> tenant = TenantContext.courant();
        if (tenant.isPresent()) {
            return Optional.ofNullable(portee.paysEtablissement(tenant.get()));
        }
        Optional<UUID> direction = Portee.direction();
        if (direction.isPresent()) {
            return jdbc.queryForList("""
                    select m.pays_id from direction d join ministere m on m.id = d.ministere_id where d.id = ?""",
                    UUID.class, direction.get()).stream().findFirst();
        }
        if (UtilisateurConnecte.aUnRole(Portee.ADMIN_PAYS) && !Portee.superAdmin()) {
            return Portee.pays();
        }
        return Optional.empty();
    }

    private Optional<Image> lire(UUID paysId) {
        return jdbc.query("select type_contenu, contenu, modifie_le from armoiries_pays where pays_id = ?",
                (l, n) -> new Image(l.getString(1), l.getBytes(2), l.getTimestamp(3).toInstant()), paysId).stream()
                .findFirst();
    }

    private void exigerPays(UUID paysId) {
        List<UUID> p = jdbc.queryForList("select id from pays where id = ?", UUID.class, paysId);
        if (p.isEmpty()) {
            throw new RessourceIntrouvableException("Pays introuvable");
        }
    }
}
