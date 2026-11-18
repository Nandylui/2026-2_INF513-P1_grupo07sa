package email;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;

import commands.CommandExecutor;
import commands.CommandParser;
import commands.CommandResult;
import commands.Registro;
import database.DbNoDisponibleException;
import util.AppLogger;

/**
 * Lee el buzón por POP3 y procesa cada correo como un comando.
 *
 * Garantías:
 *  - Un correo solo se BORRA del servidor después de haber respondido.
 *  - Si la base de datos no responde, el correo se deja en el servidor y se reintenta (no se pierde).
 *  - Si el envío de la respuesta falla (SMTP), se reintenta el envío sin volver a ejecutar el comando.
 *  - Se ignoran las respuestas automáticas propias y los rebotes (evita bucles de correo).
 */
public class EmailReader {

    private static class Pendiente {
        final String remitente, asunto; final CommandResult resultado;
        Pendiente(String r, String a, CommandResult c) { remitente = r; asunto = a; resultado = c; }
    }

    private final CommandExecutor executor = new CommandExecutor();
    private final EmailSender sender = new EmailSender();
    private final Map<String, Pendiente> respuestasPendientes = new HashMap<>();
    private final Map<String, Integer> fallos = new HashMap<>();

    private Store store;
    private Folder inbox;

    private void conectar() throws MessagingException {
        Properties p = new Properties();
        p.put("mail.pop3.host", EmailConfig.host());
        p.put("mail.pop3.port", String.valueOf(EmailConfig.pop3Puerto()));
        p.put("mail.pop3.connectiontimeout", "15000");
        p.put("mail.pop3.timeout", "20000");
        p.put("mail.pop3.ssl.enable", String.valueOf(EmailConfig.ssl()));
        p.put("mail.pop3.starttls.enable", "false");
        Session s = Session.getInstance(p);
        s.setDebug(util_debug());
        store = s.getStore(EmailConfig.ssl() ? "pop3s" : "pop3");
        store.connect(EmailConfig.host(), EmailConfig.pop3Puerto(), EmailConfig.usuario(), EmailConfig.clave());
        inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
    }

    private static boolean util_debug() { return config.AppConfig.getBool("correo.debug", false); }

    private void desconectar() {
        try { if (inbox != null && inbox.isOpen()) inbox.close(true); } catch (MessagingException e) { AppLogger.error("Al cerrar INBOX", e); }
        try { if (store != null && store.isConnected()) store.close(); } catch (MessagingException e) { AppLogger.error("Al cerrar la conexión POP3", e); }
        inbox = null; store = null;
    }

    public boolean testConnection() {
        try {
            conectar();
            return true;
        } catch (MessagingException e) {
            AppLogger.error("No se pudo conectar al correo " + EmailConfig.usuario() + "@" + EmailConfig.host() + ":" + EmailConfig.pop3Puerto(), e);
            return false;
        } finally {
            desconectar();
        }
    }

    /** Un ciclo de lectura. Devuelve la cantidad de correos tratados. */
    public int procesarCorreosPendientes() {
        int tratados = 0;
        try {
            conectar();
            Message[] mensajes = inbox.getMessages();
            if (mensajes.length == 0) {
                AppLogger.info("No hay correos nuevos.");
                return 0;
            }
            AppLogger.info(mensajes.length + " correo(s) en el buzón.");
            for (Message m : mensajes) {
                try {
                    if (procesarMensaje(m)) { m.setFlag(Flags.Flag.DELETED, true); tratados++; }
                } catch (DbNoDisponibleException e) {
                    AppLogger.error("Base de datos no disponible: los correos restantes se reintentan en el próximo ciclo", e);
                    break;
                } catch (Exception e) {
                    String id = idMensaje(m);
                    int n = fallos.merge(id, 1, Integer::sum);
                    AppLogger.error("Error procesando el correo (intento " + n + ")", e);
                    if (n >= 3) { m.setFlag(Flags.Flag.DELETED, true); fallos.remove(id); AppLogger.info("Correo descartado tras 3 fallos."); }
                }
            }
        } catch (MessagingException e) {
            AppLogger.error("No se pudo leer el buzón (¿servidor caído o IP no habilitada?)", e);
        } finally {
            desconectar();
        }
        return tratados;
    }

