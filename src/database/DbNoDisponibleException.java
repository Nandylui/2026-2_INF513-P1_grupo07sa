package database;

/** La base de datos no responde (servidor caído / IP no habilitada). El correo NO se descarta: se reintenta. */
public class DbNoDisponibleException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public DbNoDisponibleException(String msg, Throwable causa) { super(msg, causa); }
}
