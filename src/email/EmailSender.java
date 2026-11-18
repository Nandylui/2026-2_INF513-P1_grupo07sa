package email;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import commands.CommandResult;
import util.AppLogger;

/** Envío de respuestas por SMTP (texto + HTML, UTF-8). */
public class EmailSender {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Session sesion() {
        Properties p = new Properties();
        p.put("mail.smtp.host", EmailConfig.host());
        p.put("mail.smtp.port", String.valueOf(EmailConfig.smtpPuerto()));
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
        p.put("mail.smtp.ssl.enable", String.valueOf(EmailConfig.ssl()));
        p.put("mail.smtp.starttls.enable", "false");
        p.put("mail.smtp.auth", String.valueOf(EmailConfig.smtpAuth()));
        p.put("mail.mime.charset", "UTF-8");
        if (EmailConfig.smtpAuth()) {
            return Session.getInstance(p, new javax.mail.Authenticator() {
                @Override protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new javax.mail.PasswordAuthentication(EmailConfig.usuario(), EmailConfig.clave());
                }
            });
        }
        Session ses = Session.getInstance(p);
        ses.setDebug(config.AppConfig.getBool("correo.debug", false));
        return ses;
    }

    /** Recorre la cadena de excepciones de JavaMail para mostrar lo que respondió realmente el servidor SMTP. */
    private static String detalle(Exception e) {
        StringBuilder sb = new StringBuilder();
        Exception x = e;
        int n = 0;
        while (x != null && n++ < 6) {
            sb.append(x.getClass().getSimpleName()).append(": ").append(x.getMessage());
            if (x instanceof javax.mail.SendFailedException) {
                javax.mail.SendFailedException sf = (javax.mail.SendFailedException) x;
                if (sf.getInvalidAddresses() != null && sf.getInvalidAddresses().length > 0)
                    sb.append(" [direcciones rechazadas: ").append(java.util.Arrays.toString(sf.getInvalidAddresses())).append("]");
            }
            x = (x instanceof MessagingException) ? ((MessagingException) x).getNextException() : null;
            if (x != null) sb.append("  <-  ");
        }
        return sb.toString();
    }

    /** Responde al remitente con el resultado de su comando. */
    public boolean responder(String remitente, String asuntoOriginal, CommandResult res) {
        String estado = res.isExito() ? "OK" : "ERROR";
        String asunto = "RE: " + (asuntoOriginal == null ? "" : asuntoOriginal.trim()) + " [" + estado + "]";
        String fecha = LocalDateTime.now().format(F);

        StringBuilder txt = new StringBuilder();
        txt.append("==================================================\n");
        txt.append(EmailConfig.nombreSistema()).append('\n');
        txt.append(res.isExito() ? "OPERACION EXITOSA" : "ERROR").append('\n');
        txt.append("==================================================\n\n");
        txt.append("Comando : ").append(asuntoOriginal == null ? "" : asuntoOriginal.trim()).append('\n');
        txt.append("Mensaje : ").append(res.getMensaje()).append("\n\n");
        txt.append(res.toTexto()).append("\n\n");
        txt.append("--------------------------------------------------\n");
        txt.append("Fecha y hora: ").append(fecha).append("\nCuenta: ").append(EmailConfig.direccion()).append('\n');
        txt.append("Envie HELP para ver todos los comandos.\n");

        String color = res.isExito() ? "#1b7a3d" : "#b3261e";
        StringBuilder html = new StringBuilder();
        html.append("<div style='font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#222'>");
        html.append("<div style='background:#1f3b63;color:#fff;padding:10px 14px;border-radius:6px 6px 0 0'><b>")
            .append(CommandResult.esc(EmailConfig.nombreSistema())).append("</b></div>");
        html.append("<div style='border-left:5px solid ").append(color).append(";background:#f7f7f7;padding:10px 14px'>");
        html.append("<b style='color:").append(color).append("'>").append(res.isExito() ? "OPERACIÓN EXITOSA" : "ERROR").append("</b><br>");
        html.append("<span style='color:#555'>Comando:</span> <code>").append(CommandResult.esc(asuntoOriginal == null ? "" : asuntoOriginal.trim())).append("</code><br>");
        html.append("<span style='color:#555'>Mensaje:</span> ").append(CommandResult.esc(res.getMensaje()).replace("\n", "<br>")).append("</div>");
        html.append(res.toHtml());
        html.append("<p style='color:#888;font-size:12px;margin-top:18px'>").append(fecha).append(" · ").append(CommandResult.esc(EmailConfig.direccion()))
            .append(" · Envíe <b>HELP</b> para ver todos los comandos.</p></div>");

        return enviar(remitente, asunto, txt.toString(), html.toString());
    }

    public boolean enviar(String destinatario, String asunto, String texto, String html) {
        String destino = EmailConfig.destinoForzado().isEmpty() ? destinatario : EmailConfig.destinoForzado();
        try {
            MimeMessage m = new MimeMessage(sesion());
            m.setFrom(new InternetAddress(EmailConfig.direccion()));
            m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destino));
            m.setSubject(asunto, "UTF-8");
            m.setHeader("Auto-Submitted", "auto-replied");
            m.setHeader("X-Sistema", "ConsejoFacultativo-" + EmailConfig.usuario());
            MimeMultipart alt = new MimeMultipart("alternative");
            MimeBodyPart t = new MimeBodyPart();
            t.setText(texto, StandardCharsets.UTF_8.name());
            alt.addBodyPart(t);
            if (html != null) {
                MimeBodyPart h = new MimeBodyPart();
                h.setContent(html, "text/html; charset=UTF-8");
                alt.addBodyPart(h);
            }
            m.setContent(alt);
            Transport.send(m);
            AppLogger.info("Respuesta enviada a " + destino + (destino.equals(destinatario) ? "" : " (destino forzado; remitente original: " + destinatario + ")"));
            return true;
        } catch (MessagingException e) {
            AppLogger.error("No se pudo enviar el correo a " + destino + " -> " + detalle(e)
                + "\n   Si el servidor responde 'relay denied' / 'Invalid Addresses', el SMTP del servidor solo permite enviar a correos externos desde una IP habilitada por el docente.", e);
            return false;
        }
    }
}
