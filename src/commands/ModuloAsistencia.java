package commands;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import config.AppConfig;
import database.DatabaseOperations;
import util.Util;

/** CU5: Asistencia biométrica, verificación de quórum y votaciones. */
final class ModuloAsistencia {

    private ModuloAsistencia() {}

    static void registrar() {
        String cu = "CU5";

        Registro.add(cu, "REGISTRARASISTENCIA", "ci,id_sesion", 2, 2, CommandDef.ESCRITURA,
            "Registra la asistencia (huella del reloj biométrico). Solo cuentan titulares sin excusa, suplentes habilitados y el Presidente",
            "REGISTRARASISTENCIA[\"3000001\",\"1\"]", c -> {
                Map<String, Object> u = Datos.requerirUsuarioActivo(c.req(0, "ci"));
                int idSes = c.entero(1, "id_sesion");
                Map<String, Object> s = Datos.requerirSesion(idSes);
                String est = String.valueOf(s.get("estado"));
                if (!est.equals("PROGRAMADA") && !est.equals("EN_CURSO")) throw new ComandoException("La sesión Nro " + idSes + " está " + est + ": no admite asistencia.");
                boolean[] suplente = new boolean[1];
                String motivo = verificarDerecho(u, idSes, suplente);
                if (motivo != null) throw new ComandoException("No se registra la asistencia de " + Datos.nombre(u) + ": " + motivo);
                if (DatabaseOperations.contar("SELECT COUNT(*) FROM asistencia WHERE id_sesion = ? AND id_usuario = ?", idSes, u.get("id_usuario")) > 0)
                    throw new ComandoException(Datos.nombre(u) + " ya tiene asistencia registrada en la sesión Nro " + idSes + ".");
                DatabaseOperations.ejecutar("INSERT INTO asistencia (id_sesion, id_usuario, como_suplente) VALUES (?,?,?)", idSes, u.get("id_usuario"), suplente[0]);
                return resumenQuorum(CommandResult.exito("Asistencia biométrica registrada: " + Datos.nombre(u) + (suplente[0] ? " (como SUPLENTE)" : "")), idSes);
            });

        Registro.add(cu, "OBTENERASISTENCIA", "id_sesion", 1, 1, CommandDef.LECTURA,
            "Lista de asistentes de una sesión y estado del quórum", "OBTENERASISTENCIA[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Datos.requerirSesion(id);
                CommandResult r = resumenQuorum(CommandResult.exito("Asistencia de la sesión Nro " + id), id);
                return r.tabla("Asistentes", DatabaseOperations.consultar(
                    "SELECT a.hora_ingreso AS \"Hora\", u.ci AS \"CI\", u.nombres || ' ' || u.apellidos AS \"Nombre\", ro.nombre AS \"Rol\", "
                  + "CASE WHEN a.como_suplente THEN 'SUPLENTE' ELSE 'PROPIETARIO' END AS \"Como\" "
                  + "FROM asistencia a JOIN usuario u ON u.id_usuario = a.id_usuario LEFT JOIN rol ro ON ro.id_rol = u.id_rol "
                  + "WHERE a.id_sesion = ? ORDER BY a.hora_ingreso", id));
            });

