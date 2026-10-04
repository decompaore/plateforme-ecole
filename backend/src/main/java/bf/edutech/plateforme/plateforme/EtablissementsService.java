package bf.edutech.plateforme.plateforme;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.ComptesService;
import bf.edutech.plateforme.utilisateurs.ComptesService.CompteVue;
import bf.edutech.plateforme.utilisateurs.ComptesService.ResultatReinitialisation;
import bf.edutech.plateforme.utilisateurs.MembreVue;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/** Création et cycle de vie des établissements (super administrateur). */
@Service
public class EtablissementsService {

    private static final Pattern FORMAT_CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$");

    public record EtablissementVue(UUID id, String code, String nom, StatutTenant statut, Instant creeLe) {

        static EtablissementVue depuis(Tenant t) {
            return new EtablissementVue(t.getId(), t.getCode(), t.getNom(), t.getStatut(), t.getCreeLe());
        }
    }

    public record ResultatCreation(EtablissementVue etablissement, MembreVue administrateur,
            String motDePasseTemporaire) {
    }

    private final TenantRepository tenants;
    private final MembresService membres;
    private final ComptesService comptes;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    EtablissementsService(TenantRepository tenants, MembresService membres, ComptesService comptes, AuditService audit,
            PlatformTransactionManager gestionnaireTransactions) {
        this.tenants = tenants;
        this.membres = membres;
        this.comptes = comptes;
        this.audit = audit;
        this.transaction = new TransactionTemplate(gestionnaireTransactions);
    }

    @Transactional(readOnly = true)
    public List<EtablissementVue> lister() {
        return tenants.findAllByOrderByNomAsc().stream().map(EtablissementVue::depuis).toList();
    }

    /**
     * Crée l'établissement et son premier administrateur dans une seule transaction.
     * L'identifiant est généré à l'avance pour que la transaction s'ouvre DANS le
     * contexte du nouvel établissement : la Row-Level Security accepte alors
     * l'insertion de l'appartenance de l'administrateur.
     */
    public ResultatCreation creer(String codeSaisi, String nom, String telephoneAdmin, String nomAdmin,
            String prenomsAdmin) {
        String code = codeSaisi.trim().toLowerCase(Locale.ROOT);
        if (!FORMAT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Code invalide : 3 à 30 caractères (lettres minuscules, chiffres, tirets), sans tiret aux extrémités");
        }
        if (tenants.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Ce code d'établissement est déjà utilisé");
        }
        UUID id = UUID.randomUUID();
        return TenantContext.executerPour(id, () -> transaction.execute(statut -> {
            Tenant tenant = tenants.save(new Tenant(id, code, nom.trim()));
            MembresService.ResultatAjout admin = membres.ajouter(telephoneAdmin, nomAdmin, prenomsAdmin,
                    Role.ADMIN_ECOLE);
            audit.enregistrer("ETABLISSEMENT_CREE", code, Map.of("nom", tenant.getNom()));
            return new ResultatCreation(EtablissementVue.depuis(tenant), admin.membre(),
                    admin.motDePasseTemporaire());
        }));
    }

    /** Administrateurs d'un établissement (pour les dépanner en cas d'oubli du mot de passe). */
    public List<CompteVue> administrateurs(UUID id) {
        exister(id);
        return TenantContext.executerPour(id, comptes::administrateurs);
    }

    /** Réinitialise le mot de passe d'un administrateur d'établissement ; renvoie le mot de passe provisoire. */
    public ResultatReinitialisation reinitialiserAdministrateur(UUID id, UUID utilisateurId) {
        exister(id);
        return TenantContext.executerPour(id, () -> comptes.reinitialiserAdministrateur(utilisateurId));
    }

    private void exister(UUID id) {
        if (!tenants.existsById(id)) {
            throw new RessourceIntrouvableException("Établissement introuvable");
        }
    }

    @Transactional
    public EtablissementVue changerStatut(UUID id, StatutTenant statut) {
        Tenant tenant = tenants.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Établissement introuvable"));
        StatutTenant ancien = tenant.getStatut();
        tenant.changerStatut(statut);
        audit.enregistrer("ETABLISSEMENT_STATUT", tenant.getCode(), Map.of("ancien", ancien, "nouveau", statut));
        return EtablissementVue.depuis(tenant);
    }
}
