package commands;

import java.sql.SQLException;
import java.util.Map;

import config.AppConfig;
import database.DatabaseOperations;

/** Consultas y reglas comunes a varios casos de uso. */
public final class Datos {

    private Datos() {}

    public static final String SQL_USUARIO =
        "SELECT u.id_usuario, u.ci, u.nombres, u.apellidos, u.correo, u.estado, r.nombre AS rol "
      + "FROM usuario u LEFT JOIN rol r ON r.id_rol = u.id_rol ";

    public static int anticipacionHoras() { return AppConfig.getInt("reglas.anticipacion_horas", 48); }

    public static Map<String, Object> usuario(String ci) throws SQLException {
        return DatabaseOperations.uno(SQL_USUARIO + "WHERE u.ci = ?", ci);
    }

    public static Map<String, Object> usuarioPorCorreo(String correo) throws SQLException {
        return DatabaseOperations.uno(SQL_USUARIO + "WHERE LOWER(u.correo) = LOWER(?)", correo);
    }

    public static Map<String, Object> requerirUsuario(String ci) throws SQLException {
        Map<String, Object> u = usuario(ci);
        if (u == null) throw new ComandoException("No existe un usuario con CI " + ci + ". Regístrelo primero con REGISTRARUSUARIO.");
        return u;
    }

    public static Map<String, Object> requerirUsuarioActivo(String ci) throws SQLException {
        Map<String, Object> u = requerirUsuario(ci);
        if (!"ACTIVO".equals(u.get("estado"))) throw new ComandoException("El usuario con CI " + ci + " está INACTIVO.");
        return u;
    }

    public static String nombre(Map<String, Object> u) { return u.get("nombres") + " " + u.get("apellidos"); }

    public static String rol(Map<String, Object> u) { return u.get("rol") == null ? "" : u.get("rol").toString(); }

    /** DOCENTE / ESTUDIANTE según el rol (null si el rol no es de consejero). */
    public static String estamento(String rol) {
        if (rol == null) return null;
        if (rol.endsWith("_DOCENTE")) return "DOCENTE";
        if (rol.endsWith("_ESTUDIANTE")) return "ESTUDIANTE";
        return null;
    }

    public static boolean esStaff(String rol) {
        return "ADMINISTRADOR".equals(rol) || "PRESIDENTE".equals(rol) || "SECRETARIO".equals(rol);
    }

    public static Map<String, Object> sesion(int id) throws SQLException {
        return DatabaseOperations.uno("SELECT * FROM sesion WHERE id_sesion = ?", id);
    }

    public static Map<String, Object> requerirSesion(int id) throws SQLException {
        Map<String, Object> s = sesion(id);
        if (s == null) throw new ComandoException("No existe la sesión Nro " + id + ".");
        return s;
    }

    public static Map<String, Object> requerirDocumento(int id) throws SQLException {
        Map<String, Object> d = DatabaseOperations.uno("SELECT * FROM documento WHERE id_documento = ?", id);
        if (d == null) throw new ComandoException("No existe el documento Nro " + id + ".");
        return d;
    }

    public static Map<String, Object> requerirResolucion(int id) throws SQLException {
        Map<String, Object> r = DatabaseOperations.uno("SELECT * FROM resolucion WHERE id_resolucion = ?", id);
        if (r == null) throw new ComandoException("No existe la resolución Nro " + id + ".");
        return r;
    }

    // ---------- quórum (mayoría absoluta de los escaños del Consejo) ----------

    /** Escaños = mandatos VIGENTES con titular activo + el Presidente (1). */
    public static int totalEscanos() throws SQLException {
        long m = DatabaseOperations.contar(
            "SELECT COUNT(*) FROM mandato m JOIN usuario u ON u.id_usuario = m.id_titular "
          + "WHERE m.estado = 'VIGENTE' AND u.estado = 'ACTIVO'");
        long p = DatabaseOperations.contar(
            "SELECT COUNT(*) FROM usuario u JOIN rol r ON r.id_rol = u.id_rol WHERE r.nombre = 'PRESIDENTE' AND u.estado = 'ACTIVO'");
        return (int) (m + Math.min(p, 1));
    }

    public static int quorumRequerido(int escanos) { return escanos / 2 + 1; }

    public static int presentes(int idSesion) throws SQLException {
        return (int) DatabaseOperations.contar("SELECT COUNT(*) FROM asistencia WHERE id_sesion = ?", idSesion);
    }

    /** Fecha límite para recibir documentos de una sesión (fecha de la sesión menos las horas de anticipación). */
    public static java.sql.Timestamp limiteDocumentos(java.sql.Timestamp fechaSesion) {
        return new java.sql.Timestamp(fechaSesion.getTime() - anticipacionHoras() * 3600_000L);
    }
}
