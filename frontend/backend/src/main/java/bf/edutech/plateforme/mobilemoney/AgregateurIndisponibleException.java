package bf.edutech.plateforme.mobilemoney;

/** L'agrégateur ne répond pas ou refuse la demande pour une raison technique. */
class AgregateurIndisponibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    AgregateurIndisponibleException(String message) {
        super(message);
    }
}
