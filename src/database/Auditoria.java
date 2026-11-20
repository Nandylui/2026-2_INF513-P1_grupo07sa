package database;

/** Registra cada comando procesado en la tabla auditoria (CU7: fiscalizar / auditar). */
public final class Auditoria {

    private Auditoria() {}

    public static void registrar(String remitente, String comando, String parametros, boolean exito, String detalle) {
        try {
            DatabaseOperations.ejecutar(
                    "INSERT INTO auditoria (remitente, comando, parametros, resultado, detalle) VALUES (?,?,?,?,?)",
                    cortar(remitente, 150), cortar(comando, 80), cortar(parametros, 500),
                    exito ? "EXITO" : "ERROR", cortar(detalle, 500));
        } catch (Exception e) {
            // La auditoría nunca debe alterar el resultado de un comando ya procesado (ni provocar que se
            // reejecute porque la BD se cayó justo después): solo se anota en error.log.
            util.AppLogger.error("No se pudo registrar auditoría", e);
        }
    }

    private static String cortar(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
