package bf.edutech.plateforme.eleves;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.Vues.EnfantVue;
import bf.edutech.plateforme.eleves.Vues.EspaceParentVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.MembresService.ResultatAjout;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Espace parent : ouverture d'un compte pour un responsable (rôle PARENT) et
 * consultation, par ce parent, de la situation de ses enfants.
 * <p>
 * Un parent qui a des enfants dans deux établissements utilise le même compte
 * (même téléphone) et choisit l'établissement à la connexion.
 */
@Service
public class EspaceParentService {

    private final ResponsableRepository responsables;
    private final LienResponsableEleveRepository liens;
    private final EleveRepository eleves;
    private final InscriptionRepository inscriptions;
    private final MembresService membres;
    private final RegistreInscriptions registre;
    private final AuditService audit;

    EspaceParentService(ResponsableRepository responsables, LienResponsableEleveRepository liens,
            EleveRepository eleves, InscriptionRepository inscriptions, MembresService membres,
            RegistreInscriptions registre, AuditService audit) {
        this.responsables = responsables;
        this.liens = liens;
        this.eleves = eleves;
        this.inscriptions = inscriptions;
        this.membres = membres;
        this.registre = registre;
        this.audit = audit;
    }

    /** Ouvre (ou rouvre) l'espace parent du responsable ; sans effet s'il est déjà ouvert. */
    @Transactional
    public EspaceParentVue ouvrir(UUID responsableId) {
        UtilisateurConnecte.etablissementActif();
        Responsable responsable = responsables.findById(responsableId)
                .orElseThrow(() -> new RessourceIntrouvableException("Responsable introuvable"));
        ResultatAjout resultat = membres.garantirRole(responsable.getTelephone(), responsable.getNom(),
                responsable.getPrenoms(), Role.PARENT);
        UUID utilisateurId = resultat.membre().utilisateurId();
        responsable.lierCompte(utilisateurId);
        audit.enregistrer("ESPACE_PARENT_OUVERT", responsableId.toString(),
                Map.of("nouveauCompte", resultat.motDePasseTemporaire() != null));
        return new EspaceParentVue(responsable.getId(), utilisateurId, responsable.getTelephone(),
                resultat.motDePasseTemporaire());
    }

    /** Enfants du parent connecté dans l'établissement actif, avec leur situation la plus récente. */
    @Transactional(readOnly = true)
    public List<EnfantVue> mesEnfants() {
        UtilisateurConnecte.etablissementActif();
        Optional<Responsable> responsable = responsables.findByUtilisateurId(UtilisateurConnecte.id());
        if (responsable.isEmpty()) {
            return List.of();
        }
        Libelles libelles = registre.libelles();
        return liens.findByResponsableId(responsable.get().getId()).stream()
                .map(lien -> {
                    Eleve eleve = eleves.findById(lien.getEleveId()).orElseThrow();
                    Optional<Inscription> derniere = libelles.parAnneeDecroissante(
                            inscriptions.findByEleveIdOrderByInscritLeDesc(eleve.getId())).stream().findFirst();
                    return new EnfantVue(eleve.getId(), eleve.getMatricule(), eleve.getNom(), eleve.getPrenoms(),
                            eleve.getSexe(), eleve.getDateNaissance(), lien.getLien(),
                            derniere.map(i -> libelles.annee(i.getAnneeId()).libelle()).orElse(null),
                            derniere.map(i -> libelles.classe(i.getClasseId()).code()).orElse(null),
                            derniere.map(Inscription::getStatut).orElse(null));
                })
                .sorted(Comparator.comparing(EnfantVue::nom).thenComparing(EnfantVue::prenoms))
                .toList();
    }
}
