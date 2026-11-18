package database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Operaciones JDBC con PreparedStatement (todo parámetro viaja con '?': sin inyección SQL).
 * Reintenta una vez si la conexión se cayó. Lanza DbNoDisponibleException si el servidor no responde.
 */
public final class DatabaseOperations {

    private static boolean enTransaccion = false;

    private DatabaseOperations() {}

    @FunctionalInterface
    private interface Operacion<T> { T ejecutar(Connection c) throws SQLException; }

    private static <T> T conReintento(Operacion<T> op) throws SQLException {
        try {
            return op.ejecutar(DatabaseConnection.getConnection());
        } catch (SQLException e) {
            if (!DatabaseConnection.esErrorDeConexion(e)) throw e;
            if (enTransaccion) throw new DbNoDisponibleException("Conexión perdida durante una transacción", e);
            DatabaseConnection.reiniciar();
            try {
                return op.ejecutar(DatabaseConnection.getConnection());
            } catch (SQLException e2) {
                if (DatabaseConnection.esErrorDeConexion(e2))
                    throw new DbNoDisponibleException("Base de datos no disponible", e2);
                throw e2;
            }
        }
    }

    private static void asignar(PreparedStatement st, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) st.setNull(i + 1, java.sql.Types.NULL);
            else st.setObject(i + 1, params[i]);
        }
    }

    public static List<Map<String, Object>> consultar(String sql, Object... params) throws SQLException {
        return conReintento(c -> {
            try (PreparedStatement st = c.prepareStatement(sql)) {
                asignar(st, params);
                try (ResultSet rs = st.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> fila = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) fila.put(md.getColumnLabel(i), rs.getObject(i));
                        out.add(fila);
                    }
                    return out;
                }
            }
        });
    }

    public static Map<String, Object> uno(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> r = consultar(sql, params);
        return r.isEmpty() ? null : r.get(0);
    }

    public static long contar(String sql, Object... params) throws SQLException {
        Map<String, Object> f = uno(sql, params);
        if (f == null || f.isEmpty()) return 0;
        Object v = f.values().iterator().next();
        return v == null ? 0 : ((Number) v).longValue();
    }

    public static int ejecutar(String sql, Object... params) throws SQLException {
        return conReintento(c -> {
            try (PreparedStatement st = c.prepareStatement(sql)) {
                asignar(st, params);
                return st.executeUpdate();
            }
        });
    }

    /** INSERT que devuelve el id generado (columna idCol). */
    public static int insertar(String sql, String idCol, Object... params) throws SQLException {
        return conReintento(c -> {
            try (PreparedStatement st = c.prepareStatement(sql, new String[] { idCol })) {
                asignar(st, params);
                st.executeUpdate();
                try (ResultSet rs = st.getGeneratedKeys()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** Ejecuta varias operaciones de forma atómica: o se guardan todas o ninguna. */
    public static synchronized <T> T transaccion(Callable<T> trabajo) throws Exception {
        Connection c = DatabaseConnection.getConnection();
        boolean anterior = c.getAutoCommit();
        c.setAutoCommit(false);
        enTransaccion = true;
        try {
            T r = trabajo.call();
            c.commit();
            return r;
        } catch (Exception e) {
            try { c.rollback(); } catch (SQLException ignorar) { }
            throw e;
        } finally {
            enTransaccion = false;
            try { c.setAutoCommit(anterior); } catch (SQLException ignorar) { }
        }
    }
}
