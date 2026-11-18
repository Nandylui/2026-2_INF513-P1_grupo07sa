package commands;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import config.AppConfig;
import database.BackupManager;
import database.DatabaseConnection;
import database.DatabaseInitializer;
import database.DatabaseOperations;

/** Comandos de sistema: ayuda, estado, respaldo / restauración e inicialización. */
final class ModuloSistema {

    private ModuloSistema() {}

    static void registrar() {
        String cu = "SISTEMA";

        Registro.add(cu, "HELP", "tema", 0, 1, CommandDef.LECTURA,
            "Muestra los comandos disponibles. Tema opcional: un CU (CU1..CU8), SISTEMA o el nombre de un comando", "HELP", ModuloSistema::ayuda);

        Registro.add(cu, "ESTADOSISTEMA", "", 0, 0, CommandDef.LECTURA,
            "Estado del sistema: base de datos en uso, contingencia, correo, reglas y respaldos", "ESTADOSISTEMA", c -> {
                DatabaseConnection.testConnection();   // asegura que "en uso" refleje la conexión real
                File ult = BackupManager.ultimoRespaldo();
                return CommandResult.exito("Sistema Consejo Facultativo en línea")
                    .dato("Base de datos en uso", DatabaseConnection.descripcion())
                    .dato("Modo contingencia", DatabaseConnection.enContingencia())
                    .dato("Cuenta de correo", AppConfig.get("correo.direccion", "grupo07sa@tecnoweb.org.bo"))
                    .dato("Servidor de correo", AppConfig.get("correo.host", "mail.tecnoweb.org.bo"))
                    .dato("Intervalo de lectura (seg)", AppConfig.getInt("correo.intervalo_segundos", 30))
                    .dato("Anticipación para convocar / recibir documentos (h)", Datos.anticipacionHoras())
                    .dato("Validación de remitente", AppConfig.getBool("seguridad.validar_remitente", false))
                    .dato("Último respaldo", ult == null ? "(ninguno)" : ult.getName())
                    .dato("Hora del servidor", new java.sql.Timestamp(System.currentTimeMillis()))
                    .dato("Comandos disponibles", Registro.TODOS.size());
            });

        Registro.add(cu, "RESPALDAR", "", 0, 0, CommandDef.ESCRITURA,
            "Crea ahora un respaldo de todos los datos (carpeta backups/)", "RESPALDAR", c -> {
                File f = BackupManager.respaldar();
                return CommandResult.exito("Respaldo creado").dato("Archivo", f.getName()).dato("Tamaño (KB)", Math.max(1, f.length() / 1024))
                    .dato("Respaldos guardados", BackupManager.listar().size());
            });

        Registro.add(cu, "OBTENERRESPALDOS", "", 0, 0, CommandDef.LECTURA,
            "Lista los respaldos disponibles", "OBTENERRESPALDOS", c -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (File f : BackupManager.listar()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("Archivo", f.getName());
                    m.put("KB", Math.max(1, f.length() / 1024));
                    rows.add(m);
                }
                return CommandResult.exito("Respaldos disponibles").tabla("Respaldos", rows);
            });

        Registro.add(cu, "RESTAURAR", "archivo", 0, 1, CommandDef.ADMIN,
            "Restaura los datos desde un respaldo (el más reciente si no se indica). Deshabilitado por correo salvo seguridad.permitir_restaurar_por_correo=true; "
          + "por defecto se hace desde la consola con main.Respaldo",
            "RESTAURAR[\"respaldo_20261117_101500.sql\"]", c -> {
                if (!AppConfig.getBool("seguridad.permitir_restaurar_por_correo", false))
                    throw new ComandoException("Por seguridad la restauración no se permite por correo. Ejecute main.Respaldo restaurar [archivo] en el servidor, o active seguridad.permitir_restaurar_por_correo en config.properties.");
                File f = null;
                if (c.opt(0) != null) {
                    f = new File(BackupManager.carpeta(), new File(c.opt(0)).getName());   // evita rutas fuera de backups/
                    if (!f.exists()) throw new ComandoException("No existe el respaldo '" + c.opt(0) + "'. Use OBTENERRESPALDOS.");
                }
                int n = BackupManager.restaurar(f);
                return CommandResult.exito("Datos restaurados").dato("Filas restauradas", n);
            });

