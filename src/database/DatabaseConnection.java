package database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import config.AppConfig;
import util.AppLogger;

/**
 * Conexión única a PostgreSQL con reconexión automática y CONTINGENCIA:
 * si el perfil "remoto" (servidor del ing.) no responde, se usa la BD "local"
 * (con el mismo esquema y el último respaldo) para que el sistema siga vivo.
 * Cada ciclo intenta volver al servidor remoto.
 *
 * Cambiar de local a servidor = cambiar "perfil" en config.properties.
 */
public final class DatabaseConnection {

    private static Connection conn;
    private static String perfilEnUso;          // "local" | "remoto"
    private static boolean contingencia = false;

    private DatabaseConnection() {}

    private static String url(String perfil) {
        return AppConfig.get("bd." + perfil + ".url", "jdbc:postgresql://localhost:5432/db_prueba_tecno");
    }

    private static Connection abrir(String perfil) throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Driver PostgreSQL no encontrado (lib/postgresql-42.7.1.jar)", e);
        }
        DriverManager.setLoginTimeout(AppConfig.getInt("bd.timeout_conexion_seg", 8));
        String u = url(perfil);
        String sep = u.contains("?") ? "&" : "?";
        u = u + sep + "connectTimeout=" + AppConfig.getInt("bd.timeout_conexion_seg", 8)
                + "&socketTimeout=" + AppConfig.getInt("bd.timeout_consulta_seg", 30)
                + "&ApplicationName=ConsejoFacultativoViaMail";
        Connection c = DriverManager.getConnection(u,
                AppConfig.get("bd." + perfil + ".usuario", "postgres"),
                AppConfig.get("bd." + perfil + ".clave", ""));
        c.setAutoCommit(true);
        return c;
    }

    public static synchronized Connection getConnection() throws SQLException {
        try {
            if (conn != null && !conn.isClosed() && conn.isValid(3)) return conn;
        } catch (SQLException ignorar) { /* se reconecta */ }
        cerrarSilencioso();

        String principal = AppConfig.perfil();
        SQLException fallo = null;
        try {
            conn = abrir(principal);
            perfilEnUso = principal;
            if (contingencia) AppLogger.info("Servidor principal recuperado: se vuelve al perfil '" + principal + "'.");
            contingencia = false;
            DatabaseInitializer.prepararConexion(conn);
            return conn;
        } catch (SQLException e) {
            fallo = e;
            cerrarSilencioso();
            AppLogger.error("No se pudo conectar a la BD (perfil " + principal + "): " + url(principal), e);
        }

        // Contingencia: solo si el principal es "remoto" y está permitido
        if ("remoto".equals(principal) && AppConfig.getBool("bd.contingencia", true)) {
            try {
                conn = abrir("local");
                perfilEnUso = "local";
                if (!contingencia) AppLogger.info("MODO CONTINGENCIA: usando la BD local mientras el servidor remoto no responde.");
                contingencia = true;
                DatabaseInitializer.prepararConexion(conn);
                return conn;
            } catch (SQLException e2) {
                cerrarSilencioso();
                AppLogger.error("Tampoco se pudo usar la BD local de contingencia", e2);
            }
        }
        throw fallo;
    }

    /** Si estamos en contingencia, intenta volver al servidor principal. */
    public static synchronized void intentarVolverAlPrincipal() {
        if (!contingencia) return;
        try (Connection c = abrir(AppConfig.perfil())) {
            if (c.isValid(3)) {
                cerrarSilencioso();   // la próxima getConnection() usará el principal
            }
        } catch (SQLException e) {
            // sigue caído; se mantiene la contingencia
        }
    }

    public static synchronized void reiniciar() { cerrarSilencioso(); }

    public static boolean enContingencia() { return contingencia; }

    public static String perfilEnUso() { return perfilEnUso == null ? AppConfig.perfil() : perfilEnUso; }

    public static String descripcion() {
        String p = perfilEnUso();
        return p + " -> " + url(p) + (contingencia ? "  [CONTINGENCIA]" : "");
    }

    private static void cerrarSilencioso() {
        try { if (conn != null) conn.close(); } catch (SQLException ignorar) { }
        conn = null;
    }

    public static synchronized void closeConnection() { cerrarSilencioso(); }

    public static boolean testConnection() {
        try {
            Connection c = getConnection();
            return c != null && !c.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /** ¿El error indica que el servidor no responde (y no un error de datos)? */
    public static boolean esErrorDeConexion(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException) {
                String st = ((SQLException) t).getSQLState();
                if (st != null && (st.startsWith("08") || st.startsWith("57P") || st.startsWith("53"))) return true;
            }
            if (t instanceof java.net.SocketException || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.io.EOFException) return true;
        }
        return false;
    }
}
