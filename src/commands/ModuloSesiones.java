package commands;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import database.DatabaseOperations;
import util.Util;

/** CU3: Convocatorias (sesiones), excusas de titulares y habilitación de suplentes. */
final class ModuloSesiones {

    private ModuloSesiones() {}

    static void registrar() {
        String cu = "CU3";

        Registro.add(cu, "OBTENERSESIONES", "filtro", 0, 1, CommandDef.LECTURA,
            "Lista sesiones. Filtro opcional: * , estado (PROGRAMADA, EN_CURSO, FINALIZADA, CANCELADA) o tipo (ORDINARIA, EXTRAORDINARIA)",
            "OBTENERSESIONES[\"PROGRAMADA\"]", c -> {
                String f = c.opt(0);
                String sql = "SELECT s.id_sesion AS \"ID\", s.fecha_hora AS \"Fecha y hora\", s.lugar AS \"Lugar\", s.tipo AS \"Tipo\", s.estado AS \"Estado\", "
                    + "(SELECT COUNT(*) FROM asistencia a WHERE a.id_sesion = s.id_sesion) AS \"Presentes\", "
                    + "(SELECT COUNT(*) FROM punto_orden_dia p WHERE p.id_sesion = s.id_sesion) AS \"Puntos\" FROM sesion s ";
                List<Map<String, Object>> rows = (f == null || f.equals("*"))
                    ? DatabaseOperations.consultar(sql + "ORDER BY s.id_sesion")
                    : DatabaseOperations.consultar(sql + "WHERE s.estado = ? OR s.tipo = ? ORDER BY s.id_sesion", Util.clave(f), Util.clave(f));
                return CommandResult.exito("Sesiones del Consejo").tabla("Sesiones", rows);
            });

        Registro.add(cu, "OBTENERSESION", "id_sesion", 1, 1, CommandDef.LECTURA,
            "Detalle de una sesión: datos, orden del día y documentos", "OBTENERSESION[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Map<String, Object> s = Datos.requerirSesion(id);
                CommandResult r = CommandResult.exito("Detalle de la sesión Nro " + id)
                    .dato("Fecha y hora", s.get("fecha_hora")).dato("Lugar", s.get("lugar")).dato("Tipo", s.get("tipo"))
                    .dato("Estado", s.get("estado")).dato("Solicitantes", s.get("solicitantes"))
                    .dato("Límite para recibir documentos", Datos.limiteDocumentos((Timestamp) s.get("fecha_hora")));
                r.tabla("Orden del día", DatabaseOperations.consultar(
                    "SELECT orden AS \"N°\", descripcion AS \"Punto\" FROM punto_orden_dia WHERE id_sesion = ? ORDER BY orden", id));
                r.tabla("Documentos", DatabaseOperations.consultar(
                    "SELECT id_documento AS \"ID\", titulo AS \"Título\", estado AS \"Estado\", extemporaneo AS \"Extemporáneo\" FROM documento WHERE id_sesion = ? ORDER BY id_documento", id));
                return r;
            });

        Registro.add(cu, "REGISTRARSESION", "fecha_hora,lugar,tipo,solicitantes,orden_dia", 3, 5, CommandDef.ESCRITURA,
            "Convoca una sesión (ordinaria o extraordinaria) con " + Datos.anticipacionHoras() + " h de anticipación. "
          + "Extraordinaria: solicitantes = CI del Presidente o al menos 3 CI de consejeros separados por ;  "
          + "orden_dia = puntos separados por ;  (use \"\" para omitir un parámetro)",
            "REGISTRARSESION[\"2027-03-10 09:30\",\"Sala del Consejo\",\"ORDINARIA\",\"\",\"Informe del decano;Aprobar POA\"]",
            ModuloSesiones::registrarSesion);

