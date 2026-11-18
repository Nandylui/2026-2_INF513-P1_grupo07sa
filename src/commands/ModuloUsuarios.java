package commands;

import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import database.DatabaseOperations;
import util.Util;

/** CU1: Gestión de usuarios y roles.   CU2: Miembros del Consejo (mandatos) y quórum. */
final class ModuloUsuarios {

    private static final Pattern CI = Pattern.compile("^[0-9A-Za-z\\-]{4,20}$");
    private static final Pattern CORREO = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private ModuloUsuarios() {}

    static void registrar() {
        String cu1 = "CU1", cu2 = "CU2";

        // ===================== CU1 =====================
        Registro.add(cu1, "OBTENERROLES", "", 0, 0, CommandDef.LECTURA,
            "Lista los roles del sistema", "OBTENERROLES", c ->
            CommandResult.exito("Roles del sistema").tabla("Roles", DatabaseOperations.consultar(
                "SELECT id_rol AS \"ID\", nombre AS \"Rol\", descripcion AS \"Descripción\" FROM rol ORDER BY id_rol")));

        Registro.add(cu1, "OBTENERUSUARIOS", "filtro", 0, 1, CommandDef.LECTURA,
            "Lista usuarios. Filtro opcional: * (todos), un ROL (ej. SECRETARIO) o un ESTADO (ACTIVO/INACTIVO)",
            "OBTENERUSUARIOS[\"*\"]", ModuloUsuarios::obtenerUsuarios);

        Registro.add(cu1, "OBTENERUSUARIO", "ci", 1, 1, CommandDef.LECTURA,
            "Muestra los datos de un usuario por su CI", "OBTENERUSUARIO[\"9876543\"]", c -> {
                Map<String, Object> u = Datos.requerirUsuario(c.req(0, "ci"));
                return CommandResult.exito("Datos del usuario")
                    .dato("CI", u.get("ci")).dato("Nombre", Datos.nombre(u)).dato("Correo", u.get("correo"))
                    .dato("Rol", u.get("rol")).dato("Estado", u.get("estado"));
            });

        Registro.add(cu1, "REGISTRARUSUARIO", "ci,nombres,apellidos,correo,rol", 5, 5, CommandDef.ESCRITURA,
            "Registra un usuario con su rol (ver OBTENERROLES)",
            "REGISTRARUSUARIO[\"9876543\",\"Maria\",\"Gomez\",\"maria@gmail.com\",\"SECRETARIO\"]", ModuloUsuarios::registrarUsuario);

        Registro.add(cu1, "MODIFICARUSUARIO", "ci,campo,valor", 3, 3, CommandDef.ESCRITURA,
            "Modifica un campo del usuario: nombres, apellidos, correo, rol o estado",
            "MODIFICARUSUARIO[\"9876543\",\"correo\",\"maria.nuevo@gmail.com\"]", ModuloUsuarios::modificarUsuario);

        Registro.add(cu1, "ELIMINARUSUARIO", "ci", 1, 1, CommandDef.ESCRITURA,
            "Da de baja (INACTIVO) a un usuario. No se borra para conservar el historial",
            "ELIMINARUSUARIO[\"9876543\"]", c -> {
                Map<String, Object> u = Datos.requerirUsuario(c.req(0, "ci"));
                exigirSinMandatoVigente(u);
                DatabaseOperations.ejecutar("UPDATE usuario SET estado = 'INACTIVO' WHERE id_usuario = ?", u.get("id_usuario"));
                return CommandResult.exito("Usuario dado de baja (INACTIVO): " + Datos.nombre(u));
            });

        // ===================== CU2 =====================
        Registro.add(cu2, "OBTENERMIEMBROS", "", 0, 0, CommandDef.LECTURA,
            "Lista los miembros vigentes del Consejo (titular y suplente por carrera/estamento)", "OBTENERMIEMBROS", c -> {
                int esc = Datos.totalEscanos();
                CommandResult r = CommandResult.exito("Miembros vigentes del Consejo Facultativo")
                    .dato("Escaños totales", esc).dato("Quórum requerido (mayoría absoluta)", Datos.quorumRequerido(esc));
                r.tabla("Consejeros", DatabaseOperations.consultar(
                    "SELECT m.id_mandato AS \"Mandato\", m.carrera AS \"Carrera\", "
                  + "CASE WHEN rt.nombre LIKE '%DOCENTE' THEN 'DOCENTE' ELSE 'ESTUDIANTE' END AS \"Estamento\", "
                  + "t.nombres || ' ' || t.apellidos AS \"Titular\", COALESCE(s.nombres || ' ' || s.apellidos, '-') AS \"Suplente\", "
                  + "m.fecha_inicio || ' a ' || m.fecha_fin AS \"Vigencia\" "
                  + "FROM mandato m JOIN usuario t ON t.id_usuario = m.id_titular JOIN rol rt ON rt.id_rol = t.id_rol "
                  + "LEFT JOIN usuario s ON s.id_usuario = m.id_suplente WHERE m.estado = 'VIGENTE' ORDER BY m.carrera, 3"));
                r.tabla("Presidente / Decano", DatabaseOperations.consultar(
                    "SELECT u.ci AS \"CI\", u.nombres || ' ' || u.apellidos AS \"Nombre\", u.correo AS \"Correo\" "
                  + "FROM usuario u JOIN rol r ON r.id_rol = u.id_rol WHERE r.nombre = 'PRESIDENTE' AND u.estado = 'ACTIVO'"));
                return r;
            });

        Registro.add(cu2, "OBTENERMANDATOS", "filtro", 0, 1, CommandDef.LECTURA,
            "Lista mandatos (historial). Filtro opcional: * , VIGENTE, CONCLUIDO o una carrera",
            "OBTENERMANDATOS[\"VIGENTE\"]", c -> {
                String f = c.opt(0);
                String sql = "SELECT m.id_mandato AS \"ID\", m.carrera AS \"Carrera\", t.ci AS \"CI Titular\", "
                  + "t.nombres || ' ' || t.apellidos AS \"Titular\", COALESCE(s.nombres || ' ' || s.apellidos, '-') AS \"Suplente\", "
                  + "m.fecha_inicio AS \"Inicio\", m.fecha_fin AS \"Fin\", m.estado AS \"Estado\" "
                  + "FROM mandato m JOIN usuario t ON t.id_usuario = m.id_titular LEFT JOIN usuario s ON s.id_usuario = m.id_suplente ";
                List<Map<String, Object>> rows;
                if (f == null || f.equals("*")) rows = DatabaseOperations.consultar(sql + "ORDER BY m.id_mandato");
                else rows = DatabaseOperations.consultar(sql + "WHERE m.estado = ? OR UPPER(m.carrera) = ? ORDER BY m.id_mandato",
                        Util.clave(f), Util.clave(f).replace('_', ' '));
                return CommandResult.exito("Mandatos").tabla("Mandatos", rows);
            });

        Registro.add(cu2, "REGISTRARMANDATO", "ci_titular,ci_suplente,carrera,fecha_inicio,fecha_fin", 5, 5, CommandDef.ESCRITURA,
            "Registra un mandato de 2 años (titular + suplente de la misma carrera y estamento). Suplente: CI o NINGUNO",
            "REGISTRARMANDATO[\"3000001\",\"3000002\",\"INFORMATICA\",\"2026-01-01\",\"2027-12-31\"]", ModuloUsuarios::registrarMandato);

        Registro.add(cu2, "MODIFICARMANDATO", "id_mandato,campo,valor", 3, 3, CommandDef.ESCRITURA,
            "Modifica un mandato: ci_suplente (o NINGUNO), fecha_fin o estado (VIGENTE/CONCLUIDO)",
            "MODIFICARMANDATO[\"1\",\"ci_suplente\",\"3000002\"]", ModuloUsuarios::modificarMandato);

        Registro.add(cu2, "ELIMINARMANDATO", "id_mandato", 1, 1, CommandDef.ESCRITURA,
            "Concluye un mandato (pasa a CONCLUIDO; se conserva en el historial)", "ELIMINARMANDATO[\"1\"]", c -> {
                int id = c.entero(0, "id_mandato");
                int n = DatabaseOperations.ejecutar("UPDATE mandato SET estado = 'CONCLUIDO' WHERE id_mandato = ?", id);
                if (n == 0) throw new ComandoException("No existe el mandato Nro " + id + ".");
                return CommandResult.exito("Mandato Nro " + id + " CONCLUIDO (queda en el historial).");
            });

        Registro.add(cu2, "CALCULARQUORUM", "id_sesion", 0, 1, CommandDef.LECTURA,
            "Calcula el quórum (mayoría absoluta). Con id_sesion indica además si ya se alcanzó",
            "CALCULARQUORUM[\"1\"]", c -> {
                int esc = Datos.totalEscanos();
                int req = Datos.quorumRequerido(esc);
                CommandResult r = CommandResult.exito("Cálculo de quórum")
                    .dato("Escaños totales", esc).dato("Quórum requerido (mayoría absoluta = mitad + 1)", req);
                if (c.opt(0) != null) {
                    int ses = c.entero(0, "id_sesion");
                    Datos.requerirSesion(ses);
                    int pres = Datos.presentes(ses);
                    r.dato("Sesión Nro", ses).dato("Presentes (asistencia registrada)", pres)
                     .dato("¿Quórum alcanzado?", pres >= req ? "SI" : "NO (faltan " + (req - pres) + ")");
                }
                return r;
            });
    }

