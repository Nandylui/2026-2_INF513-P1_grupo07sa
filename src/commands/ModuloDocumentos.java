package commands;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import database.DatabaseOperations;
import util.Util;

/** CU4: Repositorio documental, recepción con plazo de 48 h, versiones y flujo de trabajo (workflow). */
final class ModuloDocumentos {

    private static final String LISTA =
        "SELECT d.id_documento AS \"ID\", d.titulo AS \"Título\", d.id_sesion AS \"Sesión\", d.estado AS \"Estado\", "
      + "COALESCE(t.nombre, 'GENERAL') AS \"Trámite\", COALESCE(d.paso_actual, 1) AS \"Paso\", d.extemporaneo AS \"Extemporáneo\", "
      + "d.fecha_recepcion AS \"Recibido\" FROM documento d LEFT JOIN tipo_tramite t ON t.id_tramite = d.id_tramite ";

    private ModuloDocumentos() {}

    static void registrar() {
        String cu = "CU4";

        Registro.add(cu, "OBTENERTRAMITES", "", 0, 0, CommandDef.LECTURA,
            "Lista los tipos de trámite y sus pasos de aprobación (workflow)", "OBTENERTRAMITES", c ->
            CommandResult.exito("Tipos de trámite y flujo de aprobación").tabla("Flujo", DatabaseOperations.consultar(
                "SELECT t.nombre AS \"Trámite\", p.orden AS \"Paso\", p.nombre AS \"Actividad\", p.rol_responsable AS \"Responsable\" "
              + "FROM tipo_tramite t JOIN flujo_paso p ON p.id_tramite = t.id_tramite ORDER BY t.nombre, p.orden")));

        Registro.add(cu, "OBTENERDOCUMENTOS", "filtro", 0, 1, CommandDef.LECTURA,
            "Lista documentos. Filtro opcional: * , un ESTADO, EXTEMPORANEO o el número de una sesión",
            "OBTENERDOCUMENTOS[\"RECIBIDO\"]", c -> {
                String f = c.opt(0);
                List<Map<String, Object>> rows;
                if (f == null || f.equals("*")) rows = DatabaseOperations.consultar(LISTA + "ORDER BY d.id_documento");
                else if (f.matches("\\d+")) rows = DatabaseOperations.consultar(LISTA + "WHERE d.id_sesion = ? ORDER BY d.id_documento", Integer.parseInt(f));
                else if (Util.clave(f).equals("EXTEMPORANEO")) rows = DatabaseOperations.consultar(LISTA + "WHERE d.extemporaneo = TRUE ORDER BY d.id_documento");
                else rows = DatabaseOperations.consultar(LISTA + "WHERE d.estado = ? ORDER BY d.id_documento", Util.clave(f));
                return CommandResult.exito("Documentos del repositorio").tabla("Documentos", rows);
            });

        Registro.add(cu, "OBTENERDOCUMENTO", "id_documento", 1, 1, CommandDef.LECTURA,
            "Detalle de un documento: datos, paso actual del flujo y versiones", "OBTENERDOCUMENTO[\"1\"]", c -> {
                int id = c.entero(0, "id_documento");
                Datos.requerirDocumento(id);
                Map<String, Object> d = DatabaseOperations.uno(
                    "SELECT d.*, u.nombres || ' ' || u.apellidos AS remitente, COALESCE(t.nombre,'GENERAL') AS tramite, "
                  + "(SELECT COUNT(*) FROM documento_version v WHERE v.id_documento = d.id_documento) AS versiones, "
                  + "(SELECT p.nombre || ' (' || p.rol_responsable || ')' FROM flujo_paso p WHERE p.id_tramite = COALESCE(d.id_tramite, (SELECT id_tramite FROM tipo_tramite WHERE nombre = 'GENERAL')) "
                  + "  AND p.orden = COALESCE(d.paso_actual, 1)) AS paso_pendiente "
                  + "FROM documento d LEFT JOIN usuario u ON u.id_usuario = d.id_remitente LEFT JOIN tipo_tramite t ON t.id_tramite = d.id_tramite WHERE d.id_documento = ?", id);
                return CommandResult.exito("Documento Nro " + id)
                    .dato("Título", d.get("titulo")).dato("Remitente", d.get("remitente")).dato("Sesión", d.get("id_sesion"))
                    .dato("Estado", d.get("estado")).dato("Trámite", d.get("tramite"))
                    .dato("Paso pendiente", "APROBADO".equals(d.get("estado")) ? "(flujo completo)" : d.get("paso_pendiente"))
                    .dato("Recibido", d.get("fecha_recepcion")).dato("Extemporáneo (fuera de plazo)", d.get("extemporaneo"))
                    .dato("Versiones", d.get("versiones")).dato("Descripción", d.get("descripcion"));
            });

        Registro.add(cu, "REGISTRARDOCUMENTO", "titulo,ci_remitente,id_sesion,tramite,contenido", 3, 5, CommandDef.ESCRITURA,
            "Recibe un documento para una sesión. Si llega con menos de " + Datos.anticipacionHoras() + " h de anticipación queda como EXTEMPORÁNEO. "
          + "tramite (opcional): GENERAL, RESOLUCION, INFORME. contenido (opcional): crea la versión 1 con su hash SHA-256",
            "REGISTRARDOCUMENTO[\"Proyecto de reglamento\",\"2000002\",\"1\",\"RESOLUCION\",\"Texto del proyecto...\"]",
            ModuloDocumentos::registrarDocumento);

        Registro.add(cu, "MODIFICARDOCUMENTO", "id_documento,campo,valor", 3, 3, CommandDef.ESCRITURA,
            "Modifica titulo, descripcion o id_sesion (reprograma el documento a otra sesión) mientras no esté APROBADO/RECHAZADO",
            "MODIFICARDOCUMENTO[\"1\",\"titulo\",\"Nuevo título\"]", c -> {
                int id = c.entero(0, "id_documento");
                Map<String, Object> d = Datos.requerirDocumento(id);
                String est = String.valueOf(d.get("estado"));
                if (!est.equals("RECIBIDO") && !est.equals("EN_REVISION")) throw new ComandoException("No se puede modificar un documento en estado " + est + ".");
                String campo = Util.clave(c.req(1, "campo")), valor = c.req(2, "valor");
                switch (campo) {
                    case "TITULO": DatabaseOperations.ejecutar("UPDATE documento SET titulo = ? WHERE id_documento = ?", valor, id); break;
                    case "DESCRIPCION": DatabaseOperations.ejecutar("UPDATE documento SET descripcion = ? WHERE id_documento = ?", valor, id); break;
                    case "ID_SESION":
                        int ns = Util.parseEntero(valor, "id_sesion");
                        Map<String, Object> s = Datos.requerirSesion(ns);
                        if (!"PROGRAMADA".equals(s.get("estado"))) throw new ComandoException("La sesión destino debe estar PROGRAMADA.");
                        boolean ext = new Timestamp(System.currentTimeMillis()).after(Datos.limiteDocumentos((Timestamp) s.get("fecha_hora")));
                        DatabaseOperations.ejecutar("UPDATE documento SET id_sesion = ?, extemporaneo = ? WHERE id_documento = ?", ns, ext, id);
                        return CommandResult.exito("Documento Nro " + id + " reprogramado a la sesión " + ns + (ext ? " (queda EXTEMPORÁNEO)." : "."));
                    default: throw new ComandoException("Campo '" + c.req(1, "campo") + "' no modificable. Use: titulo, descripcion, id_sesion.");
                }
                return CommandResult.exito("Documento Nro " + id + " actualizado (" + campo.toLowerCase() + ").");
            });

        Registro.add(cu, "ELIMINARDOCUMENTO", "id_documento", 1, 1, CommandDef.ESCRITURA,
            "Anula un documento (estado ANULADO) si todavía no tiene votos", "ELIMINARDOCUMENTO[\"1\"]", c -> {
                int id = c.entero(0, "id_documento");
                Map<String, Object> d = Datos.requerirDocumento(id);
                if (DatabaseOperations.contar("SELECT COUNT(*) FROM votacion WHERE id_documento = ?", id) > 0)
                    throw new ComandoException("El documento ya tiene votos registrados: no puede anularse.");
                cambiarEstado(id, String.valueOf(d.get("estado")), "ANULADO", "ANULACION", "Anulado por correo", null);
                return CommandResult.exito("Documento Nro " + id + " ANULADO.");
            });

        Registro.add(cu, "AVANZARDOCUMENTO", "id_documento,ci_responsable,observacion", 2, 3, CommandDef.ESCRITURA,
            "Workflow: completa el paso pendiente del trámite (lo debe hacer el rol responsable). Al terminar el último paso el documento queda APROBADO",
            "AVANZARDOCUMENTO[\"1\",\"2000002\",\"Revisado sin observaciones\"]", ModuloDocumentos::avanzar);

        Registro.add(cu, "RECHAZARDOCUMENTO", "id_documento,ci_responsable,motivo", 3, 3, CommandDef.ESCRITURA,
            "Workflow: rechaza el documento en el paso pendiente (queda RECHAZADO)",
            "RECHAZARDOCUMENTO[\"1\",\"2000002\",\"Falta firma del decano\"]", c -> {
                int id = c.entero(0, "id_documento");
                Map<String, Object> d = Datos.requerirDocumento(id);
                Map<String, Object> resp = verificarResponsable(d, c.req(1, "ci_responsable"));
                cambiarEstado(id, String.valueOf(d.get("estado")), "RECHAZADO", String.valueOf(resp.get("paso")), c.req(2, "motivo"), resp.get("id_usuario"));
                return CommandResult.exito("Documento Nro " + id + " RECHAZADO en el paso " + resp.get("paso") + ".").dato("Motivo", c.req(2, "motivo"));
            });

        Registro.add(cu, "REGISTRARVERSION", "id_documento,contenido", 2, 2, CommandDef.ESCRITURA,
            "Guarda una nueva versión del documento con su huella SHA-256", "REGISTRARVERSION[\"1\",\"Texto actualizado...\"]", c -> {
                int id = c.entero(0, "id_documento");
                Datos.requerirDocumento(id);
                String contenido = c.req(1, "contenido");
                int v = (int) DatabaseOperations.contar("SELECT COALESCE(MAX(version),0) + 1 FROM documento_version WHERE id_documento = ?", id);
                String hash = Util.sha256(contenido);
                DatabaseOperations.ejecutar("INSERT INTO documento_version (id_documento, version, contenido, hash_sha256) VALUES (?,?,?,?)", id, v, contenido, hash);
                return CommandResult.exito("Versión " + v + " registrada para el documento Nro " + id).dato("SHA-256", hash);
            });

        Registro.add(cu, "OBTENERVERSIONES", "id_documento", 1, 1, CommandDef.LECTURA,
            "Lista las versiones de un documento", "OBTENERVERSIONES[\"1\"]", c -> {
                int id = c.entero(0, "id_documento");
                Datos.requerirDocumento(id);
                return CommandResult.exito("Versiones del documento Nro " + id).tabla("Versiones", DatabaseOperations.consultar(
                    "SELECT version AS \"Versión\", fecha AS \"Fecha\", hash_sha256 AS \"SHA-256\", LEFT(contenido, 60) AS \"Contenido (inicio)\" "
                  + "FROM documento_version WHERE id_documento = ? ORDER BY version", id));
            });

        Registro.add(cu, "OBTENERHISTORIALDOCUMENTO", "id_documento", 1, 1, CommandDef.LECTURA,
            "Trazabilidad del documento: cambios de estado y quién los hizo", "OBTENERHISTORIALDOCUMENTO[\"1\"]", c -> {
                int id = c.entero(0, "id_documento");
                Datos.requerirDocumento(id);
                return CommandResult.exito("Historial del documento Nro " + id).tabla("Historial", DatabaseOperations.consultar(
                    "SELECT h.fecha AS \"Fecha\", COALESCE(h.estado_anterior,'-') AS \"Estado anterior\", h.estado_nuevo AS \"Estado nuevo\", "
                  + "h.paso AS \"Paso\", COALESCE(u.nombres || ' ' || u.apellidos, '-') AS \"Responsable\", COALESCE(h.observacion,'') AS \"Observación\" "
                  + "FROM documento_historial h LEFT JOIN usuario u ON u.id_usuario = h.id_usuario WHERE h.id_documento = ? ORDER BY h.id_historial", id));
            });
    }

