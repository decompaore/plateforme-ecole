package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.telephone.Indicatifs;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Gestion des membres de l'établissement actif.
 * <p>
 * Si le numéro de téléphone correspond déjà à un compte (par exemple un parent
 * ayant des enfants dans une autre école), ce compte est réutilisé : une personne
 * n'a qu'un seul compte sur la plateforme. Sinon, un compte est créé avec un
 * mot de passe temporaire, renvoyé une seule fois (il sera envoyé par SMS
 * lorsque le module Notifications sera en place).
 */
@Service
public class MembresService {

    /** Résultat d'un ajout ; {@code motDePasseTemporaire} est null si le compte existait déjà. */
    public record ResultatAjout(MembreVue membre, String motDePasseTemporaire) {
    }

    private final MembreEtablissementRepository membres;
    private final UtilisateurRepository utilisateurs;
    private final PasswordEncoder encodeur;
    private final AuditService audit;
    private final Indicatifs indicatifs;
    private final AdministrateursPays administrateursPays;
    private final AdministrateursDirection administrateursDirection;

    MembresService(MembreEtablissementRepository membres, UtilisateurRepository utilisateurs,
            PasswordEncoder encodeur, AuditService audit, Indicatifs indicatifs, AdministrateursPays administrateursPays,
            AdministrateursDirection administrateursDirection) {
        this.administrateursPays = administrateursPays;
        this.administrateursDirection = administrateursDirection;
        this.membres = membres;
        this.utilisateurs = utilisateurs;
        this.encodeur = encodeur;
        this.audit = audit;
        this.indicatifs = indicatifs;
    }

    @Transactional(readOnly = true)
    public List<MembreVue> lister() {
        UtilisateurConnecte.etablissementActif();
        return membres.listerAvecIdentite();
    }

    /** Ajoute une personne avec un rôle ; erreur DEJA_MEMBRE si elle a déjà ce rôle actif. */
    @Transactional
    public ResultatAjout ajouter(String telephoneSaisi, String nom, String prenoms, Role role) {
        return ajouter(telephoneSaisi, nom, prenoms, role, true);
    }

    /**
     * Garantit qu'une personne a un rôle dans l'établissement (ex. PARENT lors de
     * l'ouverture de l'espace parent) : sans erreur si elle l'a déjà.
     */
    @Transactional
    public ResultatAjout garantirRole(String telephoneSaisi, String nom, String prenoms, Role role) {
        return ajouter(telephoneSaisi, nom, prenoms, role, false);
    }

    private ResultatAjout ajouter(String telephoneSaisi, String nom, String prenoms, Role role,
            boolean refuserSiDejaMembre) {
        UUID etablissement = UtilisateurConnecte.etablissementActif();
        String telephone = indicatifs.normaliser(telephoneSaisi);

        String motDePasseTemporaire = null;
        Utilisateur utilisateur = utilisateurs.findByTelephone(telephone).orElse(null);
        if (utilisateur == null) {
            motDePasseTemporaire = GenerateurMotDePasse.temporaire();
            utilisateur = utilisateurs.save(new Utilisateur(telephone, nom.trim().toUpperCase(), prenoms.trim(),
                    encodeur.encode(motDePasseTemporaire), false));
        } else if (utilisateur.isSuperAdmin() || administrateursPays.de(utilisateur.getId()).isPresent()
                || administrateursDirection.de(utilisateur.getId()).isPresent()) {
            throw new RegleMetierException("COMPTE_PLATEFORME",
                    "Un compte d'administration de la plateforme ne peut pas être membre d'un établissement");
        }

        Optional<MembreEtablissement> existant = membres.findByUtilisateurIdAndRole(utilisateur.getId(), role);
        MembreEtablissement membre;
        if (existant.isPresent()) {
            membre = existant.get();
            if (membre.isActif()) {
                if (refuserSiDejaMembre) {
                    throw new RegleMetierException("DEJA_MEMBRE", "Cette personne a déjà ce rôle dans l'établissement");
                }
                return new ResultatAjout(vue(membre, utilisateur), null);
            }
            membre.reactiver();
        } else {
            membre = membres.save(new MembreEtablissement(etablissement, utilisateur.getId(), role));
        }
        audit.enregistrer("MEMBRE_AJOUTE", role.name(),
                Map.of("utilisateur", utilisateur.getId(), "nouveauCompte", motDePasseTemporaire != null));
        return new ResultatAjout(vue(membre, utilisateur), motDePasseTemporaire);
    }

    private static MembreVue vue(MembreEtablissement membre, Utilisateur utilisateur) {
        return new MembreVue(membre.getId(), utilisateur.getId(), utilisateur.getNom(), utilisateur.getPrenoms(),
                utilisateur.getTelephone(), membre.getRole(), membre.isActif());
    }

    /** Retire un rôle à un compte dans l'établissement actif (ex. fin d'engagement d'un enseignant). */
    @Transactional
    public void retirerRole(UUID utilisateurId, Role role) {
        UtilisateurConnecte.etablissementActif();
        membres.findByUtilisateurIdAndRole(utilisateurId, role).filter(MembreEtablissement::isActif).ifPresent(m -> {
            m.desactiver();
            audit.enregistrer("MEMBRE_DESACTIVE", role.name(), Map.of("utilisateur", utilisateurId));
        });
    }

    @Transactional
    public void desactiver(UUID membreId) {
        MembreEtablissement membre = membres.findById(membreId)
                .orElseThrow(() -> new RessourceIntrouvableException("Membre introuvable"));
        if (!membre.isActif()) {
            return;
        }
        if (membre.getRole() == Role.ADMIN_ECOLE && membres.countByRoleAndActifTrue(Role.ADMIN_ECOLE) <= 1) {
            throw new RegleMetierException("DERNIER_ADMINISTRATEUR",
                    "Impossible de désactiver le dernier administrateur de l'établissement");
        }
        membre.desactiver();
        audit.enregistrer("MEMBRE_DESACTIVE", membre.getRole().name(), Map.of("membre", membreId));
    }
}
