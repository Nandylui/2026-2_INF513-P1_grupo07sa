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
 *  - Si la base de datos no responde, el correo se deja en el servidor y se reintenta (no se pierde);
 *    si sigue caída tras unos ciclos, se le responde al usuario un ERROR de "base de datos no disponible".
 *  - Si el envío de la respuesta falla (SMTP), se reintenta el envío sin volver a ejecutar el comando,
 *    pero de forma ACOTADA: máximo 'correo.reintentos_envio' intentos (por defecto 3) separados por una espera
 *    creciente ('correo.espera_reintento_seg', por defecto 120 s). Superado el máximo se registra el error y el
 *    correo se descarta. Así no se bombardea al servidor SMTP (que podría bloquear la IP como si fuera un ataque).
 *  - Se ignoran las respuestas automáticas propias y los rebotes (evita bucles de correo).
 */
public class EmailReader {

    private static class Pendiente {
        final String remitente, asunto; final CommandResult resultado;
        int intentos = 1;                 // el primer envío fallido ya cuenta como intento
        long proximoIntento = 0;          // no reintentar antes de este instante (ms)
        Pendiente(String r, String a, CommandResult c) { remitente = r; asunto = a; resultado = c; }
    }

    /** Control de reintentos de un aviso (el aviso de "base de datos no disponible"). */
    private static class ReintentoAviso { int intentos = 0; long proximoIntento = 0; }

    private static int maxEnvios() { return Math.max(1, config.AppConfig.getInt("correo.reintentos_envio", 3)); }

    /** Espera antes del siguiente intento de envío: crece con cada fallo (120 s, 240 s, ...). */
    private static long esperaMs(int intentosHechos) {
        return Math.max(1, config.AppConfig.getInt("correo.espera_reintento_seg", 120)) * 1000L * Math.max(1, intentosHechos);
    }

    private final CommandExecutor executor = new CommandExecutor();
    private final EmailSender sender = new EmailSender();
    private final Map<String, Pendiente> respuestasPendientes = new HashMap<>();
    private final Map<String, Integer> fallos = new HashMap<>();
    /** Cuántos ciclos seguidos falló un correo porque no hay base de datos (ni remota ni de contingencia). */
    private final Map<String, Integer> sinBd = new HashMap<>();
    private final Map<String, ReintentoAviso> avisosFallidos = new HashMap<>();

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
                    if (procesarMensaje(m)) { sinBd.remove(idMensaje(m)); m.setFlag(Flags.Flag.DELETED, true); tratados++; }
                } catch (DbNoDisponibleException e) {
                    if (tratarBdNoDisponible(m, e)) { m.setFlag(Flags.Flag.DELETED, true); tratados++; }
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

    /**
     * La base de datos no responde (ni la remota ni la local de contingencia).
     * Se reintenta en cada ciclo; si tras 'bd.intentos_antes_de_avisar' ciclos (por defecto 2) sigue caída,
     * se AVISA al usuario por correo (fallo de conexión -> respuesta de Error) y el correo se descarta
     * para que lo reenvíe cuando el servicio vuelva.
     *
     * @return true si el correo quedó resuelto (aviso enviado) y puede borrarse del servidor.
     */
    private boolean tratarBdNoDisponible(Message m, DbNoDisponibleException e) {
        try {
            String id = idMensaje(m);
            int maximo = Math.max(1, config.AppConfig.getInt("bd.intentos_antes_de_avisar", 2));
            int n = sinBd.merge(id, 1, Integer::sum);
            if (n < maximo) {
                AppLogger.error("Base de datos no disponible (intento " + n + " de " + maximo
                    + "): el correo se reintenta en el próximo ciclo", e);
                return false;
            }
            ReintentoAviso ra = avisosFallidos.get(id);
            if (ra != null && System.currentTimeMillis() < ra.proximoIntento) return false;   // esperando antes de reintentar el aviso
            String remitente = remitente(m);
            String asunto = (m.getSubject() == null || m.getSubject().trim().isEmpty()) ? "(sin asunto)" : m.getSubject();
            AppLogger.error("Base de datos no disponible tras " + n + " intento(s): se avisa al remitente " + remitente, e);
            CommandResult aviso = CommandResult.error(
                "Base de datos no disponible. No hay conexión con el servidor de base de datos ni con la copia local "
                + "de contingencia, por lo que su comando no pudo completarse.\nIntente nuevamente en unos minutos.");
            if (sender.responder(remitente, asunto, aviso)) {
                sinBd.remove(id); avisosFallidos.remove(id);
                AppLogger.access(remitente, asunto, "ERROR: base de datos no disponible (aviso enviado, correo descartado)", 0);
                return true;
            }
            if (ra == null) { ra = new ReintentoAviso(); avisosFallidos.put(id, ra); }
            ra.intentos++;
            if (ra.intentos >= maxEnvios()) {
                sinBd.remove(id); avisosFallidos.remove(id);
                AppLogger.error("No se pudo enviar el aviso a " + remitente + " tras " + ra.intentos + " intento(s); correo descartado", null);
                AppLogger.access(remitente, asunto, "ERROR: base de datos no disponible (aviso NO enviado, correo descartado)", 0);
                return true;
            }
            ra.proximoIntento = System.currentTimeMillis() + esperaMs(ra.intentos);
            return false;   // el aviso se reintenta más tarde (máximo 'correo.reintentos_envio' intentos)
        } catch (Exception ex) {
            AppLogger.error("No se pudo avisar que la base de datos no está disponible", ex);
            return false;
        }
    }

    /** true = el correo quedó resuelto y puede borrarse del servidor. */
    private boolean procesarMensaje(Message m) throws Exception {
        String remitente = remitente(m);
        String asunto = m.getSubject() == null ? "" : m.getSubject();
        String id = idMensaje(m);

        // Reintento de envío de una respuesta que antes falló
        Pendiente pend = respuestasPendientes.get(id);
        if (pend != null) {
            if (System.currentTimeMillis() < pend.proximoIntento) return false;   // esperando antes del próximo intento
            if (sender.responder(pend.remitente, pend.asunto, pend.resultado)) { respuestasPendientes.remove(id); return true; }
            pend.intentos++;
            if (pend.intentos >= maxEnvios()) {
                respuestasPendientes.remove(id);
                AppLogger.error("No se pudo enviar la respuesta a " + pend.remitente + " tras " + pend.intentos
                    + " intento(s); el comando SÍ se ejecutó. Correo descartado: " + pend.asunto, null);
                util.AppLogger.access(pend.remitente, pend.asunto, "Comando ejecutado; respuesta NO enviada (SMTP rechazó " + pend.intentos + " intentos)", 0);
                return true;
            }
            pend.proximoIntento = System.currentTimeMillis() + esperaMs(pend.intentos);
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
            if (maxEnvios() <= 1) {
                AppLogger.error("No se pudo enviar la respuesta a " + remitente + "; el comando SÍ se ejecutó. Correo descartado: " + textoComando, null);
                return true;
            }
            Pendiente nueva = new Pendiente(remitente, textoComando, res);
            nueva.proximoIntento = System.currentTimeMillis() + esperaMs(1);
            respuestasPendientes.put(id, nueva);
            return false;   // se reintenta el ENVÍO más tarde (sin re-ejecutar el comando), con tope de intentos
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
