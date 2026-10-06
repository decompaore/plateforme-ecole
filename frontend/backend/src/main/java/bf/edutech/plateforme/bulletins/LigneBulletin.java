package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;

/** Ligne d'un bulletin (valeur), pour le PDF et l'API. */
public record LigneBulletin(int ordre, NatureLigne nature, String code, String libelle, String groupe,
        BigDecimal coefficient, BigDecimal moyenne, BigDecimal points, Integer rang, boolean sansNote,
        String appreciation, String enseignant, Integer competences, Integer acquises, BigDecimal taux,
        String statutModule) {
}
