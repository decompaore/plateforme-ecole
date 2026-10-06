package bf.edutech.plateforme.eleves;

import static bf.edutech.plateforme.eleves.FormatImport.BOURSE;
import static bf.edutech.plateforme.eleves.FormatImport.CLASSE;
import static bf.edutech.plateforme.eleves.FormatImport.LIEU_NAISSANCE;
import static bf.edutech.plateforme.eleves.FormatImport.MATRICULE;
import static bf.edutech.plateforme.eleves.FormatImport.NOM;
import static bf.edutech.plateforme.eleves.FormatImport.PARENT_LIEN;
import static bf.edutech.plateforme.eleves.FormatImport.PARENT_NOM;
import static bf.edutech.plateforme.eleves.FormatImport.PARENT_PRENOMS;
import static bf.edutech.plateforme.eleves.FormatImport.PARENT_TELEPHONE;
import static bf.edutech.plateforme.eleves.FormatImport.PRENOMS;
import static bf.edutech.plateforme.eleves.FormatImport.REDOUBLANT;
import static bf.edutech.plateforme.eleves.FormatImport.SEXE;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.Eleve.IdentiteEleve;
import bf.edutech.plateforme.eleves.LecteurExcel.LigneLue;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.referentiel.Sexe;

/**
 * Import des élèves d'une année depuis un fichier Excel.
 * <p>
 * Toutes les lignes sont d'abord contrôlées (rapport ligne par ligne). En
 * simulation, rien n'est enregistré. À l'import réel, les lignes valides sont
 * enregistrées en une seule transaction ; les lignes en erreur sont ignorées
 * et restent dans le rapport pour être corrigées et réimportées.
 */
@Service
public class ImportElevesService {

    /** Erreurs d'une ligne du fichier (numéro de ligne Excel). */
    public record LigneRapport(int ligne, String nomComplet, List<String> erreurs) {
    }

    public record RapportImport(boolean simulation, int lignes, int valides, int importees,
            List<LigneRapport> erreurs) {
    }

    /** Fichier à télécharger. */
    public record Fichier(String nom, byte[] contenu) {
    }

    /** Ligne contrôlée, prête à être enregistrée. */
    private record LignePrete(DonneesEleve eleve, Eleve eleveExistant, DonneesResponsable responsable,
            ClasseVue classe, boolean redoublant, StatutBourse bourse) {
    }

    private final EleveRepository eleves;
    private final InscriptionRepository inscriptions;
    private final LienResponsableEleveRepository liens;
    private final ResponsableRepository responsables;
    private final RegistreEleves registreEleves;
    private final RegistreInscriptions registreInscriptions;
    private final ClassesService classes;
    private final AuditService audit;

    ImportElevesService(EleveRepository eleves, InscriptionRepository inscriptions,
            LienResponsableEleveRepository liens, ResponsableRepository responsables, RegistreEleves registreEleves,
            RegistreInscriptions registreInscriptions, ClassesService classes, AuditService audit) {
        this.eleves = eleves;
        this.inscriptions = inscriptions;
        this.liens = liens;
        this.responsables = responsables;
        this.registreEleves = registreEleves;
        this.registreInscriptions = registreInscriptions;
        this.classes = classes;
        this.audit = audit;
    }

    /** Modèle Excel pré-rempli avec les classes de l'année. */
    @Transactional(readOnly = true)
    public Fichier modele(UUID anneeId) {
        AnneeVue annee = registreInscriptions.libelles().annee(anneeId);
        return new Fichier("modele-import-eleves-" + annee.libelle() + ".xlsx",
                ModeleImport.generer(classes.lister(anneeId)));
    }

