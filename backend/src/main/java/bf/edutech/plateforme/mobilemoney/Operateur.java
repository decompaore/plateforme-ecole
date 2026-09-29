package bf.edutech.plateforme.mobilemoney;

import bf.edutech.plateforme.scolarite.MoyenPaiement;

/** Opérateurs Mobile Money acceptés. */
public enum Operateur {
    ORANGE_MONEY(MoyenPaiement.ORANGE_MONEY),
    MOOV_MONEY(MoyenPaiement.MOOV_MONEY);

    private final MoyenPaiement moyen;

    Operateur(MoyenPaiement moyen) {
        this.moyen = moyen;
    }

    MoyenPaiement moyen() {
        return moyen;
    }
}