    private static void cambiarEstado(int id, String anterior, String nuevo, String paso, String obs, Object idUsuario) throws SQLException {
        DatabaseOperations.ejecutar("UPDATE documento SET estado = ? WHERE id_documento = ?", nuevo, id);
        DatabaseOperations.ejecutar(
            "INSERT INTO documento_historial (id_documento, estado_anterior, estado_nuevo, paso, observacion, id_usuario) VALUES (?,?,?,?,?,?)",
            id, anterior, nuevo, paso, obs, idUsuario);
    }

    private static CommandResult registrarDocumento(Contexto c) throws Exception {
        String titulo = c.req(0, "titulo");
        Map<String, Object> rem = Datos.requerirUsuarioActivo(c.req(1, "ci_remitente"));
        int idSes = c.entero(2, "id_sesion");
        Map<String, Object> ses = Datos.requerirSesion(idSes);
        if (!"PROGRAMADA".equals(ses.get("estado"))) throw new ComandoException("Solo se reciben documentos para sesiones PROGRAMADAS (estado actual: " + ses.get("estado") + ").");
        String nomTramite = c.opt(3) == null ? "GENERAL" : Util.clave(c.opt(3));
        Map<String, Object> tr = DatabaseOperations.uno("SELECT id_tramite FROM tipo_tramite WHERE nombre = ?", nomTramite);
        if (tr == null) throw new ComandoException("El trámite '" + nomTramite + "' no existe. Use OBTENERTRAMITES.");
        Timestamp limite = Datos.limiteDocumentos((Timestamp) ses.get("fecha_hora"));
        boolean ext = new Timestamp(System.currentTimeMillis()).after(limite);
        String contenido = c.opt(4);
        int id = DatabaseOperations.transaccion(() -> {
            int n = DatabaseOperations.insertar(
                "INSERT INTO documento (titulo, id_remitente, id_sesion, id_tramite, paso_actual, extemporaneo) VALUES (?,?,?,?,1,?)", "id_documento",
                titulo, rem.get("id_usuario"), idSes, tr.get("id_tramite"), ext);
            DatabaseOperations.ejecutar(
                "INSERT INTO documento_historial (id_documento, estado_anterior, estado_nuevo, paso, observacion, id_usuario) VALUES (?,NULL,'RECIBIDO','RECEPCION',?,?)",
                n, ext ? "Recibido fuera de plazo" : "Recibido en plazo", rem.get("id_usuario"));
            if (contenido != null)
                DatabaseOperations.ejecutar("INSERT INTO documento_version (id_documento, version, contenido, hash_sha256) VALUES (?,1,?,?)", n, contenido, Util.sha256(contenido));
            return n;
        });
        CommandResult r = CommandResult.exito("Documento recibido (Nro " + id + ")")
            .dato("Título", titulo).dato("Remitente", Datos.nombre(rem)).dato("Sesión", idSes).dato("Trámite", nomTramite)
            .dato("Plazo de recepción", limite).dato("Estado", "RECIBIDO");
        if (ext) r.dato("AVISO", "EXTEMPORÁNEO: llegó con menos de " + Datos.anticipacionHoras() + " h de anticipación y no podrá votarse en esa sesión. Use MODIFICARDOCUMENTO[\"" + id + "\",\"id_sesion\",\"<otra sesión>\"] para reprogramarlo.");
        if (contenido != null) r.dato("Versión 1 (SHA-256)", Util.sha256(contenido));
        return r;
    }

