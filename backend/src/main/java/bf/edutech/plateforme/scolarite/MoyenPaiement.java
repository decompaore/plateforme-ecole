package bf.edutech.plateforme.scolarite;

public enum MoyenPaiement {
    ESPECES("Espèces"),
    ORANGE_MONEY("Orange Money"),
    MOOV_MONEY("Moov Money"),
    VIREMENT("Virement"),
    CHEQUE("Chèque");

    private final String libelle;

    MoyenPaiement(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