    // ---------------------------------------------------------------- CU1
    private static CommandResult obtenerUsuarios(Contexto c) throws SQLException {
        String f = c.opt(0);
        String sql = "SELECT u.id_usuario AS \"ID\", u.ci AS \"CI\", u.nombres || ' ' || u.apellidos AS \"Nombre\", "
            + "u.correo AS \"Correo\", COALESCE(r.nombre, '-') AS \"Rol\", u.estado AS \"Estado\" "
            + "FROM usuario u LEFT JOIN rol r ON r.id_rol = u.id_rol ";
        List<Map<String, Object>> rows;
        if (f == null || f.equals("*")) rows = DatabaseOperations.consultar(sql + "ORDER BY u.id_usuario");
        else rows = DatabaseOperations.consultar(sql + "WHERE r.nombre = ? OR u.estado = ? ORDER BY u.id_usuario", Util.clave(f), Util.clave(f));
        return CommandResult.exito("Usuarios listados").tabla("Usuarios", rows);
    }

    private static CommandResult registrarUsuario(Contexto c) throws SQLException {
        String ci = c.req(0, "ci"), nombres = c.req(1, "nombres"), apellidos = c.req(2, "apellidos");
        String correo = c.req(3, "correo"), rol = Util.clave(c.req(4, "rol"));
        if (!CI.matcher(ci).matches()) throw new ComandoException("CI inválido '" + ci + "' (4 a 20 letras, números o guion).");
        if (!CORREO.matcher(correo).matches()) throw new ComandoException("Correo inválido '" + correo + "'.");
        Map<String, Object> r = DatabaseOperations.uno("SELECT id_rol FROM rol WHERE nombre = ?", rol);
        if (r == null) throw new ComandoException("El rol '" + rol + "' no existe. Use OBTENERROLES para ver los roles válidos.");
        if (Datos.usuario(ci) != null) throw new ComandoException("Ya existe un usuario registrado con el CI " + ci + ".");
        if (Datos.usuarioPorCorreo(correo) != null) throw new ComandoException("Ya existe un usuario registrado con el correo " + correo + ".");
        int id = DatabaseOperations.insertar(
            "INSERT INTO usuario (ci, nombres, apellidos, correo, id_rol) VALUES (?,?,?,?,?)", "id_usuario",
            ci, nombres, apellidos, correo, r.get("id_rol"));
        return CommandResult.exito("Usuario registrado correctamente (ID " + id + ")")
            .dato("CI", ci).dato("Nombre", nombres + " " + apellidos).dato("Correo", correo).dato("Rol", rol);
    }

