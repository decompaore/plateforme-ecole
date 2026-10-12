package bf.edutech.plateforme.eleves;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.documents.CartesPdf;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.images.Images;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Photos d'identité des élèves (v0.44) : déposées à l'inscription par le secrétariat ou l'administration,
 * vues par le personnel et par les enseignants de la classe (listes, feuille d'appel), imprimées sur le
 * bulletin et la carte scolaire.
 */
@Service
public class PhotosEleves {

    static final int LARGEUR = 480;
    static final int HAUTEUR = 600;
    static final int LARGEUR_MINIATURE = 96;
    static final int HAUTEUR_MINIATURE = 120;
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Photo(String type, byte[] contenu) {
    }

    private final JdbcTemplate jdbc;
    private final EleveRepository eleves;
    private final InscriptionRepository inscriptions;
    private final InscriptionsService inscriptionsService;
    private final EnseignantsService enseignants;
    private final EntetesOfficiels entetes;
    private final AuditService audit;

    PhotosEleves(JdbcTemplate jdbc, EleveRepository eleves, InscriptionRepository inscriptions,
            InscriptionsService inscriptionsService, EnseignantsService enseignants, EntetesOfficiels entetes,
            AuditService audit) {
        this.jdbc = jdbc;
        this.eleves = eleves;
        this.inscriptions = inscriptions;
        this.inscriptionsService = inscriptionsService;
        this.enseignants = enseignants;
        this.entetes = entetes;
        this.audit = audit;
    }

    /** Photo ou miniature d'un élève : personnel administratif, ou enseignant de sa classe. */
    @Transactional(readOnly = true)
    public Photo lire(UUID eleveId, boolean miniature) {
        UtilisateurConnecte.etablissementActif();
        Eleve eleve = charger(eleveId);
        if (!UtilisateurConnecte.aUnRole(Roles.PERSONNEL)) {
            UUID moi = UtilisateurConnecte.id();
            boolean enseigne = inscriptions.findByEleveIdOrderByInscritLeDesc(eleve.getId()).stream()
                    .filter(Inscription::estActive)
                    .anyMatch(i -> enseignants.enseigneDans(moi, i.getClasseId()));
            if (!enseigne) {
                throw new AccesRefuseException("Vous n'enseignez pas dans la classe de cet élève");
            }
        }
        return photo(eleveId, miniature).orElseThrow(() -> new RessourceIntrouvableException("Aucune photo"));
    }

    /** Photo pour un document (bulletin) : à l'appelant de vérifier ses droits. */
    @Transactional(readOnly = true)
    public Optional<Photo> photo(UUID eleveId, boolean miniature) {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("select type_contenu, " + (miniature ? "miniature" : "contenu") + " from photo_eleve where eleve_id = ?",
                (l, n) -> new Photo(miniature ? "image/jpeg" : l.getString(1), l.getBytes(2)), eleveId).stream().findFirst();
    }

    /** Élèves (actifs) d'une classe qui ont une photo : personnel administratif, ou enseignant de la classe. */
    @Transactional(readOnly = true)
    public Set<UUID> avecPhoto(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        if (!UtilisateurConnecte.aUnRole(Roles.PERSONNEL) && !enseignants.enseigneDans(UtilisateurConnecte.id(), classeId)) {
            throw new AccesRefuseException("Vous n'enseignez pas dans cette classe");
        }
        Set<UUID> dansLaClasse = new HashSet<>();
        inscriptionsService.listerParClasse(classeId, false).forEach(i -> dansLaClasse.add(i.eleveId()));
        Set<UUID> resultat = new HashSet<>(jdbc.queryForList("select eleve_id from photo_eleve", UUID.class));
        resultat.retainAll(dansLaClasse);
        return resultat;
    }

    /** Parmi ces élèves (une page de la recherche), ceux qui ont une photo : personnel administratif. */
    @Transactional(readOnly = true)
    public Set<UUID> parmi(List<UUID> eleveIds) {
        UtilisateurConnecte.etablissementActif();
        if (eleveIds == null || eleveIds.isEmpty()) {
            return Set.of();
        }
        if (eleveIds.size() > 200) {
            throw new IllegalArgumentException("200 élèves au plus par demande");
        }
        // Identifiants seuls (jamais les images) ; la RLS limite à l'établissement
        Set<UUID> resultat = new HashSet<>(jdbc.queryForList("select eleve_id from photo_eleve", UUID.class));
        resultat.retainAll(new HashSet<>(eleveIds));
        return resultat;
    }

    @Transactional
    public void enregistrer(UUID eleveId, byte[] contenu) {
        UtilisateurConnecte.etablissementActif();
        Eleve eleve = charger(eleveId);
        Images.Image photo = Images.photo(contenu, LARGEUR, HAUTEUR);
        Images.Image miniature = Images.photo(photo.contenu(), LARGEUR_MINIATURE, HAUTEUR_MINIATURE);
        jdbc.update("""
                insert into photo_eleve (tenant_id, eleve_id, type_contenu, contenu, miniature, modifie_le, modifie_par)
                values (tenant_courant(), ?, ?, ?, ?, now(), ?)
                on conflict (tenant_id, eleve_id) do update set type_contenu = excluded.type_contenu,
                    contenu = excluded.contenu, miniature = excluded.miniature, modifie_le = excluded.modifie_le,
                    modifie_par = excluded.modifie_par""",
                eleveId, photo.type(), photo.contenu(), miniature.contenu(), UtilisateurConnecte.id());
        audit.enregistrer("PHOTO_ELEVE", eleve.getMatricule(), Map.of("taille", photo.contenu().length));
    }

    @Transactional
    public void supprimer(UUID eleveId) {
        UtilisateurConnecte.etablissementActif();
        Eleve eleve = charger(eleveId);
        if (jdbc.update("delete from photo_eleve where eleve_id = ?", eleveId) > 0) {
            audit.enregistrer("PHOTO_ELEVE_SUPPRIMEE", eleve.getMatricule(), null);
        }
    }

    /** Cartes d'identité scolaires des élèves actifs d'une classe, huit par page A4. */
    @Transactional(readOnly = true)
    public byte[] cartesClasse(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        List<InscriptionVue> liste = inscriptionsService.listerParClasse(classeId, false).stream()
                .sorted((a, b) -> (a.nom() + " " + a.prenoms()).compareToIgnoreCase(b.nom() + " " + b.prenoms()))
                .toList();
        return cartes(liste);
    }

    /** Carte scolaire d'un élève (son inscription la plus récente). */
    @Transactional(readOnly = true)
    public byte[] carte(UUID eleveId) {
        UtilisateurConnecte.etablissementActif();
        Eleve eleve = charger(eleveId);
        Inscription recente = inscriptions.findByEleveIdOrderByInscritLeDesc(eleve.getId()).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("L'élève n'est inscrit dans aucune classe"));
        return cartes(List.of(inscriptionsService.trouver(recente.getId())));
    }

    private byte[] cartes(List<InscriptionVue> liste) {
        List<CartesPdf.Carte> cartes = new ArrayList<>();
        for (InscriptionVue i : liste) {
            byte[] image = photo(i.eleveId(), false).map(Photo::contenu).orElse(null);
            List<String[]> lignes = List.of(
                    new String[] { "Matricule", i.matricule() },
                    new String[] { "Né(e) le", i.dateNaissance() != null ? i.dateNaissance().format(JOUR) : null },
                    new String[] { "Classe", i.classeCode() });
            cartes.add(new CartesPdf.Carte(i.nom(), i.prenoms(), lignes, image, "Année scolaire " + i.anneeLibelle()));
        }
        return CartesPdf.rendre(entetes.courant(), "CARTE D'IDENTITÉ SCOLAIRE", cartes);
    }

    private Eleve charger(UUID eleveId) {
        return eleves.findById(eleveId).orElseThrow(() -> new RessourceIntrouvableException("Élève introuvable"));
    }
}