        Registro.add(cu, "INICIALIZARBD", "", 0, 0, CommandDef.ADMIN,
            "Recrea las tablas que falten y los datos base (no borra datos existentes)", "INICIALIZARBD", c -> {
                DatabaseInitializer.ejecutarArchivo(DatabaseConnection.getConnection(), "schema.sql");
                DatabaseInitializer.ejecutarArchivo(DatabaseConnection.getConnection(), "seed.sql");
                return CommandResult.exito("Base de datos inicializada (schema.sql + seed.sql)")
                    .dato("Roles", DatabaseOperations.contar("SELECT COUNT(*) FROM rol"));
            });

        Registro.add(cu, "CARGARDEMO", "", 0, 0, CommandDef.ADMIN,
            "Carga datos de demostración (presidente, secretario y 4 consejeros con suplentes) para probar los comandos", "CARGARDEMO", c -> {
                DatabaseInitializer.ejecutarArchivo(DatabaseConnection.getConnection(), "datos_demo.sql");
                return CommandResult.exito("Datos de demostración cargados")
                    .dato("Usuarios", DatabaseOperations.contar("SELECT COUNT(*) FROM usuario"))
                    .dato("Mandatos vigentes", DatabaseOperations.contar("SELECT COUNT(*) FROM mandato WHERE estado = 'VIGENTE'"));
            });
    }

    private static CommandResult ayuda(Contexto c) {
        String tema = c.opt(0);
        if (tema != null && Registro.TODOS.containsKey(tema.toUpperCase())) {
            CommandDef d = Registro.TODOS.get(tema.toUpperCase());
            return CommandResult.exito("Ayuda del comando " + d.nombre)
                .dato("Caso de uso", d.cu).dato("Uso", d.uso()).dato("Descripción", d.descripcion).dato("Ejemplo", d.ejemplo);
        }
        String filtro = tema == null || tema.equals("*") ? null : tema.toUpperCase();
        CommandResult r = CommandResult.exito(filtro == null ? "Comandos disponibles (" + Registro.TODOS.size() + ")" : "Comandos de " + filtro);
        if (filtro == null)
            r.bloque("Cómo enviar un comando",
                "Escriba el comando en el ASUNTO del correo, con los parámetros entre corchetes y comillas dobles:\n"
              + "   COMANDO[\"parametro1\",\"parametro2\",...]\n"
              + "Los comandos sin parámetros pueden ir solos:  HELP   OBTENERROLES\n"
              + "Fechas: AAAA-MM-DD   Fecha y hora: AAAA-MM-DD HH:MM   Para omitir un parámetro opcional use \"\"\n"
              + "Detalle de un comando: HELP[\"NOMBRECOMANDO\"]   Por caso de uso: HELP[\"CU3\"]");
        Map<String, List<Map<String, Object>>> porCu = new LinkedHashMap<>();
        for (String k : TITULOS.keySet()) porCu.put(k, new ArrayList<>());
        for (CommandDef d : Registro.TODOS.values()) {
            if (filtro != null && !d.cu.equals(filtro)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("COMANDO", d.uso());
            m.put("DESCRIPCION", filtro == null ? corto(d.descripcion) : d.descripcion);
            m.put("EJEMPLO", d.ejemplo == null || d.ejemplo.isEmpty() ? d.nombre : d.ejemplo);
            porCu.computeIfAbsent(d.cu, k -> new ArrayList<>()).add(m);
        }
        boolean hay = false;
        for (Map.Entry<String, List<Map<String, Object>>> e : porCu.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            hay = true;
            r.tabla("Caso de uso: " + TITULOS.getOrDefault(e.getKey(), e.getKey()), e.getValue());
        }
        if (!hay) throw new ComandoException("No hay comandos para '" + tema + "'. Use CU1 a CU8, SISTEMA o un nombre de comando.");
        return r;
    }

    private static final Map<String, String> TITULOS = new LinkedHashMap<>();
    static {
        TITULOS.put("CU1", "CU1 Gestionar Usuarios");
        TITULOS.put("CU2", "CU2 Gestionar Miembros y Quórum");
        TITULOS.put("CU3", "CU3 Convocatorias, Excusas y Suplentes");
        TITULOS.put("CU4", "CU4 Repositorio Documental y Workflow");
        TITULOS.put("CU5", "CU5 Asistencia, Quórum y Votaciones");
        TITULOS.put("CU6", "CU6 Resoluciones, Veto y Actas");
        TITULOS.put("CU7", "CU7 Fiscalizar, Auditar y Publicar Gaceta");
        TITULOS.put("CU8", "CU8 Reportes");
        TITULOS.put("SISTEMA", "Sistema (ayuda, estado y respaldo)");
    }

    private static String corto(String s) {
        int i = s.indexOf(". ");
        String x = i > 0 ? s.substring(0, i) : s;
        return x.length() > 90 ? x.substring(0, 87) + "..." : x;
    }
}