    @Transactional
    public RapportImport importer(UUID anneeId, InputStream flux, boolean simulation) {
        AnneeVue annee = registreInscriptions.anneeOuverte(anneeId);
        Map<String, ClasseVue> classesParCode = classes.lister(anneeId).stream()
                .collect(Collectors.toMap(c -> cle(c.code()), Function.identity(), (a, b) -> a));
        List<LigneLue> lignes = LecteurExcel.lire(flux);

        Controle controle = new Controle(annee, classesParCode);
        List<LignePrete> pretes = new ArrayList<>();
        List<LigneRapport> erreurs = new ArrayList<>();
        for (LigneLue ligne : lignes) {
            List<String> messages = new ArrayList<>();
            LignePrete prete = controle.verifier(ligne, messages);
            if (messages.isEmpty()) {
                pretes.add(prete);
            } else {
                erreurs.add(new LigneRapport(ligne.numero(), nomComplet(ligne), List.copyOf(messages)));
            }
        }

        int importees = 0;
        if (!simulation) {
            // Les élèves avec un matricule saisi d'abord : les matricules générés ensuite les évitent
            List<LignePrete> ordre = new ArrayList<>(pretes);
            ordre.sort(Comparator.comparing(l -> RegistreEleves.vide(l.eleve().matricule())));
            for (LignePrete ligne : ordre) {
                Eleve eleve = ligne.eleveExistant() != null ? ligne.eleveExistant() : registreEleves.creer(ligne.eleve());
                if (ligne.responsable() != null && !dejaRattache(eleve, ligne.responsable())) {
                    registreEleves.rattacher(eleve, ligne.responsable());
                }
                registreInscriptions.creer(eleve.getId(), ligne.classe(), ligne.redoublant(), ligne.bourse(), null);
                importees++;
            }
            audit.enregistrer("IMPORT_ELEVES", annee.libelle(),
                    Map.of("lignes", lignes.size(), "importees", importees, "erreurs", erreurs.size()));
        }
        return new RapportImport(simulation, lignes.size(), pretes.size(), importees, erreurs);
    }

    private boolean dejaRattache(Eleve eleve, DonneesResponsable responsable) {
        return responsables.findByTelephone(registreEleves.telephone(responsable.telephone()))
                .map(r -> liens.existsByEleveIdAndResponsableId(eleve.getId(), r.getId()))
                .orElse(false);
    }

    /** Contrôles d'une ligne, avec la mémoire du fichier (doublons, places déjà prises). */
    private final class Controle {

        private final AnneeVue annee;
        private final Map<String, ClasseVue> classesParCode;
        private final Map<String, Integer> matriculesVus = new HashMap<>();
        private final Map<String, Integer> identitesVues = new HashMap<>();
        private final Map<UUID, Long> placesRestantes = new HashMap<>();

        Controle(AnneeVue annee, Map<String, ClasseVue> classesParCode) {
            this.annee = annee;
            this.classesParCode = classesParCode;
        }

