package database;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import config.AppConfig;
import util.AppLogger;

/**
 * Deja la BD lista: ejecuta database/schema.sql y database/seed.sql (son idempotentes).
 * Si el ing. limpió/tumbó la BD, al reconectar las tablas se recrean solas y, si hay
 * un respaldo, se restauran los datos automáticamente.
 */
public final class DatabaseInitializer {

    private static final String DIR_SQL = AppConfig.get("sql.carpeta", "database");

    private DatabaseInitializer() {}

    public static void prepararConexion(Connection c) {
        try {
            ejecutarArchivo(c, "schema.sql");
            ejecutarArchivo(c, "seed.sql");
            if (AppConfig.getBool("backup.auto_restaurar_si_vacia", true) && estaVacia(c)) {
                File ultimo = BackupManager.ultimoRespaldo();
                if (ultimo != null) {
                    AppLogger.info("La BD está vacía y hay respaldo: restaurando " + ultimo.getName() + " ...");
                    BackupManager.restaurar(c, ultimo);
                }
            }
        } catch (Exception e) {
            AppLogger.error("Error preparando la BD (schema/seed)", e);
        }
    }

    private static boolean estaVacia(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM usuario) + (SELECT COUNT(*) FROM sesion)")) {
            return rs.next() && rs.getLong(1) == 0;
        }
    }

    public static void ejecutarArchivo(Connection c, String nombre) throws Exception {
        File f = new File(DIR_SQL, nombre);
        if (!f.exists()) {
            AppLogger.error("No existe el archivo SQL " + f.getAbsolutePath(), null);
            return;
        }
        for (String sql : dividir(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))) {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                if (DatabaseConnection.esErrorDeConexion(e)) throw e;
                AppLogger.error("SQL con error en " + nombre + ": " + primeraLinea(sql), e);
            }
        }
    }

    /** Divide por ';' al final de línea, ignorando comentarios '--'. */
    static List<String> dividir(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String linea : script.split("\\r?\\n")) {
            String t = linea.trim();
            if (t.startsWith("--") || t.isEmpty()) continue;
            cur.append(linea).append('\n');
            if (t.endsWith(";")) {
                String s = cur.toString().trim();
                out.add(s.substring(0, s.length() - 1));
                cur.setLength(0);
            }
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static String primeraLinea(String s) {
        String t = s.trim();
        int i = t.indexOf('\n');
        return i < 0 ? t : t.substring(0, i);
    }
}
