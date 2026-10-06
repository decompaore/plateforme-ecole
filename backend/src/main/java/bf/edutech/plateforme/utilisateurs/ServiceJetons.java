package bf.edutech.plateforme.utilisateurs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import bf.edutech.plateforme.socle.securite.SecuriteConfig;
import bf.edutech.plateforme.socle.securite.SecuriteProperties;
import bf.edutech.plateforme.socle.tenant.TenantFilter;

/**
 * Émission des jetons.
 * <ul>
 * <li>Jeton d'accès (JWT, 15 min) : compte, établissement actif, rôles.</li>
 * <li>Jeton de sélection (JWT, 5 min) : permet seulement de choisir un établissement.</li>
 * <li>Jeton de rafraîchissement (aléatoire, 30 j) : envoyé en cookie HttpOnly,
 * seul son haché est stocké.</li>
 * </ul>
 */
@Service
class ServiceJetons {

    private static final SecureRandom ALEA = new SecureRandom();

    private final JwtEncoder encodeur;
    private final SecuriteProperties proprietes;
    private final JetonRafraichissementRepository depot;
    private final Clock horloge;

    ServiceJetons(JwtEncoder encodeur, SecuriteProperties proprietes, JetonRafraichissementRepository depot,
            Clock horloge) {
        this.encodeur = encodeur;
        this.proprietes = proprietes;
        this.depot = depot;
        this.horloge = horloge;
    }

    /** Jeton d'accès ; {@code etablissement} est null pour le super administrateur. */
    String jetonAcces(Utilisateur utilisateur, EtablissementAccessible etablissement) {
        Instant maintenant = horloge.instant();
        JwtClaimsSet.Builder revendications = JwtClaimsSet.builder()
                .issuer(proprietes.emetteur())
                .subject(utilisateur.getId().toString())
                .issuedAt(maintenant)
                .expiresAt(maintenant.plus(proprietes.dureeJetonAcces()))
                .claim(SecuriteConfig.CLAIM_TYPE, SecuriteConfig.TYPE_ACCES);
        if (utilisateur.isDoitChangerMotDePasse()) {
            revendications.claim(SecuriteConfig.CLAIM_MOT_DE_PASSE_A_CHANGER, true);
        }
        if (etablissement != null) {
            revendications
                    .claim(TenantFilter.CLAIM_TENANT_ID, etablissement.id().toString())
                    .claim(TenantFilter.CLAIM_TENANT_CODE, etablissement.code())
                    .claim(SecuriteConfig.CLAIM_ROLES, etablissement.roles());
        } else if (utilisateur.isSuperAdmin()) {
            revendications.claim(SecuriteConfig.CLAIM_ROLES, List.of("SUPER_ADMIN"));
        }
        return encoder(revendications.build());
    }

    /** Jeton court, sans établissement, pour choisir parmi plusieurs établissements. */
    String jetonSelection(Utilisateur utilisateur) {
        Instant maintenant = horloge.instant();
        return encoder(JwtClaimsSet.builder()
                .issuer(proprietes.emetteur())
                .subject(utilisateur.getId().toString())
                .issuedAt(maintenant)
                .expiresAt(maintenant.plus(proprietes.dureeJetonSelection()))
                .claim(SecuriteConfig.CLAIM_TYPE, SecuriteConfig.TYPE_SELECTION)
                .build());
    }

    long dureeAccesEnSecondes() {
        return proprietes.dureeJetonAcces().toSeconds();
    }

    /** Crée un jeton de rafraîchissement ; {@code famille} null = nouvelle chaîne. */
    String nouveauJetonRafraichissement(UUID utilisateurId, UUID tenantId, UUID famille) {
        return emettreJetonRafraichissement(utilisateurId, tenantId, famille).valeur();
    }

    /** Jeton émis : identifiant en base et valeur (remise une seule fois, dans le cookie). */
    record JetonEmis(UUID id, String valeur, UUID famille) {
    }

    JetonEmis emettreJetonRafraichissement(UUID utilisateurId, UUID tenantId, UUID famille) {
        byte[] octets = new byte[32];
        ALEA.nextBytes(octets);
        String valeur = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        JetonRafraichissement jeton = depot.save(new JetonRafraichissement(utilisateurId, tenantId, hacher(valeur),
                famille != null ? famille : UUID.randomUUID(),
                horloge.instant().plus(proprietes.dureeJetonRafraichissement())));
        return new JetonEmis(jeton.getId(), valeur, jeton.getFamille());
    }

    static String hacher(String valeur) {
        try {
            byte[] empreinte = MessageDigest.getInstance("SHA-256").digest(valeur.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(empreinte);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private String encoder(JwtClaimsSet revendications) {
        JwsHeader entete = JwsHeader.with(MacAlgorithm.HS256).build();
        return encodeur.encode(JwtEncoderParameters.from(entete, revendications)).getTokenValue();
    }
}
