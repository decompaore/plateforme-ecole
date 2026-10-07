package bf.edutech.plateforme.plateforme;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.modules.Module;
import bf.edutech.plateforme.socle.modules.ModulesEtablissement;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.socle.securite.Portee;
import bf.edutech.plateforme.territoire.PorteeTerritoire;
import bf.edutech.plateforme.territoire.TerritoireService;
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

    /**
     * @param directionId   direction de rattachement (dernier niveau), ou null
     * @param rattachement  « Burkina Faso · MESFPT · Direction régionale … · Direction provinciale … »
     */
    public record EtablissementVue(UUID id, String code, String nom, StatutTenant statut, Instant creeLe,
            UUID directionId, String rattachement) {
    }

    /** Module activable d'un établissement, pour la page du super administrateur. */
    public record ModuleVue(Module code, String libelle, String description, Module requis, boolean actif) {
    }

    public record ResultatCreation(EtablissementVue etablissement, MembreVue administrateur,
            String motDePasseTemporaire) {
    }

    private final TenantRepository tenants;
    private final MembresService membres;
    private final ComptesService comptes;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final ModulesEtablissement modules;
    private final TerritoireService territoire;
    private final PorteeTerritoire portee;

    EtablissementsService(TenantRepository tenants, MembresService membres, ComptesService comptes, AuditService audit,
            PlatformTransactionManager gestionnaireTransactions, ModulesEtablissement modules, TerritoireService territoire, PorteeTerritoire portee) {
        this.portee = portee;
        this.modules = modules;
        this.territoire = territoire;
        this.tenants = tenants;
        this.membres = membres;
        this.comptes = comptes;
        this.audit = audit;
        this.transaction = new TransactionTemplate(gestionnaireTransactions);
    }

    @Transactional(readOnly = true)
    public List<EtablissementVue> lister() {
        return lister(null);
    }

    /** Établissements, éventuellement limités à ceux qui dépendent d'une direction (à tout niveau). */
    @Transactional(readOnly = true)
    public List<EtablissementVue> lister(UUID direction) {
        Set<UUID> sousDirection = direction == null ? null : Set.copyOf(territoire.descendantes(direction));
        // Administrateur pays : seulement les établissements rattachés à son pays
        java.util.Optional<UUID> pays = portee.pays();
        Map<UUID, String> chemins = new java.util.HashMap<>();
        return tenants.findAllByOrderByNomAsc().stream()
                .filter(t -> sousDirection == null || (t.getDirectionId() != null && sousDirection.contains(t.getDirectionId())))
                .filter(t -> pays.isEmpty() || pays.get().equals(portee.paysEtablissement(t.getId())))
                .map(t -> vue(t, chemins)).toList();
    }

    private EtablissementVue vue(Tenant t, Map<UUID, String> chemins) {
        String chemin = t.getDirectionId() == null ? null
                : chemins.computeIfAbsent(t.getDirectionId(), d -> territoire.chemin(d).orElse(null));
        return new EtablissementVue(t.getId(), t.getCode(), t.getNom(), t.getStatut(), t.getCreeLe(), t.getDirectionId(),
                chemin);
    }

    private EtablissementVue vue(Tenant t) {
        return vue(t, new java.util.HashMap<>());
    }

    /** Rattache l'établissement à une direction du dernier niveau (ex. direction provinciale). */
    @Transactional
    public EtablissementVue rattacher(UUID id, UUID direction) {
        portee.verifierEtablissement(id);
        portee.verifierDirection(direction);
        Tenant tenant = tenants.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Établissement introuvable"));
        territoire.verifierRattachement(direction);
        tenant.rattacher(direction);
        tenants.save(tenant);
        EtablissementVue v = vue(tenant);
        audit.enregistrer("ETABLISSEMENT_RATTACHE", tenant.getCode(), Map.of("rattachement", String.valueOf(v.rattachement())));
        return v;
    }

    /**
     * Crée l'établissement et son premier administrateur dans une seule transaction.
     * L'identifiant est généré à l'avance pour que la transaction s'ouvre DANS le
     * contexte du nouvel établissement : la Row-Level Security accepte alors
     * l'insertion de l'appartenance de l'administrateur.
     */
    public ResultatCreation creer(String codeSaisi, String nom, String telephoneAdmin, String nomAdmin,
            String prenomsAdmin) {
        return creer(codeSaisi, nom, telephoneAdmin, nomAdmin, prenomsAdmin, null);
    }

    /**
     * Variante avec rattachement : le numéro de l'administrateur reçoit alors l'indicatif du pays
     * de la direction.
     */
    public ResultatCreation creer(String codeSaisi, String nom, String telephoneAdmin, String nomAdmin,
            String prenomsAdmin, UUID direction) {
        String code = codeSaisi.trim().toLowerCase(Locale.ROOT);
        if (!FORMAT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Code invalide : 3 à 30 caractères (lettres minuscules, chiffres, tirets), sans tiret aux extrémités");
        }
        if (tenants.existsByCode(code)) {
            throw new RegleMetierException("CODE_EXISTANT", "Ce code d'établissement est déjà utilisé");
        }
        if (direction == null && portee.pays().isPresent()) {
            throw new IllegalArgumentException("Choisissez la direction de rattachement de l'établissement");
        }
        if (direction != null) {
            portee.verifierDirection(direction);
            territoire.verifierRattachement(direction);
        }
        UUID id = UUID.randomUUID();
        return TenantContext.executerPour(id, () -> transaction.execute(statut -> {
            Tenant nouveau = new Tenant(id, code, nom.trim());
            nouveau.rattacher(direction);
            Tenant tenant = tenants.saveAndFlush(nouveau);
            MembresService.ResultatAjout admin = membres.ajouter(telephoneAdmin, nomAdmin, prenomsAdmin,
                    Role.ADMIN_ECOLE);
            audit.enregistrer("ETABLISSEMENT_CREE", code, Map.of("nom", tenant.getNom()));
            return new ResultatCreation(vue(tenant), admin.membre(),
                    admin.motDePasseTemporaire());
        }));
    }

    /** Administrateurs d'un établissement (pour les dépanner en cas d'oubli du mot de passe). */
    public List<CompteVue> administrateurs(UUID id) {
        exister(id);
        portee.verifierEtablissement(id);
        return TenantContext.executerPour(id, comptes::administrateurs);
    }

    /** Réinitialise le mot de passe d'un administrateur d'établissement ; renvoie le mot de passe provisoire. */
    public ResultatReinitialisation reinitialiserAdministrateur(UUID id, UUID utilisateurId) {
        exister(id);
        portee.verifierEtablissement(id);
        return TenantContext.executerPour(id, () -> comptes.reinitialiserAdministrateur(utilisateurId));
    }

    /** Modules de l'établissement : actifs ou désactivés par le super administrateur. */
    public List<ModuleVue> modules(UUID id) {
        exister(id);
        portee.verifierEtablissement(id);
        Set<Module> fermes = modules.desactives(List.of(id)).getOrDefault(id, Set.of());
        return java.util.Arrays.stream(Module.values())
                .map(m -> new ModuleVue(m, m.libelle(), m.description(), m.requis(), !fermes.contains(m))).toList();
    }

    /** Active ou désactive des modules ; désactiver un module désactive ceux qui en dépendent. */
    public List<ModuleVue> definirModules(UUID id, Set<Module> actifs) {
        portee.verifierEtablissement(id);
        Tenant tenant = tenants.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Établissement introuvable"));
        Set<Module> fermes = java.util.EnumSet.allOf(Module.class);
        fermes.removeAll(actifs);
        Set<Module> resultat = modules.definir(id, fermes, UtilisateurConnecte.id());
        audit.enregistrer("MODULES_ETABLISSEMENT", tenant.getCode(),
                Map.of("desactives", resultat.stream().map(Enum::name).sorted().toList()));
        return modules(id);
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
        portee.verifierEtablissement(id);
        StatutTenant ancien = tenant.getStatut();
        if ((statut == StatutTenant.RESILIE || ancien == StatutTenant.RESILIE) && !Portee.superAdmin()
                && portee.pays().isPresent()) {
            throw new bf.edutech.plateforme.socle.erreurs.AccesRefuseException(
                    "La résiliation d'un établissement est réservée au super administrateur");
        }
        tenant.changerStatut(statut);
        audit.enregistrer("ETABLISSEMENT_STATUT", tenant.getCode(), Map.of("ancien", ancien, "nouveau", statut));
        return vue(tenant);
    }
}
