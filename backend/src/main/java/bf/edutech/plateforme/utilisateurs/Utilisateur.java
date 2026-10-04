package bf.edutech.plateforme.utilisateurs;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteUuid;

/**
 * Compte de connexion : un seul par personne sur toute la plateforme
 * (identifié par son numéro de téléphone). Table de niveau plateforme,
 * non cloisonnée par établissement.
 */
@Entity
@Table(name = "utilisateur")
public class Utilisateur extends EntiteUuid {

    @Column(name = "telephone", nullable = false, unique = true, length = 20)
    private String telephone;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "nom", nullable = false, length = 80)
    private String nom;

    @Column(name = "prenoms", nullable = false, length = 120)
    private String prenoms;

    @Column(name = "mot_de_passe_hache", nullable = false, length = 100)
    private String motDePasseHache;

    @Column(name = "super_admin", nullable = false)
    private boolean superAdmin;

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    @Column(name = "doit_changer_mot_de_passe", nullable = false)
    private boolean doitChangerMotDePasse = true;

    @Column(name = "echecs_connexion", nullable = false)
    private int echecsConnexion;

    @Column(name = "verrouille_jusqua")
    private Instant verrouilleJusqua;

    @Column(name = "derniere_connexion")
    private Instant derniereConnexion;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe = Instant.now();

    protected Utilisateur() {
    }

    public Utilisateur(String telephone, String nom, String prenoms, String motDePasseHache, boolean superAdmin) {
        this.telephone = telephone;
        this.nom = nom;
        this.prenoms = prenoms;
        this.motDePasseHache = motDePasseHache;
        this.superAdmin = superAdmin;
    }

    /** Vrai si le compte est verrouillé à l'instant donné. */
    public boolean estVerrouille(Instant maintenant) {
        return verrouilleJusqua != null && verrouilleJusqua.isAfter(maintenant);
    }

    /** Compte un échec ; verrouille le compte après {@code maxEchecs} échecs consécutifs. */
    public void enregistrerEchec(Instant maintenant, int maxEchecs, Duration dureeVerrouillage) {
        echecsConnexion++;
        if (echecsConnexion >= maxEchecs) {
            verrouilleJusqua = maintenant.plus(dureeVerrouillage);
            echecsConnexion = 0;
        }
    }

    public void enregistrerConnexionReussie(Instant maintenant) {
        echecsConnexion = 0;
        verrouilleJusqua = null;
        derniereConnexion = maintenant;
    }

    /**
     * Réinitialisation par l'administration : mot de passe provisoire à changer à la prochaine
     * connexion ; le compte est déverrouillé.
     */
    public void reinitialiserMotDePasse(String nouveauHache) {
        this.motDePasseHache = nouveauHache;
        this.doitChangerMotDePasse = true;
        deverrouiller();
    }

    /** Lève le verrouillage dû aux échecs de connexion. */
    public void deverrouiller() {
        this.echecsConnexion = 0;
        this.verrouilleJusqua = null;
    }

    public Instant getVerrouilleJusqua() {
        return verrouilleJusqua;
    }

    public void changerMotDePasse(String nouveauHache, boolean temporaire) {
        this.motDePasseHache = nouveauHache;
        this.doitChangerMotDePasse = temporaire;
    }

    public String getTelephone() {
        return telephone;
    }

    public String getEmail() {
        return email;
    }

    public String getNom() {
        return nom;
    }

    public String getPrenoms() {
        return prenoms;
    }

    public String getMotDePasseHache() {
        return motDePasseHache;
    }

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    public boolean isActif() {
        return actif;
    }

    public boolean isDoitChangerMotDePasse() {
        return doitChangerMotDePasse;
    }

    public Instant getDerniereConnexion() {
        return derniereConnexion;
    }
}
