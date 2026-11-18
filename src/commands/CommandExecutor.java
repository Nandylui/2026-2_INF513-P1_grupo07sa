package commands;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import config.AppConfig;
import database.Auditoria;
import database.DatabaseConnection;
import database.DbNoDisponibleException;
import util.AppLogger;
import util.Util;

/** Valida y ejecuta un comando ya analizado por CommandParser. */
public class CommandExecutor {

    public CommandResult ejecutar(CommandParser parser, String remitente) {
        long t0 = System.currentTimeMillis();
        String nombre = parser.getComando();
        CommandResult res;
        try {
            Registro.cargar();
            CommandDef def = Registro.TODOS.get(nombre);
            if (def == null) {
                res = CommandResult.error(comandoDesconocido(nombre));
            } else {
                int n = parser.getNumeroParametros();
                if (n < def.min || n > def.max) {
                    String esperado = def.min == def.max ? String.valueOf(def.min) : def.min + " a " + def.max;
                    res = CommandResult.error("El comando " + nombre + " recibe " + esperado + " parámetro(s) y usted envió " + n
                            + ".\nUso: " + def.uso() + "\nEjemplo: " + def.ejemplo);
                } else {
                    String denegado = verificarPermiso(def, remitente, parser);
                    if (denegado != null) {
                        res = CommandResult.error(denegado);
                    } else {
                        res = def.handler.ejecutar(new Contexto(remitente, parser.getParametros()));
                    }
                }
            }
        } catch (ComandoException | IllegalArgumentException e) {
            res = CommandResult.error(e.getMessage());
        } catch (DbNoDisponibleException e) {
            throw e;   // el lector NO descarta el correo: se reintenta cuando vuelva el servidor
        } catch (SQLException e) {
            if (DatabaseConnection.esErrorDeConexion(e)) throw new DbNoDisponibleException("Base de datos no disponible", e);
            AppLogger.error("Error SQL en " + nombre, e);
            res = CommandResult.error("Error de base de datos: " + resumirSql(e));
        } catch (Exception e) {
            AppLogger.error("Error inesperado en " + nombre, e);
            res = CommandResult.error("Error inesperado: " + e.getMessage());
        }
        long ms = System.currentTimeMillis() - t0;
        AppLogger.access(remitente, parser.getComandoOriginal(), res.isExito() ? "EXITO" : "ERROR: " + res.getMensaje(), ms);
        Auditoria.registrar(remitente, nombre, String.join(" | ", parser.getParametros()), res.isExito(), res.getMensaje());
        return res;
    }

    private static String resumirSql(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        int i = m.indexOf('\n');
        return i > 0 ? m.substring(0, i) : m;
    }

    private static String comandoDesconocido(String nombre) {
        List<String> parecidos = new ArrayList<>();
        for (String k : Registro.TODOS.keySet())
            if (Util.distancia(nombre, k) <= 3 || k.contains(nombre) || nombre.contains(k)) parecidos.add(k);
        StringBuilder sb = new StringBuilder("El comando '" + nombre + "' no existe.");
        if (!parecidos.isEmpty()) sb.append("\n¿Quiso decir?: ").append(String.join(", ", parecidos.subList(0, Math.min(5, parecidos.size()))));
        sb.append("\nEnvíe HELP para ver todos los comandos.");
        return sb.toString();
    }

    /** null = permitido. Solo se aplica si seguridad.validar_remitente=true. */
    private String verificarPermiso(CommandDef def, String remitente, CommandParser parser) throws SQLException {
        if (!AppConfig.getBool("seguridad.validar_remitente", false)) return null;
        if (def.nombre.equals("HELP")) return null;
        Map<String, Object> u = Datos.usuarioPorCorreo(remitente);
        if (u == null || !"ACTIVO".equals(u.get("estado")))
            return "Acceso denegado: el correo " + remitente + " no está registrado como usuario ACTIVO del Consejo.";
        String rol = Datos.rol(u);
        switch (def.permiso) {
            case CommandDef.LECTURA:
                return null;
            case CommandDef.ESCRITURA:
                return Datos.esStaff(rol) ? null : "Acceso denegado: el rol " + rol + " no puede ejecutar " + def.nombre + ".";
            case CommandDef.PROPIO:
                if (Datos.esStaff(rol)) return null;
                String ci = parser.getParametros().isEmpty() ? "" : parser.getParametros().get(0);
                return String.valueOf(u.get("ci")).equals(ci) ? null
                        : "Acceso denegado: solo puede usar " + def.nombre + " con su propio CI.";
            case CommandDef.ADMIN:
                return "ADMINISTRADOR".equals(rol) ? null : "Acceso denegado: solo el ADMINISTRADOR puede ejecutar " + def.nombre + ".";
            default:
                return null;
        }
    }
}
