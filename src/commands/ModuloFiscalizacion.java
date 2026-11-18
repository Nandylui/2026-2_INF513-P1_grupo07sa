package commands;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import database.DatabaseOperations;
import util.Util;

/** CU7: Fiscalizar el cumplimiento, auditar el sistema y publicar en la Gaceta. */
final class ModuloFiscalizacion {

    private ModuloFiscalizacion() {}

    static void registrar() {
        String cu = "CU7";

        Registro.add(cu, "PUBLICARGACETA", "id_resolucion", 1, 1, CommandDef.ESCRITURA,
            "Publica en la Gaceta una resolución PROMULGADA (queda PUBLICADA)", "PUBLICARGACETA[\"1\"]", c -> {
                Map<String, Object> r = Datos.requerirResolucion(c.entero(0, "id_resolucion"));
                if (!"PROMULGADA".equals(r.get("estado"))) throw new ComandoException("Solo se publican resoluciones PROMULGADAS (estado actual: " + r.get("estado") + ").");
                Map<String, Object> d = Datos.requerirDocumento(((Number) r.get("id_documento")).intValue());
                String numero = String.format("GAC-%d-%03d", LocalDate.now().getYear(),
                    DatabaseOperations.contar("SELECT COUNT(*) FROM gaceta WHERE numero LIKE ?", "GAC-" + LocalDate.now().getYear() + "-%") + 1);
                String contenido = "GACETA OFICIAL DEL CONSEJO FACULTATIVO - " + numero + "\n"
                    + "Resolución " + r.get("numero") + " (sesión Nro " + r.get("id_sesion") + ")\n"
                    + "Asunto: " + d.get("titulo") + "\n"
                    + "Votación: " + r.get("votos_favor") + " a favor, " + r.get("votos_contra") + " en contra, " + r.get("abstenciones") + " abstenciones ("
                    + r.get("presentes") + " presentes, mayoría " + r.get("tipo_mayoria") + ")\n"
                    + "Promulgada: " + Util.fmt(r.get("fecha_promulgacion"));
                DatabaseOperations.transaccion(() -> {
                    DatabaseOperations.ejecutar("INSERT INTO gaceta (numero, id_resolucion, contenido) VALUES (?,?,?)", numero, r.get("id_resolucion"), contenido);
                    DatabaseOperations.ejecutar("UPDATE resolucion SET estado = 'PUBLICADA' WHERE id_resolucion = ?", r.get("id_resolucion"));
                    return null;
                });
                return CommandResult.exito("Resolución " + r.get("numero") + " PUBLICADA en la Gaceta " + numero).bloque("Texto publicado", contenido);
            });

        Registro.add(cu, "OBTENERGACETA", "numero", 0, 1, CommandDef.LECTURA,
            "Lista las publicaciones de la Gaceta, o muestra una por su número (ej. GAC-2026-001)", "OBTENERGACETA[\"*\"]", c -> {
                String f = c.opt(0);
                if (f != null && !f.equals("*")) {
                    Map<String, Object> g = DatabaseOperations.uno("SELECT * FROM gaceta WHERE numero = ?", f.toUpperCase());
                    if (g == null) throw new ComandoException("No existe la publicación " + f + " en la Gaceta.");
                    return CommandResult.exito("Gaceta " + g.get("numero")).dato("Publicada", g.get("fecha_publicacion")).bloque("Contenido", String.valueOf(g.get("contenido")));
                }
                return CommandResult.exito("Gaceta del Consejo").tabla("Publicaciones", DatabaseOperations.consultar(
                    "SELECT g.numero AS \"Gaceta\", r.numero AS \"Resolución\", d.titulo AS \"Asunto\", g.fecha_publicacion AS \"Publicada\" "
                  + "FROM gaceta g JOIN resolucion r ON r.id_resolucion = g.id_resolucion JOIN documento d ON d.id_documento = r.id_documento ORDER BY g.id_gaceta"));
            });

        Registro.add(cu, "VERIFICARACTA", "id_sesion", 1, 1, CommandDef.LECTURA,
            "Audita la integridad del acta: recalcula el SHA-256 y lo compara con el guardado", "VERIFICARACTA[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Map<String, Object> a = DatabaseOperations.uno("SELECT * FROM acta WHERE id_sesion = ?", id);
                if (a == null) throw new ComandoException("La sesión Nro " + id + " no tiene acta.");
                String actual = Util.sha256(String.valueOf(a.get("contenido")));
                boolean ok = actual.equals(a.get("hash_sha256"));
                CommandResult r = ok ? CommandResult.exito("Acta ÍNTEGRA: el contenido no fue alterado") : CommandResult.error("¡ACTA ALTERADA! El hash no coincide");
                return r.dato("SHA-256 guardado", a.get("hash_sha256")).dato("SHA-256 recalculado", actual);
            });

        Registro.add(cu, "FISCALIZARSESION", "id_sesion", 1, 1, CommandDef.LECTURA,
            "Fiscaliza el cumplimiento normativo de una sesión (convocatoria, quórum, votos, plazos, acta, Gaceta)",
            "FISCALIZARSESION[\"1\"]", ModuloFiscalizacion::fiscalizar);