    /** Verifica que el CI corresponda al rol responsable del paso pendiente. Devuelve usuario + nombre del paso. */
    private static Map<String, Object> verificarResponsable(Map<String, Object> doc, String ci) throws SQLException {
        String est = String.valueOf(doc.get("estado"));
        if (!est.equals("RECIBIDO") && !est.equals("EN_REVISION")) throw new ComandoException("El documento está " + est + ": ya no tiene pasos pendientes.");
        Map<String, Object> u = Datos.requerirUsuarioActivo(ci);
        Object idTr = doc.get("id_tramite");
        if (idTr == null) idTr = DatabaseOperations.uno("SELECT id_tramite FROM tipo_tramite WHERE nombre = 'GENERAL'").get("id_tramite");
        int paso = doc.get("paso_actual") == null ? 1 : ((Number) doc.get("paso_actual")).intValue();
        Map<String, Object> p = DatabaseOperations.uno("SELECT nombre, rol_responsable, orden FROM flujo_paso WHERE id_tramite = ? AND orden = ?", idTr, paso);
        if (p == null) throw new ComandoException("El flujo del trámite no tiene el paso " + paso + ".");
        String rolU = Datos.rol(u);
        if (!rolU.equals(p.get("rol_responsable")) && !rolU.equals("ADMINISTRADOR"))
            throw new ComandoException("El paso pendiente '" + p.get("nombre") + "' corresponde al rol " + p.get("rol_responsable") + " y " + Datos.nombre(u) + " es " + rolU + ".");
        u.put("paso", p.get("nombre"));
        u.put("orden", p.get("orden"));
        u.put("idTramite", idTr);
        return u;
    }

