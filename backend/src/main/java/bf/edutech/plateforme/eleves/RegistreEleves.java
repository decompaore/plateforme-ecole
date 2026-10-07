package bf.edutech.plateforme.eleves;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.eleves.Donnees.DonneesEleve;
import bf.edutech.plateforme.eleves.Donnees.DonneesResponsable;
import bf.edutech.plateforme.eleves.Eleve.IdentiteEleve;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.telephone.Indicatifs;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;

/**
 * Règles de création des dossiers d'élèves et de rattachement des responsables,
 * partagées par la saisie unitaire et l'import Excel.
 * <p>
 * Composant sans proxy transactionnel : il s'exécute dans la transaction du
 * service appelant.
 */
@Component
class RegistreEleves {

    private static final Pattern FORMAT_MATRICULE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/._-]{0,29}$");
    private static final LocalDate NAISSANCE_MIN = LocalDate.of(1950, 1, 2);

    private final EleveRepository eleves;
    private final ResponsableRepository responsables;
    private final LienResponsableEleveRepository liens;
    private final Compteurs compteurs;
    private final Indicatifs indicatifs;
    private final Clock horloge;

    RegistreEleves(EleveRepository eleves, ResponsableRepository responsables, LienResponsableEleveRepository liens,
            Compteurs compteurs, Indicatifs indicatifs, Clock horloge) {
        this.eleves = eleves;
        this.responsables = responsables;
        this.liens = liens;
        this.compteurs = compteurs;
        this.indicatifs = indicatifs;
        this.horloge = horloge;
    }

    /** Contrôle et nettoie l'identité saisie ; IllegalArgumentException (400) si elle est invalide. */
    IdentiteEleve normaliser(DonneesEleve d) {
        if (d.sexe() == null) {
            throw new IllegalArgumentException("Le sexe est obligatoire (M ou F)");
        }
        LocalDate naissance = d.dateNaissance();
        if (naissance == null) {
            throw new IllegalArgumentException("La date de naissance est obligatoire");
        }
        if (naissance.isBefore(NAISSANCE_MIN) || naissance.isAfter(LocalDate.now(horloge))) {
            throw new IllegalArgumentException("Date de naissance invalide : " + naissance);
        }
        String telephone = vide(d.telephone()) ? null
                : indicatifs.normaliser(d.telephone());
        return new IdentiteEleve(obligatoire(d.nom(), "Le nom", 80).toUpperCase(Locale.ROOT),
                obligatoire(d.prenoms(), "Les prénoms", 120), d.sexe(), naissance,
                facultatif(d.lieuNaissance(), "Le lieu de naissance", 80), telephone,
                facultatif(d.identifiantNational(), "L'identifiant national", 40),
                facultatif(d.adresse(), "L'adresse", 200));
    }

    /** Crée le dossier ; le matricule est généré s'il n'est pas fourni. */
    Eleve creer(DonneesEleve d) {
        IdentiteEleve identite = normaliser(d);
        verifierIdentifiantNational(identite.identifiantNational(), null);
        String matricule;
        if (vide(d.matricule())) {
            matricule = genererMatricule();
        } else {
            matricule = matriculeValide(d.matricule());
            if (eleves.existsByMatricule(matricule)) {
                throw new RegleMetierException("MATRICULE_EXISTANT", "Le matricule " + matricule + " est déjà attribué");
            }
        }
        return eleves.save(new Eleve(matricule, identite));
    }

    /** Matricule « année-numéro » : 2026-00001, 2026-00002... (numérotation propre à l'établissement). */
    String genererMatricule() {
        int annee = LocalDate.now(horloge).getYear();
        String matricule;
        do {
            matricule = annee + "-" + String.format("%05d", compteurs.suivant("MATRICULE-" + annee));
        } while (eleves.existsByMatricule(matricule)); // un matricule saisi à la main peut déjà l'occuper
        return matricule;
    }

    String matriculeValide(String saisie) {
        String matricule = saisie.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT_MATRICULE.matcher(matricule).matches()) {
            throw new IllegalArgumentException("Matricule invalide : lettres, chiffres, / . _ - (30 caractères au plus)");
        }
        return matricule;
    }

    void verifierIdentifiantNational(String identifiant, UUID eleveCourant) {
        if (identifiant == null) {
            return;
        }
        eleves.findByIdentifiantNational(identifiant).filter(autre -> !autre.getId().equals(eleveCourant))
                .ifPresent(autre -> {
                    throw new RegleMetierException("IDENTIFIANT_EXISTANT", "L'identifiant " + identifiant
                            + " est déjà celui de l'élève " + autre.getMatricule());
                });
    }

    String telephone(String saisie) {
        return indicatifs.normaliser(saisie);
    }

    /**
     * Rattache un responsable à l'élève. Le responsable est retrouvé par son
     * téléphone (fratrie) ou créé. Le premier responsable devient le contact
     * prioritaire ; un nouveau contact prioritaire remplace l'ancien.
     */
    LienResponsableEleve rattacher(Eleve eleve, DonneesResponsable d) {
        String telephone = telephone(d.telephone());
        Responsable responsable = responsables.findByTelephone(telephone)
                .orElseGet(() -> responsables.save(new Responsable(
                        obligatoire(d.nom(), "Le nom du responsable", 80).toUpperCase(Locale.ROOT),
                        obligatoire(d.prenoms(), "Les prénoms du responsable", 120), telephone,
                        facultatif(d.profession(), "La profession", 80), d.langueSms())));
        if (liens.existsByEleveIdAndResponsableId(eleve.getId(), responsable.getId())) {
            throw new RegleMetierException("RESPONSABLE_DEJA_LIE",
                    "Ce responsable est déjà rattaché à l'élève " + eleve.getMatricule());
        }
        List<LienResponsableEleve> existants = liens.findByEleveId(eleve.getId());
        boolean prioritaire = existants.isEmpty() || Boolean.TRUE.equals(d.contactPrioritaire());
        if (prioritaire && !existants.isEmpty()) {
            existants.forEach(l -> l.definirContactPrioritaire(false));
            liens.flush(); // l'index unique partiel exige de retirer l'ancien contact avant d'insérer le nouveau
        }
        return liens.save(new LienResponsableEleve(eleve.getId(), responsable.getId(),
                d.lien() != null ? d.lien() : LienParente.AUTRE,
                d.responsableLegal() == null || d.responsableLegal(), prioritaire));
    }

    /** Détache un responsable ; si c'était le contact prioritaire, le suivant le devient. */
    void detacher(UUID eleveId, UUID responsableId) {
        LienResponsableEleve lien = liens.findByEleveIdAndResponsableId(eleveId, responsableId)
                .orElseThrow(() -> new RessourceIntrouvableException("Ce responsable n'est pas rattaché à l'élève"));
        liens.delete(lien);
        liens.flush();
        if (lien.isContactPrioritaire()) {
            liens.findByEleveId(eleveId).stream().findFirst().ifPresent(l -> l.definirContactPrioritaire(true));
        }
    }

    static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }

    static String obligatoire(String valeur, String libelle, int longueurMax) {
        if (vide(valeur)) {
            throw new IllegalArgumentException(libelle + " est obligatoire");
        }
        return facultatif(valeur, libelle, longueurMax);
    }

    static String facultatif(String valeur, String libelle, int longueurMax) {
        if (vide(valeur)) {
            return null;
        }
        String nettoye = valeur.trim().replaceAll("\\s+", " ");
        if (nettoye.length() > longueurMax) {
            throw new IllegalArgumentException(libelle + " dépasse " + longueurMax + " caractères");
        }
        return nettoye;
    }
}
