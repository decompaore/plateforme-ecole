package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.UUID;

/** Établissement auquel un compte a accès, avec ses rôles dans cet établissement. */
public record EtablissementAccessible(UUID id, String code, String nom, List<String> roles) {
}