        LignePrete verifier(LigneLue ligne, List<String> erreurs) {
            // Identité
            Sexe sexe = sexe(ligne.valeur(SEXE), erreurs);
            if (RegistreEleves.vide(ligne.valeur(NOM))) {
                erreurs.add("Nom obligatoire");
            }
            if (RegistreEleves.vide(ligne.valeur(PRENOMS))) {
                erreurs.add("Prénoms obligatoires");
            }
            if (ligne.date() == null) {
                erreurs.add(ligne.valeur(FormatImport.DATE_NAISSANCE) == null ? "Date de naissance obligatoire"
                        : "Date de naissance illisible : « " + ligne.valeur(FormatImport.DATE_NAISSANCE)
                                + " » (attendu jj/mm/aaaa)");
            }
            DonneesEleve donnees = new DonneesEleve(ligne.valeur(MATRICULE), ligne.valeur(NOM),
                    ligne.valeur(PRENOMS), sexe, ligne.date(), ligne.valeur(LIEU_NAISSANCE), null, null, null);
            IdentiteEleve identite = null;
            if (erreurs.isEmpty()) {
                try {
                    identite = registreEleves.normaliser(donnees);
                } catch (IllegalArgumentException e) {
                    erreurs.add(e.getMessage());
                }
            }

            // Élève déjà connu (matricule) ou nouveau (doublons)
            Eleve existant = identite == null ? null : dossierExistant(ligne, donnees, identite, erreurs);

            // Classe, redoublement, bourse
            boolean redoublant = ouiNon(ligne.valeur(REDOUBLANT), erreurs);
            StatutBourse bourse = bourse(ligne.valeur(BOURSE), erreurs);
            ClasseVue classe = null;
            if (RegistreEleves.vide(ligne.valeur(CLASSE))) {
                erreurs.add("Classe obligatoire");
            } else {
                classe = classesParCode.get(cle(ligne.valeur(CLASSE)));
                if (classe == null) {
                    erreurs.add("Classe « " + ligne.valeur(CLASSE) + " » inconnue pour " + annee.libelle());
                }
            }

            DonneesResponsable responsable = responsable(ligne, erreurs);

            // Places : décomptées seulement pour une ligne par ailleurs valide
            if (erreurs.isEmpty()) {
                ClasseVue c = classe;
                long places = placesRestantes.computeIfAbsent(c.id(), id -> registreInscriptions.placesRestantes(c));
                if (places <= 0) {
                    erreurs.add("Classe " + c.code() + " complète (" + c.effectifMax() + " places)");
                } else {
                    placesRestantes.put(c.id(), places - 1);
                }
            }
            return new LignePrete(donnees, existant, responsable, classe, redoublant, bourse);
        }

        private Eleve dossierExistant(LigneLue ligne, DonneesEleve donnees, IdentiteEleve identite,
                List<String> erreurs) {
            if (!RegistreEleves.vide(donnees.matricule())) {
                String matricule;
                try {
                    matricule = registreEleves.matriculeValide(donnees.matricule());
                } catch (IllegalArgumentException e) {
                    erreurs.add(e.getMessage());
                    return null;
                }
                Integer premiere = matriculesVus.putIfAbsent(matricule, ligne.numero());
                if (premiere != null) {
                    erreurs.add("Matricule " + matricule + " déjà présent ligne " + premiere);
                    return null;
                }
                Optional<Eleve> eleve = eleves.findByMatricule(matricule);
                if (eleve.isEmpty()) {
                    return null; // nouvel élève avec un matricule attribué par l'établissement
                }
                if (!eleve.get().getNom().equalsIgnoreCase(identite.nom())) {
                    erreurs.add("Le matricule " + matricule + " appartient à " + eleve.get().nomComplet());
                } else if (inscriptions.existsByEleveIdAndAnneeId(eleve.get().getId(), annee.id())) {
                    erreurs.add("Élève déjà inscrit pour " + annee.libelle());
                }
                return eleve.get();
            }
            String cleIdentite = cle(identite.nom() + "|" + identite.prenoms()) + "|" + identite.dateNaissance();
            Integer premiere = identitesVues.putIfAbsent(cleIdentite, ligne.numero());
            if (premiere != null) {
                erreurs.add("Même élève que ligne " + premiere);
                return null;
            }
            eleves.findFirstByNomIgnoreCaseAndPrenomsIgnoreCaseAndDateNaissance(identite.nom(), identite.prenoms(),
                    identite.dateNaissance())
                    .ifPresent(e -> erreurs.add("Un élève de même nom né le même jour existe déjà (matricule "
                            + e.getMatricule() + ") : indiquez son matricule"));
            return null;
        }

