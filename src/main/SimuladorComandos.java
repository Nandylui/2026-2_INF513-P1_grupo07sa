package main;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import commands.CommandExecutor;
import commands.CommandParser;
import commands.CommandResult;
import commands.Registro;
import database.DatabaseConnection;

/**
 * Prueba los comandos SIN correo (ni IP habilitada): escribe un comando por línea y muestra la respuesta.
 *   - Sin argumentos: modo interactivo (consola de STS).
 *   - Con un archivo:  main.SimuladorComandos database/guion_pruebas.txt
 * Línea opcional "correo@x.com | COMANDO[...]" simula otro remitente. Las líneas con # se ignoran.
 * En archivos: {FH+4} = fecha y hora dentro de 4 días a las 09:00.
 */
public class SimuladorComandos {

    public static void main(String[] args) throws Exception {
        Registro.cargar();
        String remitente = System.getProperty("remitente", "prueba@local.test");
        CommandExecutor ex = new CommandExecutor();
        boolean interactivo = args.length == 0;
        InputStream in = interactivo ? System.in : new FileInputStream(args[0]);
        if (interactivo) System.out.println("Simulador de comandos (BD: " + DatabaseConnection.descripcion() + "). Escriba un comando; 'salir' termina.");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String linea;
            while (true) {
                if (interactivo) System.out.print("> ");
                if ((linea = br.readLine()) == null) break;
                linea = linea.trim();
                if (linea.isEmpty() || linea.startsWith("#")) { if (!interactivo && linea.startsWith("#")) System.out.println("\n" + linea); continue; }
                if (linea.equalsIgnoreCase("salir")) break;
                String rem = remitente;
                int barra = linea.indexOf(" | ");
                if (barra > 0 && linea.substring(0, barra).contains("@")) { rem = linea.substring(0, barra).trim(); linea = linea.substring(barra + 3).trim(); }
                linea = reemplazarFechas(linea);
                System.out.println("\n>>> [" + rem + "] " + linea);
                CommandParser p = new CommandParser(linea);
                CommandResult r;
                try {
                    r = p.isValid() ? ex.ejecutar(p, rem) : CommandResult.error(p.getError());
                } catch (database.DbNoDisponibleException e) {
                    r = CommandResult.error("La base de datos no responde (por correo este comando quedaría en espera y se reintentaría).");
                }
                System.out.println((r.isExito() ? "[OK] " : "[ERROR] ") + r.getMensaje());
                String t = r.toTexto();
                if (!t.isEmpty()) System.out.println(t);
            }
        }
        DatabaseConnection.closeConnection();
    }

    static String reemplazarFechas(String s) {
        Matcher m = Pattern.compile("\\{FH\\+(\\d+)\\}").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, LocalDate.now().plusDays(Integer.parseInt(m.group(1))) + " 09:00");
        m.appendTail(sb);
        return sb.toString();
    }
}
