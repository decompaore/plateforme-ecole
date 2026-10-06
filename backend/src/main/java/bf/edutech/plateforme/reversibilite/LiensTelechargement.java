package bf.edutech.plateforme.reversibilite;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.SecuriteConfig;
import bf.edutech.plateforme.socle.securite.SecuriteProperties;

/**
 * Liens de téléchargement signés : le navigateur télécharge l'archive lui-même (barre de
 * progression, reprise après une coupure), sans jeton d'accès dans l'en-tête. Le lien ne
 * vaut que pour une archive, un établissement et quelques minutes. Ce jeton ne donne accès
 * à rien d'autre : il ne porte ni rôle ni type ACCES.
 */
@Component
class LiensTelechargement {

    static final String TYPE = "TELECHARGEMENT";
    private static final String AUDIENCE = "telechargement-export";

    record Lien(UUID tenantId, UUID exportId, UUID utilisateurId) {
    }

    private final JwtEncoder encodeur;
    private final JwtDecoder decodeur;
    private final String emetteur;

    LiensTelechargement(JwtEncoder encodeur, JwtDecoder decodeur, SecuriteProperties securite) {
        this.encodeur = encodeur;
        this.decodeur = decodeur;
        this.emetteur = securite.emetteur();
    }

    String creer(UUID tenantId, UUID exportId, UUID utilisateurId, Instant expiration) {
        JwtClaimsSet revendications = JwtClaimsSet.builder()
                .issuer(emetteur)
                .audience(List.of(AUDIENCE))
                .subject(utilisateurId.toString())
                .issuedAt(Instant.now())
                .expiresAt(expiration)
                .claim(SecuriteConfig.CLAIM_TYPE, TYPE)
                .claim("etablissement", tenantId.toString())
                .claim("export", exportId.toString())
                .build();
        return encodeur.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), revendications))
                .getTokenValue();
    }

    Lien verifier(String jeton, UUID exportId) {
        if (jeton == null || jeton.isBlank()) {
            throw new AccesRefuseException("Lien de téléchargement invalide");
        }
        Jwt jwt;
        try {
            jwt = decodeur.decode(jeton);
        } catch (JwtException e) {
            throw new AccesRefuseException("Lien de téléchargement invalide ou expiré");
        }
        if (!TYPE.equals(jwt.getClaimAsString(SecuriteConfig.CLAIM_TYPE))
                || jwt.getAudience() == null || !jwt.getAudience().contains(AUDIENCE)
                || !exportId.toString().equals(jwt.getClaimAsString("export"))) {
            throw new AccesRefuseException("Lien de téléchargement invalide");
        }
        return new Lien(UUID.fromString(jwt.getClaimAsString("etablissement")), exportId,
                UUID.fromString(jwt.getSubject()));
    }
}