    private static CommandResult avanzar(Contexto c) throws Exception {
        int id = c.entero(0, "id_documento");
        Map<String, Object> d = Datos.requerirDocumento(id);
        Map<String, Object> u = verificarResponsable(d, c.req(1, "ci_responsable"));
        int paso = ((Number) u.get("orden")).intValue();
        long total = DatabaseOperations.contar("SELECT MAX(orden) FROM flujo_paso WHERE id_tramite = ?", u.get("idTramite"));
        String anterior = String.valueOf(d.get("estado"));
        boolean ultimo = paso >= total;
        String nuevo = ultimo ? "APROBADO" : "EN_REVISION";
        DatabaseOperations.transaccion(() -> {
            DatabaseOperations.ejecutar("UPDATE documento SET paso_actual = ?, id_tramite = ? WHERE id_documento = ?", paso + 1, u.get("idTramite"), id);
            cambiarEstado(id, anterior, nuevo, String.valueOf(u.get("paso")), c.opt(2), u.get("id_usuario"));
            return null;
        });
        CommandResult r = CommandResult.exito("Paso '" + u.get("paso") + "' completado en el documento Nro " + id)
            .dato("Estado", nuevo).dato("Responsable", Datos.nombre(u));
        if (ultimo) r.dato("Resultado", "Flujo completo: el documento queda listo para votación en la sesión " + d.get("id_sesion") + ".");
        else r.dato("Siguiente paso", (paso + 1) + " de " + total);
        if (Boolean.TRUE.equals(d.get("extemporaneo"))) r.dato("AVISO", "El documento es EXTEMPORÁNEO: no podrá votarse hasta reprogramarlo.");
        return r;
    }
}