        Registro.add(cu, "INICIARSESION", "id_sesion", 1, 1, CommandDef.ESCRITURA,
            "Abre la sesión si hay quórum (mayoría absoluta de asistentes). Pasa a EN_CURSO", "INICIARSESION[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Map<String, Object> s = Datos.requerirSesion(id);
                if (!"PROGRAMADA".equals(s.get("estado"))) throw new ComandoException("La sesión Nro " + id + " está " + s.get("estado") + ": solo se inician sesiones PROGRAMADAS.");
                int req = Datos.quorumRequerido(Datos.totalEscanos()), pres = Datos.presentes(id);
                if (pres < req) throw new ComandoException("SIN QUÓRUM: asisten " + pres + " y se necesitan " + req + ". La sesión no puede iniciarse.");
                DatabaseOperations.ejecutar("UPDATE sesion SET estado = 'EN_CURSO', hora_inicio = CURRENT_TIMESTAMP, quorum_requerido = ? WHERE id_sesion = ?", req, id);
                return CommandResult.exito("Sesión Nro " + id + " INICIADA con quórum").dato("Presentes", pres).dato("Quórum requerido", req);
            });

        Registro.add(cu, "FINALIZARSESION", "id_sesion", 1, 1, CommandDef.ESCRITURA,
            "Cierra la sesión EN_CURSO (pasa a FINALIZADA)", "FINALIZARSESION[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Map<String, Object> s = Datos.requerirSesion(id);
                if (!"EN_CURSO".equals(s.get("estado"))) throw new ComandoException("La sesión Nro " + id + " está " + s.get("estado") + ": solo se finalizan sesiones EN_CURSO.");
                DatabaseOperations.ejecutar("UPDATE sesion SET estado = 'FINALIZADA', hora_fin = CURRENT_TIMESTAMP WHERE id_sesion = ?", id);
                return CommandResult.exito("Sesión Nro " + id + " FINALIZADA.");
            });

        Registro.add(cu, "REGISTRARVOTO", "ci,id_sesion,id_documento,voto", 4, 4, CommandDef.PROPIO,
            "Registra el voto de un consejero presente sobre un documento. voto: A_FAVOR, EN_CONTRA o ABSTENCION (un voto por persona)",
            "REGISTRARVOTO[\"3000001\",\"1\",\"1\",\"A_FAVOR\"]", ModuloAsistencia::registrarVoto);

        Registro.add(cu, "OBTENERVOTOS", "id_sesion,id_documento", 2, 2, CommandDef.LECTURA,
            "Votos emitidos sobre un documento y su recuento", "OBTENERVOTOS[\"1\",\"1\"]", c -> {
                int ses = c.entero(0, "id_sesion"), doc = c.entero(1, "id_documento");
                Datos.requerirSesion(ses);
                Datos.requerirDocumento(doc);
                CommandResult r = CommandResult.exito("Votos del documento Nro " + doc + " en la sesión Nro " + ses);
                for (String v : new String[] { "A_FAVOR", "EN_CONTRA", "ABSTENCION" })
                    r.dato(v, DatabaseOperations.contar("SELECT COUNT(*) FROM votacion WHERE id_sesion = ? AND id_documento = ? AND voto = ?", ses, doc, v));
                return r.tabla("Detalle de votos", DatabaseOperations.consultar(
                    "SELECT u.nombres || ' ' || u.apellidos AS \"Consejero\", v.voto AS \"Voto\", v.fecha AS \"Hora\" "
                  + "FROM votacion v JOIN usuario u ON u.id_usuario = v.id_usuario WHERE v.id_sesion = ? AND v.id_documento = ? ORDER BY v.id_votacion", ses, doc));
            });
    }

    private static CommandResult resumenQuorum(CommandResult r, int idSes) throws SQLException {
        int req = Datos.quorumRequerido(Datos.totalEscanos()), pres = Datos.presentes(idSes);
        return r.dato("Presentes", pres).dato("Quórum requerido", req).dato("¿Quórum alcanzado?", pres >= req ? "SI" : "NO (faltan " + (req - pres) + ")");
    }

    /** null = tiene derecho a asistir; si no, devuelve la razón. suplente[0] indica si asiste como suplente. */
    static String verificarDerecho(Map<String, Object> u, int idSes, boolean[] suplente) throws SQLException {
        String rol = Datos.rol(u);
        Object id = u.get("id_usuario");
        if (rol.equals("PRESIDENTE")) return null;
        if (rol.startsWith("TITULAR_")) {
            if (DatabaseOperations.contar("SELECT COUNT(*) FROM mandato WHERE id_titular = ? AND estado = 'VIGENTE'", id) == 0)
                return "no tiene un mandato VIGENTE como titular.";
            if (DatabaseOperations.contar("SELECT COUNT(*) FROM excusa WHERE id_titular = ? AND id_sesion = ?", id, idSes) > 0)
                return "presentó excusa para esta sesión; asiste su suplente.";
            return null;
        }
        if (rol.startsWith("SUPLENTE_")) {
            Map<String, Object> m = DatabaseOperations.uno("SELECT id_titular FROM mandato WHERE id_suplente = ? AND estado = 'VIGENTE'", id);
            if (m == null) return "no es suplente de ningún mandato VIGENTE.";
            if (DatabaseOperations.contar("SELECT COUNT(*) FROM habilitacion WHERE id_sesion = ? AND id_suplente = ?", idSes, id) == 0)
                return "su titular no presentó excusa: el suplente no está habilitado.";
            if (DatabaseOperations.contar("SELECT COUNT(*) FROM asistencia WHERE id_sesion = ? AND id_usuario = ?", idSes, m.get("id_titular")) > 0)
                return "su titular ya registró asistencia.";
            suplente[0] = true;
            return null;
        }
        return "el rol " + rol + " no cuenta para el quórum.";
    }

    private static String normalizarVoto(String v) {
        switch (Util.clave(v)) {
            case "A_FAVOR": case "FAVOR": case "SI": return "A_FAVOR";
            case "EN_CONTRA": case "CONTRA": case "NO": return "EN_CONTRA";
            case "ABSTENCION": case "BLANCO": return "ABSTENCION";
            default: throw new ComandoException("Voto inválido '" + v + "'. Use A_FAVOR, EN_CONTRA o ABSTENCION.");
        }
    }

    private static CommandResult registrarVoto(Contexto c) throws SQLException {
        Map<String, Object> u = Datos.requerirUsuarioActivo(c.req(0, "ci"));
        int idSes = c.entero(1, "id_sesion"), idDoc = c.entero(2, "id_documento");
        String voto = normalizarVoto(c.req(3, "voto"));
        Map<String, Object> s = Datos.requerirSesion(idSes);
        if (!"EN_CURSO".equals(s.get("estado"))) throw new ComandoException("Solo se vota en sesiones EN_CURSO (estado actual: " + s.get("estado") + ").");
        Map<String, Object> d = Datos.requerirDocumento(idDoc);
        if (!Integer.valueOf(idSes).equals(d.get("id_sesion"))) throw new ComandoException("El documento Nro " + idDoc + " no pertenece a la sesión Nro " + idSes + ".");
        if (Boolean.TRUE.equals(d.get("extemporaneo"))) throw new ComandoException("El documento es EXTEMPORÁNEO (llegó fuera del plazo de " + Datos.anticipacionHoras() + " h): no puede votarse en esta sesión.");
        if (AppConfig.getBool("reglas.exigir_workflow_para_votar", true) && !"APROBADO".equals(d.get("estado")))
            throw new ComandoException("El documento está " + d.get("estado") + ": debe completar el flujo de aprobación (AVANZARDOCUMENTO) antes de votarse.");
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM asistencia WHERE id_sesion = ? AND id_usuario = ?", idSes, u.get("id_usuario")) == 0)
            throw new ComandoException(Datos.nombre(u) + " no registró asistencia en la sesión Nro " + idSes + ": no puede votar.");
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM resolucion WHERE id_documento = ?", idDoc) > 0)
            throw new ComandoException("La votación del documento Nro " + idDoc + " ya fue cerrada con una resolución.");
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM votacion WHERE id_documento = ? AND id_usuario = ?", idDoc, u.get("id_usuario")) > 0)
            throw new ComandoException(Datos.nombre(u) + " ya votó sobre el documento Nro " + idDoc + " (el voto no se puede cambiar).");
        DatabaseOperations.ejecutar("INSERT INTO votacion (id_sesion, id_documento, id_usuario, voto) VALUES (?,?,?,?)", idSes, idDoc, u.get("id_usuario"), voto);
        return CommandResult.exito("Voto registrado: " + voto).dato("Consejero", Datos.nombre(u)).dato("Documento", idDoc).dato("Sesión", idSes)
            .dato("Votos emitidos hasta ahora", DatabaseOperations.contar("SELECT COUNT(*) FROM votacion WHERE id_documento = ?", idDoc));
    }
}
