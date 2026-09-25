package bf.edutech.plateforme.socle.securite;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import bf.edutech.plateforme.socle.tenant.TenantFilter;

/**
 * Configuration de la sécurité HTTP.
 * <ul>
 * <li>API sans session : chaque requête porte un jeton d'accès JWT (15 min).</li>
 * <li>Le jeton est signé par l'API elle-même (HMAC-SHA256) et vérifié ici.</li>
 * <li>L'établissement actif est extrait du jeton par {@link TenantFilter}.</li>
 * <li>Les rôles du jeton deviennent des autorités ROLE_xxx ; le type de jeton
 * (ACCES ou SELECTION) devient l'autorité TYPE_xxx.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecuriteConfig {

    public static final String CLAIM_TYPE = "typ_jeton";
    public static final String CLAIM_ROLES = "roles";
    public static final String TYPE_ACCES = "ACCES";
    public static final String TYPE_SELECTION = "SELECTION";
    public static final String CLAIM_MOT_DE_PASSE_A_CHANGER = "mdp_a_changer";

    @Bean
    SecretKey cleSignatureJwt(SecuriteProperties proprietes) {
        byte[] octets = Base64.getDecoder().decode(proprietes.jwtSecret());
        if (octets.length < 32) {
            throw new IllegalStateException("Le secret JWT doit faire au moins 32 octets");
        }
        return new SecretKeySpec(octets, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey cleSignatureJwt) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(cleSignatureJwt));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey cleSignatureJwt, SecuriteProperties proprietes) {
        NimbusJwtDecoder decodeur = NimbusJwtDecoder.withSecretKey(cleSignatureJwt)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decodeur.setJwtValidator(JwtValidators.createDefaultWithIssuer(proprietes.emetteur()));
        return decodeur;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain chaineDeSecurite(HttpSecurity http, SecuriteProperties proprietes) throws Exception {
        http
            // API sans session ni formulaire : pas de CSRF. Le cookie de rafraîchissement
            // est HttpOnly, SameSite=Strict et limité au chemin /api/v1/auth.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/connexion", "/api/v1/auth/rafraichir", "/api/v1/auth/deconnexion")
                    .permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/auth/etablissement")
                    .hasAnyAuthority("TYPE_" + TYPE_SELECTION, "TYPE_" + TYPE_ACCES)
                .requestMatchers("/api/v1/plateforme/**").hasAuthority("ROLE_SUPER_ADMIN")
                .anyRequest().hasAuthority("TYPE_" + TYPE_ACCES))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(convertisseurJwt())))
            .addFilterAfter(new TenantFilter(proprietes.verifierSousDomaine()), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new MotDePasseTemporaireFilter(), TenantFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecuriteProperties proprietes) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(proprietes.originesAutorisees());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Transforme les revendications du jeton en autorités Spring Security. */
    static Converter<Jwt, AbstractAuthenticationToken> convertisseurJwt() {
        return jwt -> {
            List<GrantedAuthority> autorites = new ArrayList<>();
            String type = jwt.getClaimAsString(CLAIM_TYPE);
            if (type != null) {
                autorites.add(new SimpleGrantedAuthority("TYPE_" + type));
            }
            List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
            if (roles != null) {
                roles.forEach(role -> autorites.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            return new JwtAuthenticationToken(jwt, autorites, jwt.getSubject());
        };
    }
}
