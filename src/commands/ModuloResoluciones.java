package commands;

import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import database.DatabaseOperations;
import util.Util;

/** CU6: Resoluciones (mayorías), promulgación / veto y actas con huella SHA-256. */
final class ModuloResoluciones {

    private static final int DIAS_PROMULGACION = 5;

    private ModuloResoluciones() {}

    static void registrar() {
        String cu = "CU6";

        Registro.add(cu, "EMITIRRESOLUCION", "id_sesion,id_documento,tipo_mayoria,numero", 2, 4, CommandDef.ESCRITURA,
            "Cierra la votación de un documento y emite la resolución. tipo_mayoria: ABSOLUTA (por defecto) o DOS_TERCIOS. numero opcional (si se omite se genera RES-AAAA-###)",
            "EMITIRRESOLUCION[\"1\",\"1\",\"ABSOLUTA\"]", ModuloResoluciones::emitir);

        Registro.add(cu, "OBTENERRESOLUCIONES", "filtro", 0, 1, CommandDef.LECTURA,
            "Lista resoluciones. Filtro opcional: * o estado (EMITIDA, RECHAZADA, PROMULGADA, VETADA, SUPERADO_VETO, PUBLICADA)",
            "OBTENERRESOLUCIONES[\"*\"]", c -> {
                String f = c.opt(0);
                String sql = "SELECT r.id_resolucion AS \"ID\", r.numero AS \"Número\", r.id_sesion AS \"Sesión\", d.titulo AS \"Documento\", r.tipo_mayoria AS \"Mayoría\", "
                    + "r.votos_favor || '/' || r.votos_contra || '/' || r.abstenciones AS \"Favor/Contra/Abst\", r.estado AS \"Estado\", r.fecha_limite AS \"Límite promulgación\" "
                    + "FROM resolucion r JOIN documento d ON d.id_documento = r.id_documento ";
                List<Map<String, Object>> rows = (f == null || f.equals("*"))
                    ? DatabaseOperations.consultar(sql + "ORDER BY r.id_resolucion")
                    : DatabaseOperations.consultar(sql + "WHERE r.estado = ? ORDER BY r.id_resolucion", Util.clave(f));
                return CommandResult.exito("Resoluciones").tabla("Resoluciones", rows);
            });

        Registro.add(cu, "OBTENERRESOLUCION", "id_resolucion", 1, 1, CommandDef.LECTURA,
            "Detalle de una resolución", "OBTENERRESOLUCION[\"1\"]", c -> {
                Map<String, Object> r = Datos.requerirResolucion(c.entero(0, "id_resolucion"));
                return detalle(CommandResult.exito("Resolución " + r.get("numero")), r);
            });

        Registro.add(cu, "PROMULGARRESOLUCION", "id_resolucion,ci_presidente", 2, 2, CommandDef.ESCRITURA,
            "El Presidente promulga una resolución aprobada dentro de " + DIAS_PROMULGACION + " días hábiles (o con el veto superado)",
            "PROMULGARRESOLUCION[\"1\",\"2000001\"]", c -> {
                Map<String, Object> r = Datos.requerirResolucion(c.entero(0, "id_resolucion"));
                Map<String, Object> p = exigirPresidente(c.req(1, "ci_presidente"));
                String est = String.valueOf(r.get("estado"));
                if (est.equals("EMITIDA")) exigirPlazo(r);
                else if (!est.equals("SUPERADO_VETO")) throw new ComandoException("La resolución está " + est + ": solo se promulgan las EMITIDAS o con veto superado.");
                DatabaseOperations.ejecutar("UPDATE resolucion SET estado = 'PROMULGADA', fecha_promulgacion = CURRENT_TIMESTAMP WHERE id_resolucion = ?", r.get("id_resolucion"));
                return CommandResult.exito("Resolución " + r.get("numero") + " PROMULGADA").dato("Promulgada por", Datos.nombre(p))
                    .dato("Siguiente paso", "PUBLICARGACETA[\"" + r.get("id_resolucion") + "\"]");
            });

        Registro.add(cu, "VETARRESOLUCION", "id_resolucion,ci_presidente,motivo", 3, 3, CommandDef.ESCRITURA,
            "El Presidente veta una resolución EMITIDA dentro de " + DIAS_PROMULGACION + " días hábiles",
            "VETARRESOLUCION[\"1\",\"2000001\",\"Contraviene el estatuto\"]", c -> {
                Map<String, Object> r = Datos.requerirResolucion(c.entero(0, "id_resolucion"));
                Map<String, Object> p = exigirPresidente(c.req(1, "ci_presidente"));
                if (!"EMITIDA".equals(r.get("estado"))) throw new ComandoException("Solo se vetan resoluciones EMITIDAS (estado actual: " + r.get("estado") + ").");
                exigirPlazo(r);
                DatabaseOperations.ejecutar("UPDATE resolucion SET estado = 'VETADA', fecha_veto = CURRENT_TIMESTAMP, motivo_veto = ? WHERE id_resolucion = ?", c.req(2, "motivo"), r.get("id_resolucion"));
                return CommandResult.exito("Resolución " + r.get("numero") + " VETADA").dato("Vetada por", Datos.nombre(p)).dato("Motivo", c.req(2, "motivo"))
                    .dato("Siguiente paso", "El Consejo puede superar el veto con 2/3: SUPERARVETO[\"" + r.get("id_resolucion") + "\"]");
            });

        Registro.add(cu, "SUPERARVETO", "id_resolucion", 1, 1, CommandDef.ESCRITURA,
            "Intenta superar el veto: requiere los 2/3 de los presentes a favor", "SUPERARVETO[\"1\"]", c -> {
                Map<String, Object> r = Datos.requerirResolucion(c.entero(0, "id_resolucion"));
                if (!"VETADA".equals(r.get("estado"))) throw new ComandoException("La resolución no está VETADA (estado actual: " + r.get("estado") + ").");
                int favor = ((Number) r.get("votos_favor")).intValue(), pres = ((Number) r.get("presentes")).intValue();
                if (favor * 3 < pres * 2)
                    throw new ComandoException("El veto se MANTIENE: " + favor + " votos a favor de " + pres + " presentes no alcanzan los 2/3 (se necesitan " + ((pres * 2 + 2) / 3) + ").");
                DatabaseOperations.ejecutar("UPDATE resolucion SET estado = 'SUPERADO_VETO' WHERE id_resolucion = ?", r.get("id_resolucion"));
                return CommandResult.exito("Veto SUPERADO en la resolución " + r.get("numero") + " (" + favor + " de " + pres + " ≥ 2/3)")
                    .dato("Siguiente paso", "PROMULGARRESOLUCION[\"" + r.get("id_resolucion") + "\",\"<ci_presidente>\"]");
            });

        Registro.add(cu, "GENERARACTA", "id_sesion,observaciones", 1, 2, CommandDef.ESCRITURA,
            "Genera el acta de una sesión FINALIZADA (asistentes, quórum, documentos, votos y resoluciones) con su huella SHA-256 inalterable",
            "GENERARACTA[\"1\",\"Sin observaciones\"]", ModuloResoluciones::generarActa);

        Registro.add(cu, "OBTENERACTA", "id_sesion", 1, 1, CommandDef.LECTURA,
            "Muestra el acta de una sesión", "OBTENERACTA[\"1\"]", c -> {
                int id = c.entero(0, "id_sesion");
                Map<String, Object> a = DatabaseOperations.uno("SELECT * FROM acta WHERE id_sesion = ?", id);
                if (a == null) throw new ComandoException("La sesión Nro " + id + " no tiene acta. Genérela con GENERARACTA[\"" + id + "\"].");
                return CommandResult.exito("Acta de la sesión Nro " + id).dato("Generada", a.get("fecha_generacion")).dato("SHA-256", a.get("hash_sha256"))
                    .bloque("Contenido", String.valueOf(a.get("contenido")));
            });
    }

