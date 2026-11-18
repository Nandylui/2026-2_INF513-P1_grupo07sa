package commands;

import java.util.LinkedHashMap;
import java.util.Map;

/** Catálogo de comandos. Cada módulo (CU) registra los suyos. */
public final class Registro {

    public static final Map<String, CommandDef> TODOS = new LinkedHashMap<>();

    private Registro() {}

    public static void add(String cu, String nombre, String params, int min, int max, int permiso,
                           String descripcion, String ejemplo, CommandDef.Handler h) {
        TODOS.put(nombre, new CommandDef(cu, nombre, params, min, max, permiso, descripcion, ejemplo, h));
    }

    static {
        ModuloUsuarios.registrar();      // CU1 + CU2
        ModuloSesiones.registrar();      // CU3
        ModuloDocumentos.registrar();    // CU4
        ModuloAsistencia.registrar();    // CU5
        ModuloResoluciones.registrar();  // CU6
        ModuloFiscalizacion.registrar(); // CU7
        ModuloReportes.registrar();      // CU8
        ModuloSistema.registrar();       // HELP, respaldo, estado
    }

    /** Fuerza la carga de los módulos. */
    public static void cargar() { /* el bloque static hace el trabajo */ }
}
