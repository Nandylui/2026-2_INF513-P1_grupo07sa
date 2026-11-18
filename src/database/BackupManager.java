package database;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import config.AppConfig;
import util.AppLogger;

/**
 * Respaldo y restauración de los DATOS (sin pg_dump): cada tabla se exporta a un
 * archivo .sql con una sentencia INSERT por línea. El esquema lo recrea schema.sql.
 *
 *  - respaldar():  crea backups/respaldo_AAAAMMDD_HHMMSS.sql y conserva los N más recientes.
 *  - restaurar():  vacía las tablas, re-inserta el respaldo y reajusta las secuencias.
 */
public final class BackupManager {

    /** Orden de dependencias (padres primero). */
    private static final String[][] TABLAS = {
        {"rol", "id_rol"}, {"usuario", "id_usuario"}, {"mandato", "id_mandato"}, {"sesion", "id_sesion"},
        {"punto_orden_dia", "id_punto"}, {"excusa", "id_excusa"}, {"habilitacion", "id_habilitacion"},
        {"tipo_tramite", "id_tramite"}, {"flujo_paso", "id_paso"}, {"documento", "id_documento"},
        {"documento_version", "id_version"}, {"documento_historial", "id_historial"},
        {"asistencia", "id_asistencia"}, {"votacion", "id_votacion"}, {"resolucion", "id_resolucion"},
        {"acta", "id_acta"}, {"gaceta", "id_gaceta"}, {"auditoria", "id_auditoria"}
    };

    private BackupManager() {}

    public static File carpeta() {
        File d = new File(AppConfig.get("backup.carpeta", "backups"));
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<File> listar() {
        File[] fs = carpeta().listFiles((d, n) -> n.startsWith("respaldo_") && n.endsWith(".sql"));
        List<File> l = new ArrayList<>();
        if (fs != null) l.addAll(Arrays.asList(fs));
        l.sort(Comparator.comparing(File::getName).reversed());   // más reciente primero
        return l;
    }

    public static File ultimoRespaldo() {
        List<File> l = listar();
        return l.isEmpty() ? null : l.get(0);
    }

    public static synchronized File respaldar() throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String sufijo = DatabaseConnection.enContingencia() ? "_contingencia" : "";
        File f = new File(carpeta(), "respaldo_" + ts + sufijo + ".sql");
        int filas = 0;
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new java.io.FileOutputStream(f), StandardCharsets.UTF_8))) {
            pw.println("-- Respaldo de datos - Consejo Facultativo (grupo07sa) - " + LocalDateTime.now());
            pw.println("-- Origen: " + DatabaseConnection.descripcion());
            for (String[] t : TABLAS) {
                List<Map<String, Object>> rows;
                try {
                    rows = DatabaseOperations.consultar("SELECT * FROM " + t[0] + " ORDER BY " + t[1]);
                } catch (java.sql.SQLException e) {
                    if (DatabaseConnection.esErrorDeConexion(e)) throw e;
                    AppLogger.error("Respaldo: se omite la tabla " + t[0], e);
                    continue;
                }
                for (Map<String, Object> r : rows) {
                    StringBuilder cols = new StringBuilder(), vals = new StringBuilder();
                    for (Map.Entry<String, Object> e : r.entrySet()) {
                        if (cols.length() > 0) { cols.append(", "); vals.append(", "); }
                        cols.append(e.getKey());
                        vals.append(literal(e.getValue()));
                    }
                    pw.println("INSERT INTO " + t[0] + " (" + cols + ") VALUES (" + vals + ");");
                    filas++;
                }
            }
        }
        rotar();
        AppLogger.info("Respaldo creado: " + f.getName() + " (" + filas + " filas)");
        return f;
    }

    private static void rotar() {
        int conservar = AppConfig.getInt("backup.conservar", 10);
        List<File> l = listar();
        for (int i = conservar; i < l.size(); i++) l.get(i).delete();
    }

    static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof BigDecimal) return v.toString();
        if (v instanceof Boolean) return ((Boolean) v) ? "TRUE" : "FALSE";
        String s = v.toString().replace("\\", "\\\\").replace("'", "''")
                .replace("\n", "\\n").replace("\r", "\\r");
        return "E'" + s + "'";
    }

    /** Restaura usando la conexión indicada (vacía todas las tablas primero). */
    public static synchronized int restaurar(Connection c, File archivo) throws Exception {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        int n = 0;
        try (Statement st = c.createStatement()) {
            StringBuilder lista = new StringBuilder();
            for (String[] t : TABLAS) { if (lista.length() > 0) lista.append(", "); lista.append(t[0]); }
            st.execute("TRUNCATE TABLE " + lista + " RESTART IDENTITY CASCADE");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (linea.startsWith("INSERT INTO ")) { st.execute(linea); n++; }
                }
            }
            for (String[] t : TABLAS) {
                st.execute("SELECT setval(pg_get_serial_sequence('" + t[0] + "','" + t[1] + "'), "
                        + "COALESCE((SELECT MAX(" + t[1] + ") FROM " + t[0] + "),1), "
                        + "(SELECT MAX(" + t[1] + ") FROM " + t[0] + ") IS NOT NULL)");
            }
            c.commit();
            AppLogger.info("Restauración completa desde " + archivo.getName() + " (" + n + " filas)");
            return n;
        } catch (Exception e) {
            try { c.rollback(); } catch (Exception ignorar) { }
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /** Restaura sobre la conexión activa; archivo null = el más reciente. */
    public static int restaurar(File archivo) throws Exception {
        File f = archivo != null ? archivo : ultimoRespaldo();
        if (f == null) throw new IllegalStateException("No hay respaldos en " + carpeta().getAbsolutePath());
        return restaurar(DatabaseConnection.getConnection(), f);
    }
}
