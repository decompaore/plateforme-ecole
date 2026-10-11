package bf.edutech.plateforme.scolarite;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.modules.Module;
import bf.edutech.plateforme.socle.modules.ModulesEtablissement;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Moyens de paiement de l'établissement (v0.38) :
 * <ul>
 * <li>moyens acceptés au guichet (seuls ceux-là sont proposés à l'intendance) ;</li>
 * <li>moyens proposés aux parents : Mobile Money (opérateurs choisis) et virement bancaire
 * déclaré ;</li>
 * <li>comptes bancaires affichés aux parents (jamais supprimés : désactivés).</li>
 * </ul>
 * Valeurs par défaut tant que rien n'est enregistré : tous les moyens au guichet, Mobile Money
 * proposé aux parents avec tous les opérateurs, virement non proposé.
 */
@Service
public class MoyensPaiementService {

    /** Opérateurs Mobile Money (sous-ensemble des moyens de paiement). */
    public static final Set<MoyenPaiement> OPERATEURS =
            EnumSet.of(MoyenPaiement.ORANGE_MONEY, MoyenPaiement.MOOV_MONEY, MoyenPaiement.TELECEL_MONEY);

    public record CompteBancaireVue(UUID id, String banque, String titulaire, String numero, String codeSwift,
            String agence, boolean actif) {
    }

    public record DonneesCompte(String banque, String titulaire, String numero, String codeSwift, String agence,
            Boolean actif) {
    }

    /** Paramètres complets (intendance, direction). */
    public record ParametresPaiementVue(List<MoyenPaiement> moyensGuichet, boolean mobileMoneyParents,
            List<MoyenPaiement> operateurs, boolean virementParents, String consigneVirement,
            List<CompteBancaireVue> comptes, boolean mobileMoneyDisponible) {
    }

    public record DonneesParametres(List<MoyenPaiement> moyensGuichet, Boolean mobileMoneyParents,
            List<MoyenPaiement> operateurs, Boolean virementParents, String consigneVirement) {
    }

    /** Ce qu'un parent peut utiliser pour payer la scolarité d'un enfant. */
    public record MoyensParentVue(boolean mobileMoney, List<MoyenPaiement> operateurs, boolean virement,
            List<CompteBancaireVue> comptes, String consigneVirement, String referenceAIndiquer) {
    }

    private record Parametres(List<MoyenPaiement> moyensGuichet, boolean mobileMoneyParents,
            List<MoyenPaiement> operateurs, boolean virementParents, String consigneVirement) {
    }

    private static final Parametres DEFAUT = new Parametres(List.of(MoyenPaiement.values()), true,
            List.copyOf(OPERATEURS.stream().sorted().toList()), false, null);

    private final JdbcTemplate jdbc;
    private final InscriptionsService inscriptions;
    private final EspaceParentService espaceParent;
    private final ModulesEtablissement modules;
    private final ObjectProvider<DisponibiliteMobileMoney> mobileMoney;
    private final AuditService audit;

    MoyensPaiementService(JdbcTemplate jdbc, InscriptionsService inscriptions, EspaceParentService espaceParent,
            ModulesEtablissement modules, ObjectProvider<DisponibiliteMobileMoney> mobileMoney, AuditService audit) {
        this.jdbc = jdbc;
        this.inscriptions = inscriptions;
        this.espaceParent = espaceParent;
        this.modules = modules;
        this.mobileMoney = mobileMoney;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public ParametresPaiementVue lire() {
        UtilisateurConnecte.etablissementActif();
        Parametres p = parametres();
        return new ParametresPaiementVue(p.moyensGuichet(), p.mobileMoneyParents(), p.operateurs(),
                p.virementParents(), p.consigneVirement(), comptes(false), mobileMoneyDisponible());
    }

    /** Moyens proposés aux parents pour une inscription de leur enfant. */
    @Transactional(readOnly = true)
    public MoyensParentVue pourParent(UUID inscriptionId) {
        UtilisateurConnecte.etablissementActif();
        InscriptionVue inscription = inscriptions.trouver(inscriptionId);
        if (!espaceParent.estMonEnfant(inscription.eleveId())) {
            throw new AccesRefuseException("Cet élève n'est pas rattaché à votre compte");
        }
        Parametres p = parametres();
        boolean mm = p.mobileMoneyParents() && !p.operateurs().isEmpty() && mobileMoneyDisponible();
        List<CompteBancaireVue> comptes = comptes(true);
        boolean virement = p.virementParents() && !comptes.isEmpty();
        return new MoyensParentVue(mm, mm ? p.operateurs() : List.of(), virement, virement ? comptes : List.of(),
                virement ? p.consigneVirement() : null, referenceAIndiquer(inscription));
    }

    /** Référence que le parent indique sur son ordre de virement : matricule et nom de l'élève. */
    static String referenceAIndiquer(InscriptionVue i) {
        return "SCOL " + i.matricule() + " " + i.nom().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ contrôles

    /** Encaissement au guichet : le moyen doit être accepté par l'établissement. */
    @Transactional(readOnly = true)
    public void verifierGuichet(MoyenPaiement moyen) {
        if (moyen != null && !parametres().moyensGuichet().contains(moyen)) {
            throw new RegleMetierException("MOYEN_NON_ACCEPTE",
                    moyen.libelle() + " n'est pas accepté au guichet de l'établissement");
        }
    }

    /** Paiement Mobile Money par un parent : proposé par l'établissement, avec cet opérateur. */
    @Transactional(readOnly = true)
    public void verifierMobileMoneyParent(MoyenPaiement operateur) {
        Parametres p = parametres();
        if (!p.mobileMoneyParents()) {
            throw new RegleMetierException("MOBILE_MONEY_NON_PROPOSE",
                    "L'établissement ne propose pas le paiement Mobile Money : payez à l'intendance");
        }
        if (!p.operateurs().contains(operateur)) {
            throw new RegleMetierException("OPERATEUR_NON_PROPOSE",
                    operateur.libelle() + " n'est pas proposé par l'établissement");
        }
    }

    /** Virement déclaré par un parent : proposé, vers un compte actif de l'établissement. */
    void verifierVirementParent(UUID compteId) {
        if (!parametres().virementParents()) {
            throw new RegleMetierException("VIREMENT_NON_PROPOSE",
                    "L'établissement ne propose pas le paiement par virement");
        }
        CompteBancaireVue c = compte(compteId);
        if (!c.actif()) {
            throw new RegleMetierException("COMPTE_INACTIF", "Ce compte bancaire n'est plus utilisé par l'établissement");
        }
    }

    // ------------------------------------------------------------------ modification (direction)

    @Transactional
    public ParametresPaiementVue modifier(DonneesParametres d) {
        UtilisateurConnecte.etablissementActif();
        Parametres actuels = parametres();
        List<MoyenPaiement> guichet = d.moyensGuichet() == null ? actuels.moyensGuichet()
                : d.moyensGuichet().stream().distinct().sorted().toList();
        if (guichet.isEmpty()) {
            throw new IllegalArgumentException("Acceptez au moins un moyen de paiement au guichet");
        }
        List<MoyenPaiement> operateurs = d.operateurs() == null ? actuels.operateurs()
                : d.operateurs().stream().distinct().sorted().toList();
        if (!OPERATEURS.containsAll(operateurs)) {
            throw new IllegalArgumentException("Opérateurs Mobile Money : ORANGE_MONEY, MOOV_MONEY ou TELECEL_MONEY");
        }
        boolean mm = d.mobileMoneyParents() != null ? d.mobileMoneyParents() : actuels.mobileMoneyParents();
        if (mm && operateurs.isEmpty()) {
            throw new IllegalArgumentException("Choisissez au moins un opérateur pour le paiement Mobile Money");
        }
        boolean virement = d.virementParents() != null ? d.virementParents() : actuels.virementParents();
        if (virement && comptes(true).isEmpty()) {
            throw new RegleMetierException("SANS_COMPTE_BANCAIRE",
                    "Enregistrez d'abord un compte bancaire actif pour proposer le virement aux parents");
        }
        String consigne = d.consigneVirement() == null ? actuels.consigneVirement() : vide(d.consigneVirement());
        if (consigne != null && consigne.length() > 300) {
            throw new IllegalArgumentException("Consigne : 300 caractères au plus");
        }
        jdbc.update("""
                insert into parametres_paiement (tenant_id, moyens_guichet, mobile_money_parents, operateurs,
                       virement_parents, consigne_virement, modifie_par, modifie_le)
                values (tenant_courant(), string_to_array(?, ',')::varchar[], ?, string_to_array(?, ',')::varchar[], ?, ?,
                       (select id from utilisateur where id = ?), now())
                on conflict (tenant_id) do update set moyens_guichet = excluded.moyens_guichet,
                       mobile_money_parents = excluded.mobile_money_parents, operateurs = excluded.operateurs,
                       virement_parents = excluded.virement_parents, consigne_virement = excluded.consigne_virement,
                       modifie_par = excluded.modifie_par, modifie_le = now()""",
                liste(guichet), mm, liste(operateurs), virement, consigne, UtilisateurConnecte.idSiConnecte().orElse(null));
        audit.enregistrer("MOYENS_PAIEMENT_MODIFIES", null, Map.of("guichet", liste(guichet), "mobileMoney", mm,
                "operateurs", liste(operateurs), "virement", virement));
        return lire();
    }

    @Transactional
    public CompteBancaireVue creerCompte(DonneesCompte d) {
        UtilisateurConnecte.etablissementActif();
        DonneesCompte c = valider(d);
        UUID id = UUID.randomUUID();
        exigerNumeroLibre(c.numero(), id);
        jdbc.update("""
                insert into compte_bancaire (tenant_id, id, banque, titulaire, numero, code_swift, agence, actif, modifie_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, (select id from utilisateur where id = ?))""",
                id, c.banque(), c.titulaire(), c.numero(), c.codeSwift(), c.agence(), c.actif() == null || c.actif(),
                UtilisateurConnecte.idSiConnecte().orElse(null));
        audit.enregistrer("COMPTE_BANCAIRE_AJOUTE", c.banque(), Map.of("numero", masquer(c.numero())));
        return compte(id);
    }

    @Transactional
    public CompteBancaireVue modifierCompte(UUID id, DonneesCompte d) {
        UtilisateurConnecte.etablissementActif();
        CompteBancaireVue avant = compte(id);
        DonneesCompte c = valider(d);
        exigerNumeroLibre(c.numero(), id);
        boolean actif = c.actif() == null ? avant.actif() : c.actif();
        if (!actif && avant.actif() && parametres().virementParents() && comptes(true).size() == 1) {
            throw new RegleMetierException("DERNIER_COMPTE",
                    "C'est le seul compte proposé aux parents : arrêtez d'abord le virement ou ajoutez un autre compte");
        }
        jdbc.update("""
                update compte_bancaire set banque = ?, titulaire = ?, numero = ?, code_swift = ?, agence = ?, actif = ?,
                       modifie_par = (select id from utilisateur where id = ?), modifie_le = now()
                where tenant_id = tenant_courant() and id = ?""",
                c.banque(), c.titulaire(), c.numero(), c.codeSwift(), c.agence(), actif,
                UtilisateurConnecte.idSiConnecte().orElse(null), id);
        audit.enregistrer("COMPTE_BANCAIRE_MODIFIE", c.banque(),
                Map.of("numeroAvant", masquer(avant.numero()), "numero", masquer(c.numero()), "actif", actif));
        return compte(id);
    }

    // ------------------------------------------------------------------ outils

    CompteBancaireVue compte(UUID id) {
        return jdbc.query("""
                select id, banque, titulaire, numero, code_swift, agence, actif from compte_bancaire
                where tenant_id = tenant_courant() and id = ?""", (l, n) -> compte(l), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Compte bancaire introuvable"));
    }

    private List<CompteBancaireVue> comptes(boolean actifsSeulement) {
        return jdbc.query("""
                select id, banque, titulaire, numero, code_swift, agence, actif from compte_bancaire
                where tenant_id = tenant_courant() and (actif or not ?) order by actif desc, banque, numero""",
                (l, n) -> compte(l), actifsSeulement);
    }

    private static CompteBancaireVue compte(java.sql.ResultSet l) throws java.sql.SQLException {
        return new CompteBancaireVue(l.getObject(1, UUID.class), l.getString(2), l.getString(3), l.getString(4),
                l.getString(5), l.getString(6), l.getBoolean(7));
    }

    private Parametres parametres() {
        return jdbc.query("""
                select array_to_string(moyens_guichet, ','), mobile_money_parents, array_to_string(operateurs, ','),
                       virement_parents, consigne_virement
                from parametres_paiement where tenant_id = tenant_courant()""",
                (l, n) -> new Parametres(moyens(l.getString(1)), l.getBoolean(2), moyens(l.getString(3)),
                        l.getBoolean(4), l.getString(5)))
                .stream().findFirst().orElse(DEFAUT);
    }

    private boolean mobileMoneyDisponible() {
        UUID ecole = UtilisateurConnecte.etablissementActif();
        if (!modules.actif(ecole, Module.MOBILE_MONEY)) {
            return false;
        }
        DisponibiliteMobileMoney d = mobileMoney.getIfAvailable();
        return d != null && d.configure();
    }

    private void exigerNumeroLibre(String numero, UUID id) {
        Integer n = jdbc.queryForObject("""
                select count(*) from compte_bancaire where tenant_id = tenant_courant() and numero = ? and id <> ?""",
                Integer.class, numero, id);
        if (n != null && n > 0) {
            throw new RegleMetierException("COMPTE_EXISTANT", "Ce numéro de compte est déjà enregistré");
        }
    }

    private static DonneesCompte valider(DonneesCompte d) {
        if (d == null) {
            throw new IllegalArgumentException("Données du compte manquantes");
        }
        String banque = vide(d.banque());
        String titulaire = vide(d.titulaire());
        String numero = d.numero() == null ? null : d.numero().trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (banque == null || banque.length() > 120) {
            throw new IllegalArgumentException("Banque : obligatoire, 120 caractères au plus");
        }
        if (titulaire == null || titulaire.length() > 150) {
            throw new IllegalArgumentException("Titulaire du compte : obligatoire, 150 caractères au plus");
        }
        if (numero == null || numero.isEmpty() || numero.length() > 60 || !numero.matches("[A-Z0-9 -]+")) {
            throw new IllegalArgumentException("Numéro de compte (RIB ou IBAN) : lettres et chiffres, 60 caractères au plus");
        }
        String swift = d.codeSwift() == null || d.codeSwift().isBlank() ? null : d.codeSwift().trim().toUpperCase(Locale.ROOT);
        if (swift != null && !swift.matches("[A-Z0-9]{8}([A-Z0-9]{3})?")) {
            throw new IllegalArgumentException("Code SWIFT (BIC) : 8 ou 11 lettres et chiffres");
        }
        String agence = vide(d.agence());
        if (agence != null && agence.length() > 120) {
            throw new IllegalArgumentException("Agence : 120 caractères au plus");
        }
        return new DonneesCompte(banque, titulaire, numero, swift, agence, d.actif());
    }

    private static List<MoyenPaiement> moyens(String liste) {
        if (liste == null || liste.isBlank()) {
            return List.of();
        }
        return Arrays.stream(liste.split(",")).map(MoyenPaiement::valueOf).sorted().toList();
    }

    private static String liste(List<MoyenPaiement> moyens) {
        return moyens.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /** Pour le journal d'audit : les 4 derniers caractères seulement. */
    static String masquer(String numero) {
        return numero.length() <= 4 ? "****" : "…" + numero.substring(numero.length() - 4);
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
