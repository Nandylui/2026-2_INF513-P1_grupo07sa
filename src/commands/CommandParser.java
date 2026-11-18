package commands;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analizador léxico/sintáctico de comandos:   COMANDO["param1","param2",...]
 *
 *  - Tolera espacios extra, saltos de línea del asunto, mayúsculas/minúsculas,
 *    comillas tipográficas (“ ” de Word/teléfonos) y espacios no separables.
 *  - Soporta comillas escapadas dentro de un parámetro:  "dijo \"hola\""
 *  - Soporta parámetros sin comillas (números, NINGUNO).
 *  - Los comandos sin parámetros pueden ir con o sin corchetes:  HELP   o   HELP[]
 *  - Si la sintaxis es inválida, isValid() = false y getError() explica por qué.
 */
public class CommandParser {

    private static final Pattern FORMA = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[(.*)\\])?$", Pattern.DOTALL);

    private final String comandoOriginal;
    private String comando;
    private final List<String> parametros = new ArrayList<>();
    private boolean valid = false;
    private String error;

    public CommandParser(String texto) {
        this.comandoOriginal = texto;
        parsear(texto);
    }

    static String normalizar(String s) {
        return s.replace(' ', ' ').replace('​', ' ').replace("﻿", "")
                .replace('“', '"').replace('”', '"').replace('„', '"').replace('«', '"').replace('»', '"')
                .replaceAll("[\\r\\n]+", " ")
                .trim();
    }

    private void parsear(String texto) {
        if (texto == null || texto.trim().isEmpty()) {
            error = "El asunto del correo está vacío. Escriba un comando, por ejemplo: HELP";
            return;
        }
        String s = normalizar(texto);
        Matcher m = FORMA.matcher(s);
        if (!m.matches()) {
            error = "Formato de comando inválido. Use: COMANDO[\"param1\",\"param2\",...]  (ejemplo: OBTENERUSUARIOS[\"*\"])";
            return;
        }
        comando = m.group(1).toUpperCase();
        if (comando.equals("AYUDA")) comando = "HELP";   // alias en español
        String contenido = m.group(2);
        if (contenido != null && !contenido.trim().isEmpty()) {
            try {
                tokenizar(contenido);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
                parametros.clear();
                return;
            }
        }
        valid = true;
    }

    private void tokenizar(String s) {
        int i = 0, n = s.length(), num = 0;
        while (true) {
            num++;
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n) throw new IllegalArgumentException("Falta el parámetro " + num + " (hay una coma de más al final).");
            StringBuilder v = new StringBuilder();
            if (s.charAt(i) == '"') {
                i++;
                boolean cerrado = false;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < n && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\\')) {
                        v.append(s.charAt(i + 1));
                        i += 2;
                    } else if (c == '"') {
                        cerrado = true;
                        i++;
                        break;
                    } else {
                        v.append(c);
                        i++;
                    }
                }
                if (!cerrado) throw new IllegalArgumentException("El parámetro " + num + " tiene comillas sin cerrar. Si el texto lleva comillas, escápelas así: \\\"");
                while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                if (i < n && s.charAt(i) != ',')
                    throw new IllegalArgumentException("Después del parámetro " + num + " se esperaba una coma.");
            } else {
                int ini = i;
                while (i < n && s.charAt(i) != ',') i++;
                String crudo = s.substring(ini, i).trim();
                if (crudo.isEmpty()) throw new IllegalArgumentException("El parámetro " + num + " está vacío.");
                if (crudo.indexOf('"') >= 0)
                    throw new IllegalArgumentException("El parámetro " + num + " mezcla comillas y texto. Encierre todo el valor entre comillas dobles.");
                v.append(crudo);
            }
            parametros.add(v.toString());
            if (i >= n) return;
            i++; // coma
        }
    }

    public boolean isValid() { return valid; }
    public String getComando() { return comando; }
    public List<String> getParametros() { return parametros; }
    public String getError() { return error; }
    public String getComandoOriginal() { return comandoOriginal; }
    public int getNumeroParametros() { return parametros.size(); }

    @Override
    public String toString() {
        return "CommandParser{comando=" + comando + ", parametros=" + parametros + ", valid=" + valid + "}";
    }
}
