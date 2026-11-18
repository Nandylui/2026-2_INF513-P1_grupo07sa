package config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Lee config.properties (carpeta raíz del proyecto). Todo lo que cambia entre
 * "pruebas locales" y "servidor del ing." está aquí: NO hay que tocar código.
 * Se puede indicar otro archivo con -Dconfig=ruta/archivo.properties
 */
public final class AppConfig {

    private static final Properties P = new Properties();
    private static String archivoCargado = "(valores por defecto)";

    static {
        String ruta = System.getProperty("config", "config.properties");
        File f = new File(ruta);
        if (f.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                P.load(r);
                archivoCargado = f.getAbsolutePath();
            } catch (Exception e) {
                System.err.println("No se pudo leer " + ruta + ": " + e.getMessage());
            }
        }
    }

    private AppConfig() {}

    public static String archivo() { return archivoCargado; }

    public static String get(String clave, String porDefecto) {
        String v = P.getProperty(clave);
        return (v == null || v.trim().isEmpty()) ? porDefecto : v.trim();
    }

    public static int getInt(String clave, int porDefecto) {
        try { return Integer.parseInt(get(clave, String.valueOf(porDefecto))); }
        catch (NumberFormatException e) { return porDefecto; }
    }

    public static boolean getBool(String clave, boolean porDefecto) {
        return Boolean.parseBoolean(get(clave, String.valueOf(porDefecto)));
    }

    /** Perfil activo de base de datos: "local" o "remoto". */
    public static String perfil() { return get("perfil", "local").toLowerCase(); }
}
