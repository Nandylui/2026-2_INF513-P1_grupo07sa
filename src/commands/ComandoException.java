package commands;

/** Error de uso/negocio: el mensaje se devuelve tal cual al usuario por correo. */
public class ComandoException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ComandoException(String mensaje) { super(mensaje); }
}
