package commands;

import java.util.List;

/** Datos de la ejecución de un comando: quién lo envía y sus parámetros ya validados en cantidad. */
public class Contexto {

    public final String remitente;
    public final List<String> p;

    public Contexto(String remitente, List<String> parametros) {
        this.remitente = remitente;
        this.p = parametros;
    }

    public int n() { return p.size(); }

    /** Parámetro obligatorio (no vacío). */
    public String req(int i, String nombre) {
        if (i >= p.size() || p.get(i).trim().isEmpty()) throw new ComandoException("Falta el parámetro '" + nombre + "'.");
        return p.get(i).trim();
    }

    /** Parámetro opcional: null si no viene o está vacío. */
    public String opt(int i) {
        return (i < p.size() && !p.get(i).trim().isEmpty()) ? p.get(i).trim() : null;
    }

    public int entero(int i, String nombre) {
        return util.Util.parseEntero(req(i, nombre), nombre);
    }
}
