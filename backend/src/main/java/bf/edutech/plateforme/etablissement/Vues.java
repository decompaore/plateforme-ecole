package bf.edutech.plateforme.etablissement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Objets de lecture exposés par l'API du module Établissement. */
public final class Vues {

    private Vues() {
    }

    public record AnneeVue(UUID id, String libelle, LocalDate debut, LocalDate fin, EtatAnnee etat) {

        static AnneeVue depuis(AnneeScolaire a) {
            return new AnneeVue(a.getId(), a.getLibelle(), a.getDebut(), a.getFin(), a.getEtat());
        }
    }

    public record PeriodeVue(UUID id, UUID anneeId, UUID profilId, String libelle, int ordre, LocalDate debut,
            LocalDate fin, boolean verrouillee) {

        static PeriodeVue depuis(Periode p) {
            return new PeriodeVue(p.getId(), p.getAnneeId(), p.getProfilId(), p.getLibelle(), p.getOrdre(),
                    p.getDebut(), p.getFin(), p.isVerrouillee());
        }
    }

    public record FiliereVue(UUID id, String code, String libelle, String cycle, String diplomeVise, UUID profilId) {

        static FiliereVue depuis(Filiere f) {
            return new FiliereVue(f.getId(), f.getCode(), f.getLibelle(), f.getCycle(), f.getDiplomeVise(),
                    f.getProfilId());
        }
    }

    public record MatiereVue(UUID id, String code, String libelle, TypeMatiere type, boolean actif) {

        static MatiereVue depuis(Matiere m) {
            return new MatiereVue(m.getId(), m.getCode(), m.getLibelle(), m.getType(), m.isActif());
        }
    }

    public record ClasseVue(UUID id, UUID anneeId, UUID filiereId, String filiereCode, UUID profilId, String code,
            String niveau, Short effectifMax) {
    }

    public record MatiereDeClasseVue(UUID id, UUID matiereId, String matiereCode, String matiereLibelle,
            TypeMatiere type, BigDecimal coefficient, String groupe, BigDecimal volumeHebdo, BigDecimal volumeTotal,
            UUID engagementId) {
    }

    /**
     * Matière d'une classe avec son enseignant (engagement), pour les charges
     * horaires et la liste des matières sans enseignant.
     */
    public record AffectationVue(UUID classeId, String classeCode, UUID matiereId, String matiereCode,
            String matiereLibelle, BigDecimal volumeHebdo, BigDecimal volumeTotal, UUID engagementId) {
    }

    /** Résultat de la copie d'une année vers l'année suivante. */
    public record ResultatCopie(AnneeVue annee, int classesCopiees, int matieresCopiees, int periodesCopiees) {
    }
}
