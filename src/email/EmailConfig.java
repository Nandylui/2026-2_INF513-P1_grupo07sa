package email;

import config.AppConfig;

/** Datos de la cuenta de correo del grupo (se leen de config.properties). */
public final class EmailConfig {

    private EmailConfig() {}

    public static String direccion()   { return AppConfig.get("correo.direccion", "grupo07sa@tecnoweb.org.bo"); }
    /** Usuario para POP3/SMTP: sin el dominio (grupo07sa). */
    public static String usuario()     { return AppConfig.get("correo.usuario", direccion().split("@")[0]); }
    public static String clave()       { return AppConfig.get("correo.clave", ""); }
    public static String host()        { return AppConfig.get("correo.host", "mail.tecnoweb.org.bo"); }
    public static int pop3Puerto()     { return AppConfig.getInt("correo.pop3.puerto", 110); }
    public static int smtpPuerto()     { return AppConfig.getInt("correo.smtp.puerto", 25); }
    public static boolean ssl()        { return AppConfig.getBool("correo.ssl", false); }
    public static boolean smtpAuth()   { return AppConfig.getBool("correo.smtp.auth", false); }
    /** Si no está vacío, TODAS las respuestas van a esta dirección (útil mientras no habiliten la IP). */
    public static String destinoForzado() { return AppConfig.get("correo.destino_forzado", ""); }
    public static String nombreSistema()  { return "Sistema Consejo Facultativo - " + usuario().toUpperCase(); }
}
