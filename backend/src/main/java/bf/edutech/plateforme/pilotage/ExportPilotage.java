package bf.edutech.plateforme.pilotage;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import bf.edutech.plateforme.pilotage.Vues.ExamenVue;
import bf.edutech.plateforme.pilotage.Vues.Indicateurs;
import bf.edutech.plateforme.pilotage.Vues.LigneDirection;
import bf.edutech.plateforme.pilotage.Vues.LigneEtablissement;
import bf.edutech.plateforme.pilotage.Vues.NiveauVue;
import bf.edutech.plateforme.pilotage.Vues.PeriodeVue;
import bf.edutech.plateforme.pilotage.Vues.TableauPilotage;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;

/** Tableaux d'export du pilotage : uniquement des nombres. */
final class ExportPilotage {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ExportPilotage() {
    }

    static String nomFichier(TableauPilotage t) {
        String nom = t.perimetre().nom().toLowerCase(Locale.ROOT)
                .replaceAll("[àâä]", "a").replaceAll("[éèêë]", "e").replaceAll("[îï]", "i").replaceAll("[ôö]", "o")
                .replaceAll("[ùûü]", "u").replace("ç", "c").replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return "pilotage-" + nom + (t.annee() == null ? "" : "-" + t.annee());
    }

    static Tableau[] tableaux(TableauPilotage t) {
        String sousTitre = (t.annee() == null ? "Aucune année scolaire" : "Année scolaire " + t.annee())
                + " · " + t.synthese().etablissements() + " établissement(s)";
        List<String> examens = examens(t);
        List<Tableau> r = new ArrayList<>();

        // Par niveau (synthèse du périmètre)
        Tableau niveaux = new Tableau("Niveaux", "Effectifs et résultats de fin d'année par niveau — " + t.perimetre().nom())
                .sousTitre(sousTitre)
                .colonnes(Colonne.texte("Niveau", 2), Colonne.nombre("Classes", 1), Colonne.nombre("Garçons", 1),
                        Colonne.nombre("Filles", 1), Colonne.nombre("Total", 1), Colonne.nombre("Redoublants", 1.2f),
                        Colonne.nombre("Décidés", 1), Colonne.nombre("Admis", 1), Colonne.texte("Taux d'admission", 1.3f));
        for (NiveauVue n : t.synthese().niveaux()) {
            niveaux.ligne(n.niveau(), n.classes(), n.eleves().garcons(), n.eleves().filles(), n.eleves().total(),
                    n.redoublants().total(), n.decides().total(), n.admis().total(), pourcent(n.tauxAdmission()));
        }
        Indicateurs s = t.synthese();
        niveaux.total("Total", s.classes(), s.eleves().garcons(), s.eleves().filles(), s.eleves().total(),
                s.redoublants().total(), s.decides().total(), s.admis().total(), pourcent(s.tauxAdmission()));
        niveaux.note("Décisions de fin d'année validées par les conseils de classe. Admis : passage en classe supérieure ou certification.");
        r.add(niveaux);

        // Examens de fin d'études
        if (!s.examens().isEmpty()) {
            Tableau ex = new Tableau("Examens", "Examens de fin d'études — " + t.perimetre().nom()).sousTitre(sousTitre)
                    .colonnes(Colonne.texte("Examen", 2), Colonne.nombre("Candidats", 1), Colonne.nombre("Résultats connus", 1.3f),
                            Colonne.nombre("Admis", 1), Colonne.texte("Taux", 1), Colonne.texte("Taux garçons", 1.1f),
                            Colonne.texte("Taux filles", 1.1f));
            for (ExamenVue e : s.examens()) {
                ex.ligne(e.examen(), e.candidats().total(), e.resultats().total(), e.admis().total(), pourcent(e.taux()),
                        pourcent(e.tauxGarcons()), pourcent(e.tauxFilles()));
            }
            ex.note("Taux de réussite = admis / résultats connus.");
            r.add(ex);
        }

        // Moyennes par période
        if (!s.periodes().isEmpty()) {
            Tableau pe = new Tableau("Périodes", "Moyennes des bulletins publiés — " + t.perimetre().nom()).sousTitre(sousTitre)
                    .colonnes(Colonne.texte("Période", 2), Colonne.nombre("Bulletins", 1), Colonne.texte("Moyenne", 1),
                            Colonne.nombre("À la moyenne", 1.2f), Colonne.texte("Taux", 1));
            for (PeriodeVue p : s.periodes()) {
                pe.ligne(p.libelle(), p.bulletins().total(), p.moyenne() == null ? "—" : p.moyenne().toPlainString(),
                        p.admis().total(), pourcent(p.taux()));
            }
            r.add(pe);
        }

        // Directions
        if (!t.directions().isEmpty()) {
            Tableau d = new Tableau("Directions", (t.niveauDirections() == null ? "Directions" : t.niveauDirections())
                    + " — " + t.perimetre().nom()).sousTitre(sousTitre);
            ajouterColonnes(d, "Direction", examens);
            for (LigneDirection l : t.directions()) {
                d.ligne(ligne(l.nom(), null, l.indicateurs(), examens, true));
            }
            d.total(ligne("Total", null, s, examens, true));
            r.add(d);
        }

        // Établissements
        Tableau e = new Tableau("Établissements", "Établissements — " + t.perimetre().nom()).sousTitre(sousTitre);
        ajouterColonnes(e, "Établissement", examens);
        for (LigneEtablissement l : t.etablissements()) {
            e.ligne(ligne(l.nom() + ("SUSPENDU".equals(l.statut()) ? " (suspendu)" : ""), l.direction(), l.indicateurs(),
                    examens, false));
        }
        e.total(ligne("Total", "", s, examens, false));
        e.note("Utilisation : personnes ayant ouvert l'application du " + JOUR.format(t.debutUtilisation()) + " au "
                + JOUR.format(t.finUtilisation()) + ".");
        r.add(e);
        return r.toArray(Tableau[]::new);
    }

