package commands;

/** Definición de un comando (una sola fuente de verdad para ejecutar, HELP y el correo de pruebas). */
public class CommandDef {

    public static final int LECTURA = 0;    // cualquier usuario registrado
    public static final int ESCRITURA = 1;  // Presidente / Secretario / Administrador
    public static final int PROPIO = 2;     // el consejero solo sobre su propio CI (parámetro 1)
    public static final int ADMIN = 3;      // solo Administrador

    @FunctionalInterface
    public interface Handler { CommandResult ejecutar(Contexto c) throws Exception; }

    public final String cu, nombre, parametros, descripcion, ejemplo;
    public final int min, max, permiso;
    public final Handler handler;

    public CommandDef(String cu, String nombre, String parametros, int min, int max, int permiso,
                      String descripcion, String ejemplo, Handler handler) {
        this.cu = cu; this.nombre = nombre; this.parametros = parametros; this.min = min; this.max = max;
        this.permiso = permiso; this.descripcion = descripcion; this.ejemplo = ejemplo; this.handler = handler;
    }

    public String uso() {
        return parametros.isEmpty() ? nombre : nombre + "[" + parametros + "]";
    }
}
