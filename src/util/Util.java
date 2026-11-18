package util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class Util {

    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Util() {}

    public static String sha256(String texto) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static LocalDate parseFecha(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Fecha inválida '" + s + "'. Use AAAA-MM-DD (ej: 2026-12-15).");
        }
    }

    public static Timestamp parseFechaHora(String s) {
        if (s == null) throw new IllegalArgumentException("Falta la fecha y hora.");
        String t = s.trim().replace('T', ' ');
        if (t.length() == 16) t = t + ":00";
        try {
            return Timestamp.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Fecha y hora inválida '" + s
                    + "'. Use AAAA-MM-DD HH:MM (ej: 2026-12-15 09:30).");
        }
    }

    public static int parseEntero(String s, String nombre) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("El parámetro '" + nombre + "' debe ser un número entero (recibido: '" + s + "').");
        }
    }

    /** Suma días hábiles (lunes a viernes; no considera feriados). */
    public static LocalDate sumarDiasHabiles(LocalDate desde, int dias) {
        LocalDate d = desde;
        int n = 0;
        while (n < dias) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) n++;
        }
        return d;
    }

    public static String quitarAcentos(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Mayúsculas, sin acentos y con espacios -> '_' (ej: "a favor" -> "A_FAVOR"). */
    public static String clave(String s) {
        return quitarAcentos(s == null ? "" : s.trim()).toUpperCase().replaceAll("[\\s-]+", "_");
    }

    public static String fmt(Object o) {
        if (o == null) return "-";
        if (o instanceof Timestamp) {
            LocalDateTime t = ((Timestamp) o).toLocalDateTime();
            return t.format(FH);
        }
        if (o instanceof Boolean) return ((Boolean) o) ? "SI" : "NO";
        return String.valueOf(o);
    }

    public static int distancia(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            prev = cur;
        }
        return prev[b.length()];
    }
}