    private static List<String> examens(TableauPilotage t) {
        Set<String> noms = new LinkedHashSet<>();
        t.synthese().examens().forEach(x -> noms.add(x.examen()));
        return List.copyOf(noms);
    }

    private static void ajouterColonnes(Tableau t, String premiere, List<String> examens) {
        List<Colonne> cs = new ArrayList<>();
        cs.add(Colonne.texte(premiere, 3));
        if (premiere.equals("Établissement")) {
            cs.add(Colonne.texte("Rattachement", 2.5f));
        }
        cs.add(Colonne.nombre("Élèves", 1));
        cs.add(Colonne.nombre("dont filles", 1));
        cs.add(Colonne.nombre("Classes", 1));
        cs.add(Colonne.nombre("Enseignants", 1.1f));
        cs.add(Colonne.texte("Élèves / ens.", 1));
        cs.add(Colonne.texte("Admission", 1));
        for (String x : examens) {
            cs.add(Colonne.texte(x, 1));
        }
        cs.add(Colonne.texte("Utilisation", 1.1f));
        t.colonnes(cs.toArray(Colonne[]::new));
    }

    private static Object[] ligne(String nom, String rattachement, Indicateurs i, List<String> examens, boolean direction) {
        List<Object> v = new ArrayList<>();
        v.add(nom);
        if (!direction) {
            v.add(rattachement == null ? "" : rattachement);
        }
        v.add(i.eleves().total());
        v.add(i.eleves().filles());
        v.add(i.classes());
        v.add(i.enseignants().total());
        v.add(i.elevesParEnseignant() == null ? "—" : i.elevesParEnseignant().toPlainString());
        v.add(pourcent(i.tauxAdmission()));
        for (String x : examens) {
            v.add(i.examens().stream().filter(e -> e.examen().equals(x)).findFirst().map(e -> pourcent(e.taux()))
                    .orElse("—"));
        }
        v.add(i.utilisation().actifs() + " / " + i.utilisation().comptes());
        return v.toArray();
    }

    static String pourcent(BigDecimal taux) {
        return taux == null ? "—" : taux.toPlainString().replace('.', ',') + " %";
    }
}