    /** true = el correo quedó resuelto y puede borrarse del servidor. */
    private boolean procesarMensaje(Message m) throws Exception {
        String remitente = remitente(m);
        String asunto = m.getSubject() == null ? "" : m.getSubject();
        String id = idMensaje(m);

        // Reintento de envío de una respuesta que antes falló
        Pendiente pend = respuestasPendientes.get(id);
        if (pend != null) {
            if (sender.responder(pend.remitente, pend.asunto, pend.resultado)) { respuestasPendientes.remove(id); return true; }
            return false;
        }

        if (esAutomatico(m, remitente, asunto)) {
            AppLogger.info("Correo automático/rebote ignorado (de " + remitente + "): " + asunto);
            return true;
        }

        AppLogger.info("Comando recibido de " + remitente + ": " + asunto);
        CommandParser parser = new CommandParser(asunto);
        String textoComando = asunto;
        if (!parser.isValid() || asunto.trim().isEmpty() || !Registro.TODOS.containsKey(parser.getComando())) {
            String delCuerpo = comandoEnCuerpo(m);
            if (delCuerpo != null) {
                CommandParser p2 = new CommandParser(delCuerpo);
                if (p2.isValid() && Registro.TODOS.containsKey(p2.getComando())) { parser = p2; textoComando = delCuerpo; }
            }
        }

        CommandResult res = parser.isValid() ? executor.ejecutar(parser, remitente) : CommandResult.error(parser.getError()
            + "\n\nEjemplos válidos:\n  HELP\n  OBTENERUSUARIOS[\"*\"]\n  REGISTRARUSUARIO[\"9876543\",\"Maria\",\"Gomez\",\"maria@gmail.com\",\"SECRETARIO\"]");
        if (!parser.isValid()) util.AppLogger.access(remitente, asunto, "ERROR SINTAXIS: " + parser.getError(), 0);

        if (!sender.responder(remitente, textoComando, res)) {
            respuestasPendientes.put(id, new Pendiente(remitente, textoComando, res));
            return false;   // se reintenta el ENVÍO en el próximo ciclo (sin re-ejecutar el comando)
        }
        return true;
    }

    private static String remitente(Message m) throws MessagingException {
        javax.mail.Address[] from = m.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress) return ((InternetAddress) from[0]).getAddress();
        return "desconocido@desconocido";
    }

    private static String idMensaje(Message m) {
        try {
            String[] h = m.getHeader("Message-ID");
            if (h != null && h.length > 0) return h[0];
            return remitente(m) + "|" + m.getSubject() + "|" + m.getSentDate();
        } catch (MessagingException e) {
            return String.valueOf(m.hashCode());
        }
    }

    private static boolean esAutomatico(Message m, String remitente, String asunto) throws MessagingException {
        String[] auto = m.getHeader("Auto-Submitted");
        if (auto != null && auto.length > 0 && !auto[0].trim().equalsIgnoreCase("no")) return true;
        if (m.getHeader("X-Sistema") != null) return true;
        String r = remitente.toLowerCase();
        if (r.startsWith("mailer-daemon@") || r.startsWith("postmaster@") || r.startsWith("no-reply@") || r.startsWith("noreply@")) return true;
        return r.equalsIgnoreCase(EmailConfig.direccion()) && asunto.trim().toUpperCase().startsWith("RE:");
    }

    /** Busca en las primeras líneas del cuerpo una línea que sea un comando (tiene [ ] o es un comando conocido). */
    private static String comandoEnCuerpo(Message m) {
        try {
            String texto = textoPlano(m);
            if (texto == null) return null;
            int vistas = 0;
            for (String linea : texto.split("\\r?\\n")) {
                String l = linea.trim();
                if (l.isEmpty()) continue;
                if (++vistas > 5) break;
                if (l.contains("[") && l.endsWith("]")) return l;
                if (Registro.TODOS.containsKey(l.toUpperCase())) return l;
            }
        } catch (Exception e) {
            AppLogger.error("No se pudo leer el cuerpo del correo", e);
        }
        return null;
    }

    private static String textoPlano(Part p) throws MessagingException, IOException {
        if (p.isMimeType("text/plain")) return String.valueOf(p.getContent());
        if (p.isMimeType("text/html")) return String.valueOf(p.getContent()).replaceAll("(?is)<(script|style).*?</\\1>", " ").replaceAll("(?i)<br\\s*/?>|</p>|</div>", "\n").replaceAll("<[^>]+>", " ").replace("&nbsp;", " ");
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part b = mp.getBodyPart(i);
                String t = textoPlano(b);
                if (t != null) { if (b.isMimeType("text/plain")) return t; html = t; }
            }
            return html;
        }
        return null;
    }
}