        Registro.add(cu, "OBTENERAUDITORIA", "limite,filtro", 0, 2, CommandDef.LECTURA,
            "Bitácora de auditoría: últimos comandos procesados (limite por defecto 20, máx 100). filtro opcional: correo o comando",
            "OBTENERAUDITORIA[\"20\",\"REGISTRARVOTO\"]", c -> {
                int lim = c.opt(0) == null ? 20 : Math.max(1, Math.min(100, c.entero(0, "limite")));
                String f = c.opt(1);
                String sql = "SELECT id_auditoria AS \"ID\", fecha AS \"Fecha\", remitente AS \"Remitente\", comando AS \"Comando\", resultado AS \"Resultado\", "
                    + "LEFT(COALESCE(detalle,''), 80) AS \"Detalle\" FROM auditoria ";
                List<Map<String, Object>> rows = f == null
                    ? DatabaseOperations.consultar(sql + "ORDER BY id_auditoria DESC LIMIT ?", lim)
                    : DatabaseOperations.consultar(sql + "WHERE remitente ILIKE ? OR comando = ? ORDER BY id_auditoria DESC LIMIT ?", "%" + f + "%", Util.clave(f), lim);
                return CommandResult.exito("Auditoría del sistema").tabla("Últimos comandos", rows);
            });
    }

    private static void fila(List<Map<String, Object>> t, String punto, boolean ok, String detalle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Verificación", punto);
        m.put("Resultado", ok ? "CUMPLE" : "OBSERVACIÓN");
        m.put("Detalle", detalle);
        t.add(m);
    }

    private static CommandResult fiscalizar(Contexto c) throws Exception {
        int id = c.entero(0, "id_sesion");
        Map<String, Object> s = Datos.requerirSesion(id);
        List<Map<String, Object>> t = new ArrayList<>();

        fila(t, "Estado de la sesión", "FINALIZADA".equals(s.get("estado")), "Estado: " + s.get("estado"));

        Timestamp conv = (Timestamp) s.get("fecha_convocatoria"), fh = (Timestamp) s.get("fecha_hora");
        if (conv == null) fila(t, "Convocatoria con anticipación", true, "Sin fecha de convocatoria registrada (sesión antigua)");
        else fila(t, "Convocatoria con " + Datos.anticipacionHoras() + " h de anticipación", !conv.after(Datos.limiteDocumentos(fh)),
                "Convocada " + Util.fmt(conv) + " para " + Util.fmt(fh));

        int pres = Datos.presentes(id);
        int req = s.get("quorum_requerido") != null ? ((Number) s.get("quorum_requerido")).intValue() : Datos.quorumRequerido(Datos.totalEscanos());
        fila(t, "Quórum", pres >= req, pres + " presentes / " + req + " requeridos");

        long votosSinAsistencia = DatabaseOperations.contar(
            "SELECT COUNT(*) FROM votacion v WHERE v.id_sesion = ? AND NOT EXISTS (SELECT 1 FROM asistencia a WHERE a.id_sesion = v.id_sesion AND a.id_usuario = v.id_usuario)", id);
        fila(t, "Votos solo de asistentes", votosSinAsistencia == 0, votosSinAsistencia + " voto(s) de personas sin asistencia");

        long extVotados = DatabaseOperations.contar(
            "SELECT COUNT(DISTINCT d.id_documento) FROM documento d JOIN votacion v ON v.id_documento = d.id_documento WHERE d.id_sesion = ? AND d.extemporaneo = TRUE", id);
        fila(t, "Documentos votados dentro de plazo", extVotados == 0, extVotados + " documento(s) extemporáneo(s) fueron votados");

        for (Map<String, Object> r : DatabaseOperations.consultar("SELECT * FROM resolucion WHERE id_sesion = ? ORDER BY id_resolucion", id)) {
            String est = String.valueOf(r.get("estado"));
            Object lim = r.get("fecha_limite");
            boolean vencida = lim != null && LocalDate.now().isAfter(((Date) lim).toLocalDate());
            if (est.equals("EMITIDA")) fila(t, "Resolución " + r.get("numero"), !vencida, vencida ? "Plazo vencido (" + lim + ") sin promulgar ni vetar" : "Pendiente de promulgación hasta " + lim);
            else if (est.equals("PROMULGADA")) fila(t, "Resolución " + r.get("numero"), false, "Promulgada pero aún no publicada en la Gaceta");
            else fila(t, "Resolución " + r.get("numero"), true, "Estado: " + est);
        }

        Map<String, Object> a = DatabaseOperations.uno("SELECT * FROM acta WHERE id_sesion = ?", id);
        if (a == null) fila(t, "Acta", false, "La sesión no tiene acta");
        else fila(t, "Acta íntegra (SHA-256)", Util.sha256(String.valueOf(a.get("contenido"))).equals(a.get("hash_sha256")), "Hash " + String.valueOf(a.get("hash_sha256")).substring(0, 16) + "...");

        long obs = t.stream().filter(m -> "OBSERVACIÓN".equals(m.get("Resultado"))).count();
        return (obs == 0 ? CommandResult.exito("Fiscalización de la sesión Nro " + id + ": CUMPLE todo")
                         : CommandResult.exito("Fiscalización de la sesión Nro " + id + ": " + obs + " observación(es)"))
            .tabla("Resultado de la fiscalización", t);
    }
}
