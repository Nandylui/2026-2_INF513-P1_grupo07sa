package main;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import commands.CommandDef;
import commands.Registro;
import config.AppConfig;

/**
 * Genera el correo de pruebas (una tabla por CU: COMANDO | DESCRIPCION | EJEMPLO) leyendo el mismo
 * catalogo que usa el sistema. Salida: correo_pruebas/correo_pruebas.html y .md
 * Ejecutar: main.GenerarCorreoPruebas  -> abrir el .html en el navegador, copiar y pegar en el correo al ing.
 */
public class GenerarCorreoPruebas {

    static final Map<String, String> TITULOS = new LinkedHashMap<>();
    static {
        
        TITULOS.put("CU1", "CU1 - Gestionar Usuarios");
        TITULOS.put("CU2", "CU2 - Gestionar Miembros y Quórum");
        TITULOS.put("CU3", "CU3 - Convocatorias, Excusas y Suplentes");
        TITULOS.put("CU4", "CU4 - Repositorio Documental y Workflow");
        TITULOS.put("CU5", "CU5 - Asistencia, Quórum y Votaciones");
        TITULOS.put("CU6", "CU6 - Resoluciones, Veto y Actas");
        TITULOS.put("CU7", "CU7 - Fiscalizar, Auditar y Publicar Gaceta");
        TITULOS.put("CU8", "CU8 - Reportes");
        TITULOS.put("SISTEMA", "Sistema (ayuda, estado y respaldo)");
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static void main(String[] args) throws Exception {
        Registro.cargar();
        String cuenta = AppConfig.get("correo.direccion", "grupo07sa@tecnoweb.org.bo");
        Map<String, List<CommandDef>> porCu = new LinkedHashMap<>();
        for (String k : TITULOS.keySet()) porCu.put(k, new ArrayList<>());
        for (CommandDef d : Registro.TODOS.values()) porCu.computeIfAbsent(d.cu, k -> new ArrayList<>()).add(d);

        File dir = new File("correo_pruebas");
        dir.mkdirs();

        StringBuilder h = new StringBuilder();
        StringBuilder m = new StringBuilder();
        h.append("<html><head><meta charset=\"UTF-8\"><title>Sistema Consejo Facultativo - Comandos de prueba</title></head>")
         .append("<body style=\"font-family:Arial,sans-serif;font-size:14px;color:#222\">\n")
         .append("<h2>Sistema Vía Mail Básico - Consejo Facultativo (GRUPO07SA)</h2>\n")
         .append("<p><b>INF513 Tecnología Web</b> - Primer parcial. Enviar cada comando <b>en el asunto</b> del correo a <b>")
         .append(esc(cuenta)).append("</b>. La respuesta llega al mismo correo del remitente.</p>\n")
         .append("<p>Formato: <code>COMANDO[\"param1\",\"param2\",...]</code>. El nombre del comando no distingue mayúsculas. ")
         .append("Fechas: <code>AAAA-MM-DD HH:mm</code>. Enviar <code>HELP</code> para ver la lista completa.</p>\n");
        m.append("# Sistema Vía Mail Básico - Consejo Facultativo (GRUPO07SA)\n\n")
         .append("Enviar cada comando **en el asunto** del correo a `").append(cuenta).append("`.\n\n")
         .append("Formato: `COMANDO[\"param1\",\"param2\",...]` (no distingue mayúsculas). Fechas: `AAAA-MM-DD HH:mm`.\n\n");

        for (Map.Entry<String, List<CommandDef>> e : porCu.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String titulo = TITULOS.getOrDefault(e.getKey(), e.getKey());
            h.append("<h3>").append(esc(titulo)).append("</h3>\n")
             .append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%\">\n")
             .append("<tr style=\"background:#e8eef7\"><th align=\"left\">COMANDO</th><th align=\"left\">DESCRIPCION</th><th align=\"left\">EJEMPLO</th></tr>\n");
            m.append("## ").append(titulo).append("\n\n| COMANDO | DESCRIPCION | EJEMPLO |\n|---|---|---|\n");
            for (CommandDef d : e.getValue()) {
                String ej = d.ejemplo == null || d.ejemplo.isEmpty() ? d.nombre : d.ejemplo;
                h.append("<tr><td><code>").append(esc(d.uso())).append("</code></td><td>").append(esc(d.descripcion))
                 .append("</td><td><code>").append(esc(ej)).append("</code></td></tr>\n");
                m.append("| `").append(d.uso().replace("|", "\\|")).append("` | ").append(d.descripcion.replace("|", "\\|"))
                 .append(" | `").append(ej.replace("|", "\\|")).append("` |\n");
            }
            h.append("</table>\n");
            m.append("\n");
        }
        h.append("<p style=\"color:#666\">Generado automáticamente desde el catálogo de comandos del sistema.</p></body></html>\n");

        try (PrintWriter w = new PrintWriter(new File(dir, "correo_pruebas.html"), StandardCharsets.UTF_8)) { w.print(h); }
        try (PrintWriter w = new PrintWriter(new File(dir, "correo_pruebas.md"), StandardCharsets.UTF_8)) { w.print(m); }
        System.out.println("Generado en " + dir.getAbsolutePath() + " (" + Registro.TODOS.size() + " comandos).");
    }
}