    private static CommandResult detalle(CommandResult r, Map<String, Object> x) {
        return r.dato("Número", x.get("numero")).dato("Sesión", x.get("id_sesion")).dato("Documento", x.get("id_documento"))
            .dato("Mayoría exigida", x.get("tipo_mayoria")).dato("A favor", x.get("votos_favor")).dato("En contra", x.get("votos_contra"))
            .dato("Abstenciones", x.get("abstenciones")).dato("Presentes", x.get("presentes")).dato("¿Aprobada?", x.get("aprobada"))
            .dato("Estado", x.get("estado")).dato("Emitida", x.get("fecha_emision")).dato("Límite para promulgar/vetar", x.get("fecha_limite"))
            .dato("Promulgada", x.get("fecha_promulgacion")).dato("Vetada", x.get("fecha_veto")).dato("Motivo del veto", x.get("motivo_veto"));
    }

    private static Map<String, Object> exigirPresidente(String ci) throws SQLException {
        Map<String, Object> p = Datos.requerirUsuarioActivo(ci);
        String rol = Datos.rol(p);
        if (!rol.equals("PRESIDENTE") && !rol.equals("ADMINISTRADOR")) throw new ComandoException("Solo el PRESIDENTE puede hacerlo (" + Datos.nombre(p) + " es " + rol + ").");
        return p;
    }

