package bf.edutech.plateforme.socle.persistance;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Page de résultats exposée par l'API (format JSON stable, indépendant de Spring Data).
 */
public record PageResultat<T>(List<T> elements, int page, int taille, long total, int nombrePages) {

    public static <E, T> PageResultat<T> depuis(Page<E> page, Function<E, T> conversion) {
        return new PageResultat<>(page.getContent().stream().map(conversion).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