        private DonneesResponsable responsable(LigneLue ligne, List<String> erreurs) {
            String nom = ligne.valeur(PARENT_NOM);
            String prenoms = ligne.valeur(PARENT_PRENOMS);
            String telephone = ligne.valeur(PARENT_TELEPHONE);
            String lien = ligne.valeur(PARENT_LIEN);
            if (nom == null && prenoms == null && telephone == null && lien == null) {
                return null;
            }
            int avant = erreurs.size();
            if (nom == null) {
                erreurs.add("Nom du parent obligatoire");
            }
            if (prenoms == null) {
                erreurs.add("Prénoms du parent obligatoires");
            }
            if (telephone == null) {
                erreurs.add("Téléphone du parent obligatoire");
            } else {
                try {
                    registreEleves.telephone(telephone);
                } catch (IllegalArgumentException e) {
                    erreurs.add("Téléphone du parent invalide : " + telephone);
                }
            }
            LienParente lienParente = lien(lien, erreurs);
            if (erreurs.size() > avant) {
                return null;
            }
            try {
                RegistreEleves.obligatoire(nom, "Le nom du parent", 80);
                RegistreEleves.obligatoire(prenoms, "Les prénoms du parent", 120);
            } catch (IllegalArgumentException e) {
                erreurs.add(e.getMessage());
                return null;
            }
            return new DonneesResponsable(nom, prenoms, telephone, lienParente, null, null, true, null);
        }
    }

    private static Sexe sexe(String valeur, List<String> erreurs) {
        if (valeur == null) {
            erreurs.add("Sexe obligatoire (M ou F)");
            return null;
        }
        return switch (cle(valeur)) {
            case "M", "MASCULIN", "G", "GARCON", "H", "HOMME" -> Sexe.M;
            case "F", "FEMININ", "FILLE", "FEMME" -> Sexe.F;
            default -> {
                erreurs.add("Sexe invalide : « " + valeur + " » (M ou F)");
                yield null;
            }
        };
    }

    private static boolean ouiNon(String valeur, List<String> erreurs) {
        if (valeur == null) {
            return false;
        }
        return switch (cle(valeur)) {
            case "O", "OUI", "Y", "YES", "1" -> true;
            case "N", "NON", "NO", "0" -> false;
            default -> {
                erreurs.add("Redoublant invalide : « " + valeur + " » (O ou N)");
                yield false;
            }
        };
    }

    private static StatutBourse bourse(String valeur, List<String> erreurs) {
        if (valeur == null) {
            return StatutBourse.NON_BOURSIER;
        }
        return switch (cle(valeur).replace('-', '_').replace(' ', '_')) {
            case "B", "BOURSIER" -> StatutBourse.BOURSIER;
            case "SB", "SEMI_BOURSIER", "DEMI_BOURSIER" -> StatutBourse.SEMI_BOURSIER;
            case "NB", "NON_BOURSIER" -> StatutBourse.NON_BOURSIER;
            default -> {
                erreurs.add("Bourse invalide : « " + valeur + " » (B, SB ou NB)");
                yield StatutBourse.NON_BOURSIER;
            }
        };
    }

    private static LienParente lien(String valeur, List<String> erreurs) {
        if (valeur == null) {
            return LienParente.AUTRE;
        }
        return switch (cle(valeur)) {
            case "PERE", "P" -> LienParente.PERE;
            case "MERE", "M" -> LienParente.MERE;
            case "TUTEUR", "TUTRICE", "T" -> LienParente.TUTEUR;
            case "AUTRE", "A" -> LienParente.AUTRE;
            default -> {
                erreurs.add("Lien invalide : « " + valeur + " » (PERE, MERE, TUTEUR ou AUTRE)");
                yield LienParente.AUTRE;
            }
        };
    }

    /** Majuscules sans accents ni espaces superflus, pour comparer des saisies. */
    static String cle(String valeur) {
        String sansAccents = Normalizer.normalize(valeur.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sansAccents.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String nomComplet(LigneLue ligne) {
        String nom = ligne.valeur(NOM);
        String prenoms = ligne.valeur(PRENOMS);
        return ((nom != null ? nom.toUpperCase(Locale.ROOT) : "") + " " + (prenoms != null ? prenoms : "")).trim();
    }
}
