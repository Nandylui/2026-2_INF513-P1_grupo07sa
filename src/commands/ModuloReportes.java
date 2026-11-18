package commands;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import database.DatabaseOperations;
import util.Util;

/** CU8: Reportes. */
final class ModuloReportes {

    private ModuloReportes() {}

    static void registrar() {
        String cu = "CU8";

        Registro.add(cu, "REPORTEASISTENCIA", "id_sesion", 0, 1, CommandDef.LECTURA,
            "Reporte de asistencia: resumen por sesión, o detalle de una sesión", "REPORTEASISTENCIA[\"1\"]", c -> {
                if (c.opt(0) == null)
                    return CommandResult.exito("Reporte de asistencia por sesión").tabla("Asistencia", DatabaseOperations.consultar(
                        "SELECT s.id_sesion AS \"Sesión\", s.fecha_hora AS \"Fecha\", s.tipo AS \"Tipo\", s.estado AS \"Estado\", COUNT(a.id_asistencia) AS \"Presentes\", "
                      + "COALESCE(SUM(CASE WHEN a.como_suplente THEN 1 ELSE 0 END),0) AS \"Suplentes\" FROM sesion s LEFT JOIN asistencia a ON a.id_sesion = s.id_sesion "
                      + "GROUP BY s.id_sesion ORDER BY s.id_sesion"));
                int id = c.entero(0, "id_sesion");
                Datos.requerirSesion(id);
                int esc = Datos.totalEscanos();
                int pres = Datos.presentes(id);
                return CommandResult.exito("Reporte de asistencia de la sesión Nro " + id)
                    .dato("Escaños", esc).dato("Presentes", pres).dato("% de asistencia", esc == 0 ? "0" : String.format("%.1f%%", pres * 100.0 / esc))
                    .tabla("Asistentes", DatabaseOperations.consultar(
                        "SELECT u.nombres || ' ' || u.apellidos AS \"Nombre\", r.nombre AS \"Rol\", a.hora_ingreso AS \"Ingreso\" FROM asistencia a "
                      + "JOIN usuario u ON u.id_usuario = a.id_usuario LEFT JOIN rol r ON r.id_rol = u.id_rol WHERE a.id_sesion = ? ORDER BY a.hora_ingreso", id))
                    .tabla("Ausentes con excusa", DatabaseOperations.consultar(
                        "SELECT u.nombres || ' ' || u.apellidos AS \"Titular\", e.motivo AS \"Motivo\" FROM excusa e JOIN usuario u ON u.id_usuario = e.id_titular WHERE e.id_sesion = ?", id));
            });

        Registro.add(cu, "REPORTEVOTACIONES", "id_sesion", 0, 1, CommandDef.LECTURA,
            "Reporte de votaciones por documento (todas las sesiones o una)", "REPORTEVOTACIONES[\"1\"]", c -> {
                String base = "SELECT d.id_sesion AS \"Sesión\", d.id_documento AS \"Doc\", d.titulo AS \"Documento\", "
                    + "SUM(CASE WHEN v.voto = 'A_FAVOR' THEN 1 ELSE 0 END) AS \"A favor\", SUM(CASE WHEN v.voto = 'EN_CONTRA' THEN 1 ELSE 0 END) AS \"En contra\", "
                    + "SUM(CASE WHEN v.voto = 'ABSTENCION' THEN 1 ELSE 0 END) AS \"Abstención\", COALESCE(MAX(r.numero), '-') AS \"Resolución\" "
                    + "FROM documento d JOIN votacion v ON v.id_documento = d.id_documento LEFT JOIN resolucion r ON r.id_documento = d.id_documento ";
                List<Map<String, Object>> rows = c.opt(0) == null
                    ? DatabaseOperations.consultar(base + "GROUP BY d.id_documento ORDER BY d.id_sesion, d.id_documento")
                    : DatabaseOperations.consultar(base + "WHERE d.id_sesion = ? GROUP BY d.id_documento ORDER BY d.id_documento", c.entero(0, "id_sesion"));
                return CommandResult.exito("Reporte de votaciones").tabla("Votaciones", rows);
            });

        Registro.add(cu, "REPORTERESOLUCIONES", "estado", 0, 1, CommandDef.LECTURA,
            "Reporte de resoluciones con conteo por estado; filtro opcional por estado", "REPORTERESOLUCIONES[\"PROMULGADA\"]", c -> {
                CommandResult r = CommandResult.exito("Reporte de resoluciones").tabla("Resumen por estado", DatabaseOperations.consultar(
                    "SELECT estado AS \"Estado\", COUNT(*) AS \"Cantidad\" FROM resolucion GROUP BY estado ORDER BY estado"));
                String sql = "SELECT r.numero AS \"Número\", d.titulo AS \"Documento\", r.estado AS \"Estado\", r.votos_favor AS \"Favor\", r.votos_contra AS \"Contra\", "
                    + "r.fecha_emision AS \"Emitida\" FROM resolucion r JOIN documento d ON d.id_documento = r.id_documento ";
                return r.tabla("Detalle", c.opt(0) == null ? DatabaseOperations.consultar(sql + "ORDER BY r.id_resolucion")
                    : DatabaseOperations.consultar(sql + "WHERE r.estado = ? ORDER BY r.id_resolucion", Util.clave(c.opt(0))));
            });

        Registro.add(cu, "REPORTEEXCUSAS", "desde,hasta", 0, 2, CommandDef.LECTURA,
            "Reporte de excusas por consejero en un rango de fechas (AAAA-MM-DD, opcional)", "REPORTEEXCUSAS[\"2026-01-01\",\"2026-12-31\"]", c -> {
                Date d = c.opt(0) == null ? Date.valueOf("1900-01-01") : Date.valueOf(Util.parseFecha(c.opt(0)));
                Date h = c.opt(1) == null ? Date.valueOf("2999-12-31") : Date.valueOf(Util.parseFecha(c.opt(1)));
                return CommandResult.exito("Reporte de excusas").tabla("Excusas por consejero", DatabaseOperations.consultar(
                    "SELECT u.ci AS \"CI\", u.nombres || ' ' || u.apellidos AS \"Titular\", COUNT(*) AS \"Excusas\" FROM excusa e JOIN usuario u ON u.id_usuario = e.id_titular "
                  + "WHERE e.fecha_registro::date BETWEEN ? AND ? GROUP BY u.id_usuario ORDER BY 3 DESC, 2", d, h));
            });

        Registro.add(cu, "REPORTEDOCUMENTOS", "estado", 0, 1, CommandDef.LECTURA,
            "Reporte del repositorio documental: conteo por estado y extemporáneos", "REPORTEDOCUMENTOS", c -> {
                CommandResult r = CommandResult.exito("Reporte de documentos").tabla("Por estado", DatabaseOperations.consultar(
                    "SELECT estado AS \"Estado\", COUNT(*) AS \"Cantidad\", SUM(CASE WHEN extemporaneo THEN 1 ELSE 0 END) AS \"Extemporáneos\" FROM documento GROUP BY estado ORDER BY estado"));
                String sql = "SELECT id_documento AS \"ID\", titulo AS \"Título\", id_sesion AS \"Sesión\", estado AS \"Estado\", fecha_recepcion AS \"Recibido\" FROM documento ";
                return r.tabla("Detalle", c.opt(0) == null ? DatabaseOperations.consultar(sql + "ORDER BY id_documento")
                    : DatabaseOperations.consultar(sql + "WHERE estado = ? ORDER BY id_documento", Util.clave(c.opt(0))));
            });

        Registro.add(cu, "REPORTEGENERAL", "", 0, 0, CommandDef.LECTURA,
            "Resumen general del sistema (totales de cada módulo)", "REPORTEGENERAL", c -> {
                CommandResult r = CommandResult.exito("Resumen general del Consejo Facultativo");
                String[][] items = {
                    {"Usuarios activos", "SELECT COUNT(*) FROM usuario WHERE estado = 'ACTIVO'"},
                    {"Mandatos vigentes", "SELECT COUNT(*) FROM mandato WHERE estado = 'VIGENTE'"},
                    {"Sesiones", "SELECT COUNT(*) FROM sesion"},
                    {"Sesiones finalizadas", "SELECT COUNT(*) FROM sesion WHERE estado = 'FINALIZADA'"},
                    {"Excusas", "SELECT COUNT(*) FROM excusa"},
                    {"Documentos", "SELECT COUNT(*) FROM documento"},
                    {"Votos emitidos", "SELECT COUNT(*) FROM votacion"},
                    {"Resoluciones", "SELECT COUNT(*) FROM resolucion"},
                    {"Actas", "SELECT COUNT(*) FROM acta"},
                    {"Publicaciones en Gaceta", "SELECT COUNT(*) FROM gaceta"},
                    {"Comandos auditados", "SELECT COUNT(*) FROM auditoria"}};
                for (String[] it : items) r.dato(it[0], DatabaseOperations.contar(it[1]));
                return r;
            });
    }
}
