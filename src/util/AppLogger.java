package util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import config.AppConfig;

/** Bitácoras del sistema: logs/access.log (comandos recibidos) y logs/error.log (fallos). */
public final class AppLogger {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DIR = AppConfig.get("logs.carpeta", "logs");

    private AppLogger() {}

    public static synchronized void access(String remitente, String comando, String resultado, long ms) {
        escribir("access.log", String.format("%s | %s | %s | %s | %d ms",
                LocalDateTime.now().format(F), nz(remitente), nz(comando), resultado, ms));
    }

    public static void info(String msg) {
        System.out.println("[" + LocalDateTime.now().format(F) + "] " + msg);
    }

    public static synchronized void error(String contexto, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(LocalDateTime.now().format(F)).append(" | ").append(contexto);
        if (t != null) {
            sb.append(" | ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(System.lineSeparator()).append(sw);
        }
        escribir("error.log", sb.toString());
        System.err.println("[ERROR] " + contexto + (t != null ? " -> " + t.getMessage() : ""));
    }

    private static String nz(String s) {
        if (s == null) return "-";
        String x = s.replace('\n', ' ').replace('\r', ' ');
        return x.length() > 300 ? x.substring(0, 300) + "..." : x;
    }

    private static void escribir(String archivo, String linea) {
        try {
            File d = new File(DIR);
            if (!d.exists()) d.mkdirs();
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(d, archivo), true), StandardCharsets.UTF_8))) {
                pw.println(linea);
            }
        } catch (Exception e) {
            System.err.println("No se pudo escribir " + archivo + ": " + e.getMessage());
        }
    }
}
