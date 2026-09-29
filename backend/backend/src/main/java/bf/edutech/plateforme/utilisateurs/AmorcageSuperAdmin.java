package bf.edutech.plateforme.utilisateurs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.config.ParametresPlateforme;
import bf.edutech.plateforme.socle.telephone.NumeroTelephone;

/** Crée le premier super administrateur au démarrage, une seule fois. */
@Component
class AmorcageSuperAdmin implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AmorcageSuperAdmin.class);

    private final AmorcageProperties proprietes;
    private final UtilisateurRepository utilisateurs;
    private final PasswordEncoder encodeur;
    private final ParametresPlateforme parametres;

    AmorcageSuperAdmin(AmorcageProperties proprietes, UtilisateurRepository utilisateurs, PasswordEncoder encodeur,
            ParametresPlateforme parametres) {
        this.proprietes = proprietes;
        this.utilisateurs = utilisateurs;
        this.encodeur = encodeur;
        this.parametres = parametres;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String telephoneSaisi = proprietes.superAdminTelephone();
        if (telephoneSaisi == null || telephoneSaisi.isBlank() || utilisateurs.existsBySuperAdminTrue()) {
            return;
        }
        String motDePasse = proprietes.superAdminMotDePasse();
        if (motDePasse == null || motDePasse.length() < 12) {
            throw new IllegalStateException(
                    "Le mot de passe initial du super administrateur doit faire au moins 12 caractères");
        }
        String telephone = NumeroTelephone.normaliser(telephoneSaisi, parametres.indicatifTelephone());
        if (utilisateurs.findByTelephone(telephone).isPresent()) {
            throw new IllegalStateException("Ce téléphone est déjà utilisé par un compte d'établissement");
        }
        Utilisateur superAdmin = new Utilisateur(telephone,
                valeurOuDefaut(proprietes.superAdminNom(), "ADMINISTRATEUR"),
                valeurOuDefaut(proprietes.superAdminPrenoms(), "Plateforme"),
                encodeur.encode(motDePasse), true);
        try {
            utilisateurs.save(superAdmin);
        } catch (DataIntegrityViolationException e) {
            // Deux instances démarrées en même temps : l'autre l'a déjà créé
            LOG.info("Super administrateur déjà créé par une autre instance");
            return;
        }
        LOG.info("Super administrateur initial créé ({}) : mot de passe à changer à la première connexion",
                NumeroTelephone.masquer(telephone));
    }

    private static String valeurOuDefaut(String valeur, String defaut) {
        return valeur == null || valeur.isBlank() ? defaut : valeur;
    }
}
