package commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import util.Util;

/** Resultado de un comando: estado + mensaje + secciones (pares clave/valor, tablas, bloques de texto). */
public class CommandResult {

    private final boolean exito;
    private final String mensaje;
    private final List<Object> secciones = new ArrayList<>();

    private CommandResult(boolean exito, String mensaje) {
        this.exito = exito;
        this.mensaje = mensaje;
    }

    public static CommandResult exito(String mensaje) { return new CommandResult(true, mensaje); }
    public static CommandResult error(String mensaje) { return new CommandResult(false, mensaje); }

    public boolean isExito() { return exito; }
    public String getMensaje() { return mensaje; }

    // ---- construcción ----
    private static class Pares { final List<String[]> items = new ArrayList<>(); }
    public static class Tabla {
        public final String titulo; public final List<String> cols = new ArrayList<>(); public final List<List<String>> filas = new ArrayList<>();
        Tabla(String t) { titulo = t; }
    }
    private static class Bloque { final String titulo, texto; Bloque(String a, String b) { titulo = a; texto = b; } }

    public CommandResult dato(String clave, Object valor) {
        Pares p;
        if (!secciones.isEmpty() && secciones.get(secciones.size() - 1) instanceof Pares) p = (Pares) secciones.get(secciones.size() - 1);
        else { p = new Pares(); secciones.add(p); }
        p.items.add(new String[] { clave, Util.fmt(valor) });
        return this;
    }

    public CommandResult tabla(String titulo, List<Map<String, Object>> filas) {
        Tabla t = new Tabla(titulo);
        if (!filas.isEmpty()) t.cols.addAll(filas.get(0).keySet());
        for (Map<String, Object> f : filas) {
            List<String> r = new ArrayList<>();
            for (Object v : f.values()) r.add(Util.fmt(v));
            t.filas.add(r);
        }
        secciones.add(t);
        return this;
    }

    public CommandResult bloque(String titulo, String texto) {
        secciones.add(new Bloque(titulo, texto));
        return this;
    }

    // ---- salida TEXTO ----
    public String toTexto() {
        StringBuilder sb = new StringBuilder();
        for (Object o : secciones) {
            if (o instanceof Pares) {
                int w = 0;
                for (String[] kv : ((Pares) o).items) w = Math.max(w, kv[0].length());
                for (String[] kv : ((Pares) o).items) sb.append(String.format("  %-" + w + "s : %s%n", kv[0], kv[1]));
                sb.append('\n');
            } else if (o instanceof Tabla) {
                Tabla t = (Tabla) o;
                sb.append(t.titulo).append(" (").append(t.filas.size()).append(")\n");
                if (t.filas.isEmpty()) { sb.append("  (sin registros)\n\n"); continue; }
                int[] w = new int[t.cols.size()];
                for (int i = 0; i < w.length; i++) {
                    w[i] = t.cols.get(i).length();
                    for (List<String> f : t.filas) w[i] = Math.max(w[i], Math.min(f.get(i).length(), 40));
                }
                sb.append(linea(t.cols, w)).append('\n');
                StringBuilder sep = new StringBuilder();
                for (int x : w) { for (int k = 0; k < x; k++) sep.append('-'); sep.append("-+-"); }
                sb.append(sep, 0, sep.length() - 3).append('\n');
                for (List<String> f : t.filas) sb.append(linea(f, w)).append('\n');
                sb.append('\n');
            } else {
                Bloque b = (Bloque) o;
                sb.append(b.titulo).append('\n').append(b.texto).append("\n\n");
            }
        }
        return sb.toString().replaceAll("^\\n+", "").replaceAll("\\s+$", "");
    }

    private static String linea(List<String> celdas, int[] w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < celdas.size(); i++) {
            String c = celdas.get(i);
            if (c.length() > 40) c = c.substring(0, 37) + "...";
            sb.append(String.format("%-" + w[i] + "s", c));
            if (i < celdas.size() - 1) sb.append(" | ");
        }
        return sb.toString();
    }

    // ---- salida HTML ----
    public static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public String toHtml() {
        StringBuilder sb = new StringBuilder();
        for (Object o : secciones) {
            if (o instanceof Pares) {
                sb.append("<table style='border-collapse:collapse;margin:8px 0'>");
                for (String[] kv : ((Pares) o).items)
                    sb.append("<tr><td style='padding:3px 10px;color:#555'><b>").append(esc(kv[0]))
                      .append("</b></td><td style='padding:3px 10px'>").append(esc(kv[1])).append("</td></tr>");
                sb.append("</table>");
            } else if (o instanceof Tabla) {
                Tabla t = (Tabla) o;
                sb.append("<p style='margin:14px 0 4px'><b>").append(esc(t.titulo)).append("</b> (").append(t.filas.size()).append(")</p>");
                if (t.filas.isEmpty()) { sb.append("<p><i>(sin registros)</i></p>"); continue; }
                sb.append("<table style='border-collapse:collapse;font-size:13px'><tr>");
                for (String c : t.cols)
                    sb.append("<th style='border:1px solid #bbb;background:#1f3b63;color:#fff;padding:4px 8px;text-align:left'>").append(esc(c)).append("</th>");
                sb.append("</tr>");
                int k = 0;
                for (List<String> f : t.filas) {
                    sb.append("<tr").append(k++ % 2 == 1 ? " style='background:#f3f6fb'" : "").append(">");
                    for (String c : f) sb.append("<td style='border:1px solid #ccc;padding:4px 8px'>").append(esc(c)).append("</td>");
                    sb.append("</tr>");
                }
                sb.append("</table>");
            } else {
                Bloque b = (Bloque) o;
                sb.append("<p style='margin:14px 0 4px'><b>").append(esc(b.titulo)).append("</b></p><pre style='background:#f6f6f6;border:1px solid #ddd;padding:8px;white-space:pre-wrap;font-size:12px'>")
                  .append(esc(b.texto)).append("</pre>");
            }
        }
        return sb.toString();
    }
}
