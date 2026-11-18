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
        } catch (DbNoDisponibleException e) {
            throw e;
        } catch (Exception e) {
            util.AppLogger.error("No se pudo registrar auditoría", e);
        }
    }

    private static String cortar(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