    private static void exigirPlazo(Map<String, Object> r) {
        Object lim = r.get("fecha_limite");
        if (lim != null && LocalDate.now().isAfter(((Date) lim).toLocalDate()))
            throw new ComandoException("El plazo de " + DIAS_PROMULGACION + " días hábiles venció el " + lim + ".");
    }

    private static CommandResult emitir(Contexto c) throws Exception {
        int idSes = c.entero(0, "id_sesion"), idDoc = c.entero(1, "id_documento");
        String mayoria = c.opt(2) == null ? "ABSOLUTA" : Util.clave(c.opt(2));
        if (!mayoria.equals("ABSOLUTA") && !mayoria.equals("DOS_TERCIOS")) throw new ComandoException("Mayoría inválida. Use ABSOLUTA o DOS_TERCIOS.");
        Map<String, Object> s = Datos.requerirSesion(idSes);
        String est = String.valueOf(s.get("estado"));
        if (!est.equals("EN_CURSO") && !est.equals("FINALIZADA")) throw new ComandoException("La sesión está " + est + ": no hay votaciones que cerrar.");
        Map<String, Object> d = Datos.requerirDocumento(idDoc);
        if (!Integer.valueOf(idSes).equals(d.get("id_sesion"))) throw new ComandoException("El documento Nro " + idDoc + " no pertenece a la sesión Nro " + idSes + ".");
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM resolucion WHERE id_documento = ?", idDoc) > 0) throw new ComandoException("El documento Nro " + idDoc + " ya tiene resolución.");
        int favor = cuenta(idSes, idDoc, "A_FAVOR"), contra = cuenta(idSes, idDoc, "EN_CONTRA"), abst = cuenta(idSes, idDoc, "ABSTENCION");
        if (favor + contra + abst == 0) throw new ComandoException("El documento Nro " + idDoc + " no tiene votos registrados.");
        int pres = Datos.presentes(idSes);
        boolean aprobada = mayoria.equals("ABSOLUTA") ? favor >= pres / 2 + 1 : favor * 3 >= pres * 2;
        String numero = c.opt(3);
        if (numero == null) {
            long n = DatabaseOperations.contar("SELECT COUNT(*) FROM resolucion WHERE numero LIKE ?", "RES-" + LocalDate.now().getYear() + "-%") + 1;
            numero = String.format("RES-%d-%03d", LocalDate.now().getYear(), n);
        }
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM resolucion WHERE numero = ?", numero) > 0) throw new ComandoException("Ya existe una resolución con el número " + numero + ".");
        LocalDate limite = aprobada ? Util.sumarDiasHabiles(LocalDate.now(), DIAS_PROMULGACION) : null;
        int id = DatabaseOperations.insertar(
            "INSERT INTO resolucion (numero, id_sesion, id_documento, tipo_mayoria, votos_favor, votos_contra, abstenciones, presentes, aprobada, estado, fecha_limite) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            "id_resolucion", numero, idSes, idDoc, mayoria, favor, contra, abst, pres, aprobada, aprobada ? "EMITIDA" : "RECHAZADA", limite == null ? null : Date.valueOf(limite));
        int necesarios = mayoria.equals("ABSOLUTA") ? pres / 2 + 1 : (pres * 2 + 2) / 3;
        CommandResult r = CommandResult.exito("Resolución " + numero + (aprobada ? " APROBADA" : " RECHAZADA") + " (ID " + id + ")")
            .dato("Documento", d.get("titulo")).dato("Mayoría exigida", mayoria + " (se necesitan " + necesarios + " de " + pres + " presentes)")
            .dato("A favor", favor).dato("En contra", contra).dato("Abstenciones", abst);
        if (aprobada) r.dato("Plazo para promulgar o vetar", limite + " (" + DIAS_PROMULGACION + " días hábiles)");
        return r;
    }

    private static int cuenta(int ses, int doc, String voto) throws SQLException {
        return (int) DatabaseOperations.contar("SELECT COUNT(*) FROM votacion WHERE id_sesion = ? AND id_documento = ? AND voto = ?", ses, doc, voto);
    }

    static String construirActa(int idSes, String observaciones) throws SQLException {
        Map<String, Object> s = Datos.requerirSesion(idSes);
        StringBuilder sb = new StringBuilder();
        sb.append("ACTA DE SESIÓN ").append(s.get("tipo")).append(" Nro ").append(idSes).append(" DEL CONSEJO FACULTATIVO\n");
        sb.append("Fecha y hora : ").append(Util.fmt(s.get("fecha_hora"))).append("\nLugar        : ").append(s.get("lugar")).append('\n');
        sb.append("Apertura     : ").append(Util.fmt(s.get("hora_inicio"))).append("   Cierre: ").append(Util.fmt(s.get("hora_fin"))).append("\n\n");
        int pres = Datos.presentes(idSes);
        Object req = s.get("quorum_requerido") != null ? s.get("quorum_requerido") : Datos.quorumRequerido(Datos.totalEscanos());
        sb.append("QUÓRUM: ").append(pres).append(" presentes; requerido ").append(req).append(" -> ")
          .append(pres >= ((Number) req).intValue() ? "ALCANZADO" : "NO ALCANZADO").append("\n\nASISTENTES:\n");
        for (Map<String, Object> a : DatabaseOperations.consultar(
            "SELECT u.nombres || ' ' || u.apellidos AS n, r.nombre AS rol, a.como_suplente AS sup FROM asistencia a JOIN usuario u ON u.id_usuario = a.id_usuario "
          + "LEFT JOIN rol r ON r.id_rol = u.id_rol WHERE a.id_sesion = ? ORDER BY a.hora_ingreso", idSes))
            sb.append("  - ").append(a.get("n")).append(" (").append(a.get("rol")).append(Boolean.TRUE.equals(a.get("sup")) ? ", en representación del titular" : "").append(")\n");
        sb.append("\nEXCUSAS PRESENTADAS:\n");
        List<Map<String, Object>> ex = DatabaseOperations.consultar(
            "SELECT u.nombres || ' ' || u.apellidos AS n, e.motivo FROM excusa e JOIN usuario u ON u.id_usuario = e.id_titular WHERE e.id_sesion = ?", idSes);
        if (ex.isEmpty()) sb.append("  (ninguna)\n");
        for (Map<String, Object> e : ex) sb.append("  - ").append(e.get("n")).append(": ").append(e.get("motivo")).append('\n');
        sb.append("\nORDEN DEL DÍA:\n");
        for (Map<String, Object> p : DatabaseOperations.consultar("SELECT orden, descripcion FROM punto_orden_dia WHERE id_sesion = ? ORDER BY orden", idSes))
            sb.append("  ").append(p.get("orden")).append(". ").append(p.get("descripcion")).append('\n');
        sb.append("\nDOCUMENTOS TRATADOS Y RESULTADOS:\n");
        for (Map<String, Object> d : DatabaseOperations.consultar("SELECT id_documento, titulo FROM documento WHERE id_sesion = ? ORDER BY id_documento", idSes)) {
            int id = ((Number) d.get("id_documento")).intValue();
            sb.append("  - Doc ").append(id).append(" \"").append(d.get("titulo")).append("\": A favor ").append(cuenta(idSes, id, "A_FAVOR"))
              .append(", En contra ").append(cuenta(idSes, id, "EN_CONTRA")).append(", Abstenciones ").append(cuenta(idSes, id, "ABSTENCION"));
            Map<String, Object> r = DatabaseOperations.uno("SELECT numero, aprobada, estado FROM resolucion WHERE id_documento = ?", id);
            sb.append(r == null ? " -> sin resolución\n" : " -> " + r.get("numero") + " (" + (Boolean.TRUE.equals(r.get("aprobada")) ? "APROBADA" : "RECHAZADA") + ")\n");
        }
        if (observaciones != null) sb.append("\nOBSERVACIONES: ").append(observaciones).append('\n');
        return sb.toString();
    }

    private static CommandResult generarActa(Contexto c) throws Exception {
        int id = c.entero(0, "id_sesion");
        Map<String, Object> s = Datos.requerirSesion(id);
        if (!"FINALIZADA".equals(s.get("estado"))) throw new ComandoException("El acta se genera con la sesión FINALIZADA (estado actual: " + s.get("estado") + ").");
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM acta WHERE id_sesion = ?", id) > 0)
            throw new ComandoException("La sesión Nro " + id + " ya tiene acta (es inalterable). Use OBTENERACTA o VERIFICARACTA.");
        String contenido = construirActa(id, c.opt(1));
        String hash = Util.sha256(contenido);
        DatabaseOperations.ejecutar("INSERT INTO acta (id_sesion, contenido, hash_sha256) VALUES (?,?,?)", id, contenido, hash);
        return CommandResult.exito("Acta generada para la sesión Nro " + id).dato("SHA-256", hash).bloque("Contenido", contenido);
    }
}
