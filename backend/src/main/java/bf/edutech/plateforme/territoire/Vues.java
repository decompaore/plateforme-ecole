package bf.edutech.plateforme.territoire;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Vues et données échangées du référentiel territorial. */
public final class Vues {

    private Vues() {
    }

    public record PaysVue(UUID id, String code, String nom, String deviseNationale, String indicatifTelephone,
            int longueurNumero, String fuseauHoraire, String monnaie, String langue, int ministeres,
            int etablissements) {
    }

    public record DonneesPays(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "Code ISO à 2 lettres (ex. BF)") String code,
            @NotBlank @Size(max = 100) String nom,
            @Size(max = 200) String deviseNationale,
            @NotBlank @Pattern(regexp = "^\\+[0-9]{1,4}$", message = "Indicatif du type +226") String indicatifTelephone,
            @Min(6) @Max(12) int longueurNumero,
            @NotBlank @Size(max = 60) String fuseauHoraire,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "Code ISO à 3 lettres (ex. XOF)") String monnaie,
            @Size(max = 10) String langue) {
    }

    public record MinistereVue(UUID id, UUID paysId, String sigle, String nom, boolean actif, List<String> niveaux,
            int directions, int etablissements) {
    }

    /** {@code niveaux} : noms des niveaux de directions, du plus haut au plus proche des établissements. */
    public record DonneesMinistere(
            @NotBlank @Size(max = 30) String sigle,
            @NotBlank @Size(max = 250) String nom,
            Boolean actif,
            @NotNull @Size(min = 1, max = 5, message = "De 1 à 5 niveaux de directions") List<@NotBlank @Size(max = 80) String> niveaux) {
    }

    public record DirectionVue(UUID id, UUID parentId, int rang, String code, String nom, boolean actif,
            int etablissements) {
    }

    public record DonneesDirection(UUID parentId,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "Code : lettres, chiffres, - _ .") String code,
            @NotBlank @Size(max = 200) String nom,
            Boolean actif) {
    }

    /**
     * Direction avec son chemin complet (« Burkina Faso · MESFPT · DR … · DP … ») ;
     * {@code terminale} : du dernier niveau de son ministère (un établissement peut s'y rattacher).
     */
    public record DirectionChemin(UUID id, UUID paysId, UUID ministereId, int rang, boolean terminale, String chemin,
            boolean actif) {
    }

    public record ErreurImport(int ligne, String message) {
    }

    public record RapportImport(boolean simulation, int lignes, int creees, int modifiees, int inchangees,
            List<ErreurImport> erreurs) {
    }

    public record DemandeImport(@NotBlank @Size(max = 2_000_000) String contenu, boolean simulation) {
    }
}
