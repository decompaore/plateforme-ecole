package bf.edutech.plateforme.etablissement;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.pedagogie.ProfilVue;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Filières (séries, spécialités, métiers) et leur profil pédagogique. */
@Service
public class FilieresService {

    private final FiliereRepository filieres;
    private final ClasseRepository classes;
    private final ProfilsService profils;
    private final AuditService audit;

    FilieresService(FiliereRepository filieres, ClasseRepository classes, ProfilsService profils, AuditService audit) {
        this.filieres = filieres;
        this.classes = classes;
        this.profils = profils;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<FiliereVue> lister() {
        UtilisateurConnecte.etablissementActif();
        return filieres.findAllByOrderByCodeAsc().stream().map(FiliereVue::depuis).toList();
    }

    @Transactional
    public FiliereVue creer(String codeSaisi, String libelle, String cycle, String diplomeVise, UUID profilId) {
        UtilisateurConnecte.etablissementActif();
        String code = codeSaisi.trim().toUpperCase(Locale.ROOT);
        if (filieres.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Une filière porte déjà le code " + code);
        }
        exigerProfilActif(profilId);
        Filiere filiere = filieres.save(new Filiere(code, libelle.trim(), cycle.trim(), vide(diplomeVise), profilId));
        audit.enregistrer("FILIERE_CREEE", code, null);
        return FiliereVue.depuis(filiere);
    }

    /** Le profil d'une filière ne change plus dès qu'une classe l'utilise. */
    @Transactional
    public FiliereVue modifier(UUID id, String libelle, String cycle, String diplomeVise, UUID profilId) {
        UtilisateurConnecte.etablissementActif();
        Filiere filiere = filieres.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Filière introuvable"));
        if (!filiere.getProfilId().equals(profilId)) {
            if (classes.existsByFiliereId(id)) {
                throw new RegleMetierException("FILIERE_UTILISEE",
                        "Des classes utilisent cette filière : son profil pédagogique ne peut plus changer");
            }
            exigerProfilActif(profilId);
        }
        filiere.modifier(libelle.trim(), cycle.trim(), vide(diplomeVise), profilId);
        return FiliereVue.depuis(filiere);
    }

    private void exigerProfilActif(UUID profilId) {
        ProfilVue profil = profils.trouver(profilId);
        if (!profil.actif()) {
            throw new RegleMetierException("PROFIL_INACTIF", "Le profil « " + profil.libelle() + " » est désactivé");
        }
    }

    private static String vide(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
