package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.evaluations.ContexteClasse.Contexte;
import bf.edutech.plateforme.evaluations.Vues.AlerteVue;
import bf.edutech.plateforme.evaluations.Vues.ModuleVue;
import bf.edutech.plateforme.evaluations.Vues.MoyenneGroupeVue;
import bf.edutech.plateforme.evaluations.Vues.MoyenneMatiereVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatEleveVue;
import bf.edutech.plateforme.pedagogie.ProfilVue;

/**
 * Assemblage commun des stratégies : notes (moyennes, groupes, notes
 * éliminatoires, rangs) et/ou compétences (modules, taux de maîtrise).
 */
final class CalculPeriode {

    /** Ce que la stratégie active. */
    record Options(boolean notes, boolean groupes, boolean eliminatoires, boolean competences) {
    }

    private CalculPeriode() {
    }

    static ResultatsCalcules calculer(DonneesCalcul d, Options options) {
        ProfilVue profil = d.profil();
        List<MatiereDeClasseVue> matieresANotes = options.notes()
                ? d.matieres().stream().filter(m -> !Contexte.estModule(m)).toList() : List.of();
        List<MatiereDeClasseVue> modules = options.competences()
                ? d.matieres().stream().filter(Contexte::estModule).toList() : List.of();

        Map<UUID, Map<UUID, CalculNotes.MoyenneBrute>> moyennes = options.notes()
                ? CalculNotes.moyennesParMatiere(d) : Map.of();
        Map<UUID, List<ModuleVue>> modulesParEleve = options.competences()
                ? CalculCompetences.modules(d, modules) : Map.of();

        // 1. Calcul par élève
        Map<UUID, List<MoyenneMatiereVue>> lignesParEleve = new LinkedHashMap<>();
        Map<UUID, BigDecimal> moyenneParEleve = new HashMap<>();
        Map<UUID, List<MoyenneGroupeVue>> groupesParEleve = new HashMap<>();
        Map<UUID, List<String>> eliminatoiresParEleve = new HashMap<>();
        for (InscriptionVue eleve : d.eleves()) {
            if (!options.notes() || matieresANotes.isEmpty()) {
                continue;
            }
            List<MoyenneMatiereVue> lignes = CalculNotes.lignesMatieres(matieresANotes,
                    moyennes.getOrDefault(eleve.id(), Map.of()));
            moyenneParEleve.put(eleve.id(),
                    CalculNotes.arrondi(CalculNotes.moyennePonderee(lignes), profil.decimalesMoyenne()));
            if (options.groupes()) {
                groupesParEleve.put(eleve.id(), CalculNotes.groupes(lignes, profil.decimalesMoyenne()));
            }
            if (options.eliminatoires()) {
                eliminatoiresParEleve.put(eleve.id(),
                        CalculNotes.eliminatoires(matieresANotes, lignes, profil.noteEliminatoire()));
            }
            lignesParEleve.put(eleve.id(), CalculNotes.arrondirLignes(lignes));
        }

        // 2. Rangs (général et par matière)
        Map<UUID, Integer> rangs = CalculNotes.rangs(moyenneParEleve);
        Map<UUID, Map<UUID, Integer>> rangsMatieres = CalculNotes.rangsParMatiere(lignesParEleve);

        // 3. Résultats
        BigDecimal seuilMaitrise = CalculCompetences.seuil(d);
        List<ResultatEleveVue> eleves = new ArrayList<>();
        for (InscriptionVue eleve : d.eleves()) {
            BigDecimal moyenne = moyenneParEleve.get(eleve.id());
            List<String> eliminatoires = eliminatoiresParEleve.getOrDefault(eleve.id(), List.of());
            List<ModuleVue> modulesEleve = modulesParEleve.getOrDefault(eleve.id(), List.of());
            BigDecimal taux = options.competences() ? CalculCompetences.tauxGlobal(modulesEleve) : null;

            boolean admis = true;
            if (options.notes() && !matieresANotes.isEmpty()) {
                admis = moyenne != null && moyenne.compareTo(profil.seuilAdmission()) >= 0 && eliminatoires.isEmpty();
            }
            if (options.competences() && !modules.isEmpty()) {
                admis = admis && taux != null && taux.compareTo(seuilMaitrise) >= 0;
            }
            Map<UUID, Integer> sesRangs = new HashMap<>();
            rangsMatieres.forEach((matiere, parEleve) -> {
                Integer r = parEleve.get(eleve.id());
                if (r != null) {
                    sesRangs.put(matiere, r);
                }
            });
            List<MoyenneMatiereVue> matieres = lignesParEleve.getOrDefault(eleve.id(), List.of()).stream()
                    .map(l -> new MoyenneMatiereVue(l.matiereId(), l.code(), l.libelle(), l.groupe(), l.coefficient(),
                            l.moyenne(), l.points(), l.notes(), sesRangs.get(l.matiereId()), l.sansNote()))
                    .toList();
            eleves.add(new ResultatEleveVue(eleve.id(), eleve.matricule(), eleve.nom(), eleve.prenoms(), moyenne,
                    rangs.get(eleve.id()), admis, taux, eliminatoires, matieres,
                    groupesParEleve.getOrDefault(eleve.id(), List.of()), modulesEleve));
        }
        eleves.sort(Comparator.comparing((ResultatEleveVue r) -> r.rang() == null ? Integer.MAX_VALUE : r.rang())
                .thenComparing(ResultatEleveVue::nom).thenComparing(ResultatEleveVue::prenoms));

        List<AlerteVue> alertes = new ArrayList<>();
        if (options.notes()) {
            alertes.addAll(CalculNotes.alertes(d, matieresANotes));
        }
        if (options.competences()) {
            alertes.addAll(CalculCompetences.alertes(d, modules));
        }
        return new ResultatsCalcules(eleves, alertes);
    }
}