    private static CommandResult modificarUsuario(Contexto c) throws SQLException {
        Map<String, Object> u = Datos.requerirUsuario(c.req(0, "ci"));
        String campo = Util.clave(c.req(1, "campo")), valor = c.req(2, "valor");
        Object id = u.get("id_usuario");
        switch (campo) {
            case "NOMBRES":   DatabaseOperations.ejecutar("UPDATE usuario SET nombres = ? WHERE id_usuario = ?", valor, id); break;
            case "APELLIDOS": DatabaseOperations.ejecutar("UPDATE usuario SET apellidos = ? WHERE id_usuario = ?", valor, id); break;
            case "CORREO":
                if (!CORREO.matcher(valor).matches()) throw new ComandoException("Correo inválido '" + valor + "'.");
                Map<String, Object> otro = Datos.usuarioPorCorreo(valor);
                if (otro != null && !otro.get("id_usuario").equals(id)) throw new ComandoException("Ese correo ya pertenece a otro usuario.");
                DatabaseOperations.ejecutar("UPDATE usuario SET correo = ? WHERE id_usuario = ?", valor, id);
                break;
            case "ROL":
                Map<String, Object> r = DatabaseOperations.uno("SELECT id_rol FROM rol WHERE nombre = ?", Util.clave(valor));
                if (r == null) throw new ComandoException("El rol '" + valor + "' no existe. Use OBTENERROLES.");
                exigirSinMandatoVigente(u);
                DatabaseOperations.ejecutar("UPDATE usuario SET id_rol = ? WHERE id_usuario = ?", r.get("id_rol"), id);
                break;
            case "ESTADO":
                String e = Util.clave(valor);
                if (!e.equals("ACTIVO") && !e.equals("INACTIVO")) throw new ComandoException("Estado inválido. Use ACTIVO o INACTIVO.");
                if (e.equals("INACTIVO")) exigirSinMandatoVigente(u);
                DatabaseOperations.ejecutar("UPDATE usuario SET estado = ? WHERE id_usuario = ?", e, id);
                break;
            default:
                throw new ComandoException("Campo '" + c.req(1, "campo") + "' no modificable. Use: nombres, apellidos, correo, rol, estado.");
        }
        return CommandResult.exito("Usuario " + Datos.nombre(u) + " actualizado (" + campo.toLowerCase() + ").");
    }

