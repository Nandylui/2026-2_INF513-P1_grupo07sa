package main;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import commands.Registro;
import config.AppConfig;
import database.BackupManager;
import database.DatabaseConnection;
import email.EmailConfig;
import email.EmailReader;
import util.AppLogger;

/**
 * Punto de entrada: Sistema Vía Mail del Consejo Facultativo (grupo07sa).
 * Ejecutar desde STS: clic derecho > Run As > Java Application.
 *
 * Cada ciclo: (1) intenta volver al servidor principal si estaba en contingencia,
 *             (2) lee los correos y ejecuta los comandos, (3) hace el respaldo periódico.
 */
public class SistemaPrincipal {

    private static long ultimoRespaldo = 0;

    public static void main(String[] args) {
        Registro.cargar();
        System.out.println("=====================================================================");
        System.out.println("  SISTEMA VIA MAIL - CONSEJO FACULTATIVO  (INF513 Tecnologia Web)");
        System.out.println("  Grupo: " + EmailConfig.usuario().toUpperCase() + "   Cuenta: " + EmailConfig.direccion());
        System.out.println("=====================================================================");
        System.out.println("Configuracion : " + AppConfig.archivo());
        System.out.println("Perfil BD     : " + AppConfig.perfil() + "  (cambie 'perfil' en config.properties: local | remoto)");
        System.out.println("Correo        : " + EmailConfig.host() + "  POP3:" + EmailConfig.pop3Puerto() + "  SMTP:" + EmailConfig.smtpPuerto());
        System.out.println("Comandos      : " + Registro.TODOS.size() + " disponibles (envie HELP por correo)");
        if (!EmailConfig.destinoForzado().isEmpty())
            System.out.println("ATENCION      : las respuestas se envian SIEMPRE a " + EmailConfig.destinoForzado() + " (correo.destino_forzado)");
        System.out.println();

        AppLogger.info("Verificando la base de datos...");
        if (DatabaseConnection.testConnection()) {
            AppLogger.info("Base de datos OK -> " + DatabaseConnection.descripcion());
        } else {
            AppLogger.info("La base de datos no responde ahora. El sistema seguira intentando en cada ciclo.");
        }
        AppLogger.info("Verificando el servidor de correo...");
        EmailReader lector = new EmailReader();
        if (lector.testConnection()) AppLogger.info("Correo OK (POP3 " + EmailConfig.usuario() + "@" + EmailConfig.host() + ")");
        else AppLogger.info("No se pudo conectar al correo (revise la IP habilitada, usuario y clave). Se reintentara en cada ciclo.");

        final int segundos = Math.max(5, AppConfig.getInt("correo.intervalo_segundos", 30));
        final long cadaMs = AppConfig.getInt("backup.intervalo_minutos", 60) * 60_000L;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
        ex.scheduleWithFixedDelay(() -> {
            try {
                AppLogger.info("Revisando correos... (" + LocalDateTime.now().withNano(0) + ")");
                DatabaseConnection.intentarVolverAlPrincipal();
                lector.procesarCorreosPendientes();
                if (cadaMs > 0 && System.currentTimeMillis() - ultimoRespaldo >= cadaMs && DatabaseConnection.testConnection()) {
                    BackupManager.respaldar();
                    ultimoRespaldo = System.currentTimeMillis();
                }
            } catch (Throwable t) {
                AppLogger.error("Fallo en el ciclo principal (el sistema continua)", t);
            }
        }, 0, segundos, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("Sistema activo. Revisa el buzon cada " + segundos + " s. Detener: boton rojo de la consola (o Ctrl+C).");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ex.shutdownNow();
            DatabaseConnection.closeConnection();
            System.out.println("Sistema detenido.");
        }));
    }
}