        Registro.add(cu, "MODIFICARSESION", "id_sesion,campo,valor", 3, 3, CommandDef.ESCRITURA,
            "Modifica una sesión PROGRAMADA: lugar, fecha_hora u orden_dia (puntos separados por ;)",
            "MODIFICARSESION[\"1\",\"lugar\",\"Auditorio\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Map<String, Object> s = Datos.requerirSesion(id);
                if (!"PROGRAMADA".equals(s.get("estado"))) throw new ComandoException("Solo se pueden modificar sesiones PROGRAMADAS (estado actual: " + s.get("estado") + ").");
                String campo = Util.clave(c.req(1, "campo")), valor = c.req(2, "valor");
                switch (campo) {
                    case "LUGAR":
                        DatabaseOperations.ejecutar("UPDATE sesion SET lugar = ? WHERE id_sesion = ?", valor, id);
                        break;
                    case "FECHA_HORA":
                        Timestamp t = Util.parseFechaHora(valor);
                        exigirAnticipacion(t);
                        DatabaseOperations.ejecutar("UPDATE sesion SET fecha_hora = ? WHERE id_sesion = ?", t, id);
                        break;
                    case "ORDEN_DIA":
                        DatabaseOperations.ejecutar("DELETE FROM punto_orden_dia WHERE id_sesion = ?", id);
                        guardarOrdenDia(id, valor);
                        break;
                    default:
                        throw new ComandoException("Campo '" + c.req(1, "campo") + "' no modificable. Use: lugar, fecha_hora, orden_dia.");
                }
                return CommandResult.exito("Sesión Nro " + id + " actualizada (" + campo.toLowerCase() + ").");
            });

        Registro.add(cu, "ELIMINARSESION", "id_sesion", 1, 1, CommandDef.ESCRITURA,
            "Cancela una sesión PROGRAMADA (queda registrada como CANCELADA)", "ELIMINARSESION[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Map<String, Object> s = Datos.requerirSesion(id);
                if (!"PROGRAMADA".equals(s.get("estado"))) throw new ComandoException("Solo se cancelan sesiones PROGRAMADAS (estado actual: " + s.get("estado") + ").");
                DatabaseOperations.ejecutar("UPDATE sesion SET estado = 'CANCELADA' WHERE id_sesion = ?", id);
                return CommandResult.exito("Sesión Nro " + id + " CANCELADA.");
            });

        Registro.add(cu, "OBTENEREXCUSAS", "id_sesion", 0, 1, CommandDef.LECTURA,
            "Lista excusas (todas o de una sesión)", "OBTENEREXCUSAS[\"1\"]", c -> {
                String sql = "SELECT e.id_excusa AS \"ID\", e.id_sesion AS \"Sesión\", t.ci AS \"CI Titular\", t.nombres || ' ' || t.apellidos AS \"Titular\", "
                    + "e.motivo AS \"Motivo\", COALESCE(s.nombres || ' ' || s.apellidos, 'SIN SUPLENTE') AS \"Suplente habilitado\", e.fecha_registro AS \"Registrada\" "
                    + "FROM excusa e JOIN usuario t ON t.id_usuario = e.id_titular "
                    + "LEFT JOIN habilitacion h ON h.id_excusa = e.id_excusa LEFT JOIN usuario s ON s.id_usuario = h.id_suplente ";
                List<Map<String, Object>> rows = c.opt(0) == null
                    ? DatabaseOperations.consultar(sql + "ORDER BY e.id_excusa")
                    : DatabaseOperations.consultar(sql + "WHERE e.id_sesion = ? ORDER BY e.id_excusa", c.entero(0, "id_sesion"));
                return CommandResult.exito("Excusas").tabla("Excusas", rows);
            });

        Registro.add(cu, "REGISTRAREXCUSA", "ci_titular,id_sesion,motivo", 3, 3, CommandDef.PROPIO,
            "Registra la excusa de un titular y habilita automáticamente a su suplente para esa sesión",
            "REGISTRAREXCUSA[\"3000001\",\"1\",\"Viaje por comisión de servicio\"]", ModuloSesiones::registrarExcusa);

        Registro.add(cu, "ELIMINAREXCUSA", "id_excusa", 1, 1, CommandDef.ESCRITURA,
            "Anula una excusa (el suplente deja de estar habilitado) mientras la sesión esté PROGRAMADA", "ELIMINAREXCUSA[\"1\"]", c -> {
                int id = c.entero(0, "id_excusa");
                Map<String, Object> e = DatabaseOperations.uno("SELECT e.*, s.estado AS est FROM excusa e JOIN sesion s ON s.id_sesion = e.id_sesion WHERE e.id_excusa = ?", id);
                if (e == null) throw new ComandoException("No existe la excusa Nro " + id + ".");
                if (!"PROGRAMADA".equals(e.get("est"))) throw new ComandoException("La sesión ya no está PROGRAMADA: la excusa no puede anularse.");
                DatabaseOperations.ejecutar("DELETE FROM excusa WHERE id_excusa = ?", id);
                return CommandResult.exito("Excusa Nro " + id + " anulada; el suplente deja de estar habilitado.");
            });

        Registro.add(cu, "OBTENERHABILITADOS", "id_sesion", 1, 1, CommandDef.LECTURA,
            "Quiénes pueden asistir/votar en una sesión (titulares sin excusa, suplentes habilitados y Presidente)",
            "OBTENERHABILITADOS[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Datos.requerirSesion(id);
                List<Map<String, Object>> rows = DatabaseOperations.consultar(
                    "SELECT 'TITULAR' AS \"Condición\", u.ci AS \"CI\", u.nombres || ' ' || u.apellidos AS \"Nombre\", m.carrera AS \"Carrera\" "
                  + "FROM mandato m JOIN usuario u ON u.id_usuario = m.id_titular "
                  + "WHERE m.estado = 'VIGENTE' AND u.estado = 'ACTIVO' AND NOT EXISTS (SELECT 1 FROM excusa e WHERE e.id_sesion = ? AND e.id_titular = m.id_titular) "
                  + "UNION ALL "
                  + "SELECT 'SUPLENTE HABILITADO', u.ci, u.nombres || ' ' || u.apellidos, m.carrera "
                  + "FROM habilitacion h JOIN usuario u ON u.id_usuario = h.id_suplente "
                  + "JOIN mandato m ON m.id_titular = h.id_titular AND m.estado = 'VIGENTE' WHERE h.id_sesion = ? "
                  + "UNION ALL "
                  + "SELECT 'PRESIDENTE', u.ci, u.nombres || ' ' || u.apellidos, '-' "
                  + "FROM usuario u JOIN rol r ON r.id_rol = u.id_rol WHERE r.nombre = 'PRESIDENTE' AND u.estado = 'ACTIVO'", id, id);
                return CommandResult.exito("Habilitados para la sesión Nro " + id).dato("Quórum requerido", Datos.quorumRequerido(Datos.totalEscanos()))
                    .tabla("Habilitados", rows);
            });
    }

    private static void exigirAnticipacion(Timestamp fecha) {
        int h = Datos.anticipacionHoras();
        Timestamp minimo = new Timestamp(System.currentTimeMillis() + h * 3600_000L);
        if (fecha.before(minimo))
            throw new ComandoException("La convocatoria debe hacerse con al menos " + h + " horas de anticipación. La fecha mínima ahora es " + Util.fmt(minimo) + ".");
    }

    private static void guardarOrdenDia(int idSesion, String texto) throws SQLException {
        int orden = 1;
        for (String punto : texto.split(";")) {
            if (punto.trim().isEmpty()) continue;
            DatabaseOperations.ejecutar("INSERT INTO punto_orden_dia (id_sesion, orden, descripcion) VALUES (?,?,?)", idSesion, orden++, punto.trim());
        }
    }

    private static CommandResult registrarSesion(Contexto c) throws Exception {
        Timestamp fecha = Util.parseFechaHora(c.req(0, "fecha_hora"));
        String lugar = c.req(1, "lugar"), tipo = Util.clave(c.req(2, "tipo"));
        if (!tipo.equals("ORDINARIA") && !tipo.equals("EXTRAORDINARIA")) throw new ComandoException("Tipo inválido '" + c.req(2, "tipo") + "'. Use ORDINARIA o EXTRAORDINARIA.");
        exigirAnticipacion(fecha);
        String solicitantes = c.opt(3);
        if (tipo.equals("EXTRAORDINARIA")) {
            if (solicitantes == null)
                throw new ComandoException("Una sesión EXTRAORDINARIA la convoca el Presidente o la solicitan al menos 3 consejeros. Indique en 'solicitantes' el CI del Presidente o 3 CI separados por ;");
            Set<String> cis = new HashSet<>();
            for (String s : solicitantes.split(";")) if (!s.trim().isEmpty()) cis.add(s.trim());
            boolean presidente = false;
            int consejeros = 0;
            for (String ci : cis) {
                Map<String, Object> u = Datos.requerirUsuarioActivo(ci);
                String rol = Datos.rol(u);
                if (rol.equals("PRESIDENTE")) presidente = true;
                else if (rol.startsWith("TITULAR_")) consejeros++;
            }
            if (!presidente && consejeros < 3)
                throw new ComandoException("Solicitud insuficiente: se necesita al Presidente o al menos 3 consejeros titulares (hay " + consejeros + ").");
            solicitantes = String.join(";", cis);
        } else {
            solicitantes = null;
        }
        final String sol = solicitantes;
        final String orden = c.opt(4);
        int id = DatabaseOperations.transaccion(() -> {
            int n = DatabaseOperations.insertar("INSERT INTO sesion (fecha_hora, lugar, tipo, solicitantes) VALUES (?,?,?,?)", "id_sesion", fecha, lugar, tipo, sol);
            if (orden != null) guardarOrdenDia(n, orden);
            return n;
        });
        return CommandResult.exito("Sesión " + tipo + " convocada (Nro " + id + ")")
            .dato("Fecha y hora", fecha).dato("Lugar", lugar).dato("Tipo", tipo)
            .dato("Plazo para recibir documentos", Datos.limiteDocumentos(fecha))
            .dato("Puntos del orden del día", orden == null ? 0 : orden.split(";").length);
    }

    private static CommandResult registrarExcusa(Contexto c) throws Exception {
        Map<String, Object> tit = Datos.requerirUsuarioActivo(c.req(0, "ci_titular"));
        int idSes = c.entero(1, "id_sesion");
        String motivo = c.req(2, "motivo");
        if (!Datos.rol(tit).startsWith("TITULAR_")) throw new ComandoException("Solo los consejeros TITULARES presentan excusa (rol actual: " + Datos.rol(tit) + ").");
        Map<String, Object> m = DatabaseOperations.uno("SELECT * FROM mandato WHERE id_titular = ? AND estado = 'VIGENTE'", tit.get("id_usuario"));
        if (m == null) throw new ComandoException("El titular " + Datos.nombre(tit) + " no tiene un mandato VIGENTE.");
        Map<String, Object> ses = Datos.requerirSesion(idSes);
        if (!"PROGRAMADA".equals(ses.get("estado"))) throw new ComandoException("Solo se presentan excusas para sesiones PROGRAMADAS (estado actual: " + ses.get("estado") + ").");
        if (((Timestamp) ses.get("fecha_hora")).before(new Timestamp(System.currentTimeMillis())))
            throw new ComandoException("La sesión ya pasó de fecha; no se acepta la excusa.");
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM excusa WHERE id_titular = ? AND id_sesion = ?", tit.get("id_usuario"), idSes) > 0)
            throw new ComandoException("El titular ya registró una excusa para la sesión Nro " + idSes + ".");
        Object idSup = m.get("id_suplente");
        Map<String, Object> sup = idSup == null ? null : DatabaseOperations.uno(Datos.SQL_USUARIO + "WHERE u.id_usuario = ?", idSup);
        int idEx = DatabaseOperations.transaccion(() -> {
            int e = DatabaseOperations.insertar("INSERT INTO excusa (id_titular, id_sesion, motivo) VALUES (?,?,?)", "id_excusa", tit.get("id_usuario"), idSes, motivo);
            if (idSup != null)
                DatabaseOperations.ejecutar("INSERT INTO habilitacion (id_sesion, id_excusa, id_titular, id_suplente) VALUES (?,?,?,?)", idSes, e, tit.get("id_usuario"), idSup);
            return e;
        });
        CommandResult r = CommandResult.exito("Excusa registrada (Nro " + idEx + ")")
            .dato("Titular", Datos.nombre(tit)).dato("Sesión", idSes).dato("Motivo", motivo)
            .dato("Suplente habilitado", sup == null ? "NINGUNO" : Datos.nombre(sup));
        if (sup == null) r.dato("Aviso", "El mandato no tiene suplente: el escaño quedará vacío y puede afectar el quórum.");
        return r;
    }
}