    private static void exigirSinMandatoVigente(Map<String, Object> u) throws SQLException {
        long n = DatabaseOperations.contar(
            "SELECT COUNT(*) FROM mandato WHERE estado = 'VIGENTE' AND (id_titular = ? OR id_suplente = ?)", u.get("id_usuario"), u.get("id_usuario"));
        if (n > 0) throw new ComandoException("El usuario " + Datos.nombre(u) + " tiene un mandato VIGENTE. Concluya el mandato primero (ELIMINARMANDATO).");
    }

    // ---------------------------------------------------------------- CU2
    private static CommandResult registrarMandato(Contexto c) throws Exception {
        Map<String, Object> tit = Datos.requerirUsuarioActivo(c.req(0, "ci_titular"));
        String rolT = Datos.rol(tit);
        if (!rolT.startsWith("TITULAR_")) throw new ComandoException("El usuario " + Datos.nombre(tit) + " tiene rol " + rolT + ": debe ser TITULAR_DOCENTE o TITULAR_ESTUDIANTE.");
        String est = Datos.estamento(rolT);
        String ciS = c.req(1, "ci_suplente");
        Map<String, Object> sup = null;
        if (!Util.clave(ciS).equals("NINGUNO")) {
            sup = Datos.requerirUsuarioActivo(ciS);
            String rolS = Datos.rol(sup);
            if (!rolS.equals("SUPLENTE_" + est)) throw new ComandoException("El suplente debe tener rol SUPLENTE_" + est + " (tiene " + rolS + ").");
        }
        String carrera = Util.clave(c.req(2, "carrera")).replace('_', ' ');
        LocalDate ini = Util.parseFecha(c.req(3, "fecha_inicio")), fin = Util.parseFecha(c.req(4, "fecha_fin"));
        if (!fin.isAfter(ini)) throw new ComandoException("La fecha de fin debe ser posterior a la de inicio.");
        if (fin.isAfter(ini.plusYears(2))) throw new ComandoException("El mandato dura 2 años como máximo (hasta " + ini.plusYears(2) + ").");
        if (DatabaseOperations.contar("SELECT COUNT(*) FROM mandato WHERE id_titular = ?", tit.get("id_usuario")) > 0)
            throw new ComandoException("El usuario " + Datos.nombre(tit) + " ya tuvo un mandato: no se permite la reelección.");
        if (DatabaseOperations.contar(
            "SELECT COUNT(*) FROM mandato m JOIN usuario u ON u.id_usuario = m.id_titular JOIN rol r ON r.id_rol = u.id_rol "
          + "WHERE m.estado = 'VIGENTE' AND UPPER(m.carrera) = ? AND r.nombre = ?", carrera, "TITULAR_" + est) > 0)
            throw new ComandoException("Ya existe un titular VIGENTE del estamento " + est + " para la carrera " + carrera + " (solo 1 titular + 1 suplente).");
        if (sup != null && DatabaseOperations.contar("SELECT COUNT(*) FROM mandato WHERE estado = 'VIGENTE' AND id_suplente = ?", sup.get("id_usuario")) > 0)
            throw new ComandoException("El suplente " + Datos.nombre(sup) + " ya es suplente en otro mandato VIGENTE.");
        int id = DatabaseOperations.insertar(
            "INSERT INTO mandato (id_titular, id_suplente, carrera, fecha_inicio, fecha_fin) VALUES (?,?,?,?,?)", "id_mandato",
            tit.get("id_usuario"), sup == null ? null : sup.get("id_usuario"), carrera, Date.valueOf(ini), Date.valueOf(fin));
        CommandResult r = CommandResult.exito("Mandato registrado (ID " + id + ")")
            .dato("Carrera", carrera).dato("Estamento", est).dato("Titular", Datos.nombre(tit))
            .dato("Suplente", sup == null ? "(sin suplente)" : Datos.nombre(sup)).dato("Vigencia", ini + " a " + fin);
        if (sup == null) r.dato("Aviso", "Sin suplente: si el titular se excusa, no habrá quien lo reemplace.");
        return r;
    }

