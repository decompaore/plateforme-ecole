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

    /**
     * Accès à l'espace parent d'un responsable, pour la fiche de remise d'une classe ;
     * {@code motDePasseTemporaire} est null si la personne avait déjà un compte.
     */
    public record AccesParent(UUID responsableId, String parent, String lien, String telephone, String eleves,
            String motDePasseTemporaire) {
    }

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

    /**
     * Ouvre en une fois l'espace parent des responsables des élèves inscrits dans une classe :
     * responsables légaux et contacts prioritaires (ceux qui reçoivent les SMS). Un responsable de
     * plusieurs élèves de la classe n'a qu'un compte. Les mots de passe provisoires des nouveaux
     * comptes ne sont renvoyés qu'ici, pour la fiche de remise.
     */
    @Transactional
    public List<AccesParent> ouvrirPourClasse(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        Map<UUID, List<String>> elevesParResponsable = new java.util.LinkedHashMap<>();
        Map<UUID, LienParente> lienParResponsable = new java.util.HashMap<>();
        for (Object[] ligne : inscriptions.listerAvecEleves(classeId)) {
            Inscription inscription = (Inscription) ligne[0];
            Eleve eleve = (Eleve) ligne[1];
            if (inscription.getStatut() != StatutInscription.ACTIVE) {
                continue;
            }
            for (LienResponsableEleve lien : liens.findByEleveId(eleve.getId())) {
                if (lien.isResponsableLegal() || lien.isContactPrioritaire()) {
                    elevesParResponsable.computeIfAbsent(lien.getResponsableId(), k -> new java.util.ArrayList<>())
                            .add(eleve.getNom() + " " + eleve.getPrenoms());
                    lienParResponsable.putIfAbsent(lien.getResponsableId(), lien.getLien());
                }
            }
        }
        if (elevesParResponsable.isEmpty()) {
            throw new bf.edutech.plateforme.socle.erreurs.RegleMetierException("AUCUN_RESPONSABLE",
                    "Aucun responsable légal ni contact prioritaire parmi les élèves inscrits dans cette classe");
        }
        return responsables.findByIdIn(elevesParResponsable.keySet()).stream()
                .sorted(Comparator.comparing(Responsable::getNom).thenComparing(Responsable::getPrenoms))
                .map(r -> {
                    EspaceParentVue v = ouvrir(r.getId());
                    return new AccesParent(r.getId(), r.getNom() + " " + r.getPrenoms(),
                            libelleLien(lienParResponsable.get(r.getId())), r.getTelephone(),
                            String.join(", ", elevesParResponsable.get(r.getId())), v.motDePasseTemporaire());
                })
                .toList();
    }

    /** Code de la classe (titre de la fiche de remise). */
    @Transactional(readOnly = true)
    public String codeClasse(UUID classeId) {
        return registre.classe(classeId).code();
    }

    static String libelleLien(LienParente lien) {
        return lien == null ? "" : switch (lien) {
            case PERE -> "Père";
            case MERE -> "Mère";
            case TUTEUR -> "Tuteur";
            case AUTRE -> "Autre";
        };
    }

    /** L'élève est-il un enfant du parent connecté (responsable rattaché, espace ouvert) ? */
    @Transactional(readOnly = true)
    public boolean estMonEnfant(UUID eleveId) {
        UtilisateurConnecte.etablissementActif();
        return responsables.findByUtilisateurId(UtilisateurConnecte.id())
                .map(r -> liens.existsByEleveIdAndResponsableId(eleveId, r.getId()))
                .orElse(false);
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
