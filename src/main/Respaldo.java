package main;

import java.io.File;

import database.BackupManager;
import database.DatabaseConnection;

/**
 * Respaldo / restauración desde la consola (si el ing. tumba o limpia la BD):
 *   main.Respaldo respaldar            -> crea backups/respaldo_AAAAMMDD_HHMMSS.sql
 *   main.Respaldo listar               -> muestra los respaldos
 *   main.Respaldo restaurar [archivo]  -> vuelve a dejar la BD como en el respaldo (el último si no se indica)
 * En STS: Run Configurations > Arguments > Program arguments.
 */
public class Respaldo {

    public static void main(String[] args) throws Exception {
        String accion = args.length == 0 ? "respaldar" : args[0].toLowerCase();
        try {
            switch (accion) {
                case "respaldar":
                    System.out.println("Respaldo creado: " + BackupManager.respaldar().getAbsolutePath());
                    break;
                case "listar":
                    for (File f : BackupManager.listar()) System.out.println(f.getName() + "  (" + Math.max(1, f.length() / 1024) + " KB)");
                    break;
                case "restaurar":
                    File f = args.length > 1 ? new File(BackupManager.carpeta(), new File(args[1]).getName()) : null;
                    int n = BackupManager.restaurar(f);
                    System.out.println("Restauracion completa: " + n + " filas.");
                    break;
                default:
                    System.out.println("Uso: respaldar | listar | restaurar [archivo]");
            }
        } finally {
            DatabaseConnection.closeConnection();
        }
    }
}