    private static CommandResult modificarMandato(Contexto c) throws SQLException {
        int id = c.entero(0, "id_mandato");
        Map<String, Object> m = DatabaseOperations.uno("SELECT * FROM mandato WHERE id_mandato = ?", id);
        if (m == null) throw new ComandoException("No existe el mandato Nro " + id + ".");
        String campo = Util.clave(c.req(1, "campo")), valor = c.req(2, "valor");
        switch (campo) {
            case "CI_SUPLENTE":
                if (Util.clave(valor).equals("NINGUNO")) {
                    DatabaseOperations.ejecutar("UPDATE mandato SET id_suplente = NULL WHERE id_mandato = ?", id);
                } else {
                    Map<String, Object> tit = DatabaseOperations.uno(Datos.SQL_USUARIO + "WHERE u.id_usuario = ?", m.get("id_titular"));
                    String est = Datos.estamento(Datos.rol(tit));
                    Map<String, Object> sup = Datos.requerirUsuarioActivo(valor);
                    if (!Datos.rol(sup).equals("SUPLENTE_" + est)) throw new ComandoException("El suplente debe tener rol SUPLENTE_" + est + ".");
                    DatabaseOperations.ejecutar("UPDATE mandato SET id_suplente = ? WHERE id_mandato = ?", sup.get("id_usuario"), id);
                }
                break;
            case "FECHA_FIN":
                LocalDate fin = Util.parseFecha(valor), ini = ((Date) m.get("fecha_inicio")).toLocalDate();
                if (!fin.isAfter(ini) || fin.isAfter(ini.plusYears(2))) throw new ComandoException("La fecha de fin debe estar entre " + ini.plusDays(1) + " y " + ini.plusYears(2) + " (máximo 2 años).");
                DatabaseOperations.ejecutar("UPDATE mandato SET fecha_fin = ? WHERE id_mandato = ?", Date.valueOf(fin), id);
                break;
            case "ESTADO":
                String e = Util.clave(valor);
                if (!e.equals("VIGENTE") && !e.equals("CONCLUIDO")) throw new ComandoException("Estado inválido. Use VIGENTE o CONCLUIDO.");
                DatabaseOperations.ejecutar("UPDATE mandato SET estado = ? WHERE id_mandato = ?", e, id);
                break;
            default:
                throw new ComandoException("Campo '" + c.req(1, "campo") + "' no modificable. Use: ci_suplente, fecha_fin, estado.");
        }
        return CommandResult.exito("Mandato Nro " + id + " actualizado (" + campo.toLowerCase() + ").");
    }
}
