package bf.edutech.plateforme.recrutement;

/** Situation d'une candidature au complément d'effectif. */
public enum StatutCandidature {
    DEPOSEE("Déposée"), IRRECEVABLE("Irrecevable"), RETENUE("Retenue"), LISTE_ATTENTE("Liste d'attente"),
    NON_RETENUE("Non retenue"), INSCRITE("Inscrite"), DESISTEE("Désistée");

    private final String libelle;

    StatutCandidature(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
