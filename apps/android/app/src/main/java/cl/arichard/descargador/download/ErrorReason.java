package cl.arichard.descargador.download;

import java.util.Locale;

/** Traduce el error de yt-dlp a un motivo que el usuario pueda entender. Equivale a error_reason de escritorio. */
public final class ErrorReason {
    private ErrorReason() {}

    /** Primera parte del mensaje, en español; la interfaz la traduce con Tr.t. */
    public static String prefix(String error) {
        String lowered = (error == null ? "" : error).toLowerCase(Locale.ROOT);
        if (lowered.contains("429") || lowered.contains("too many requests"))
            return "YouTube limitó las consultas. Espera unos minutos y reintenta.";
        if (lowered.contains("timed out") || lowered.contains("timeout"))
            return "Se agotó el tiempo de espera; comprueba la conexión y reintenta.";
        if (lowered.contains("sign in") || lowered.contains("not a bot"))
            return "YouTube solicita verificar la sesión; puede ser necesario configurar cookies.";
        return "No se pudo completar la consulta.";
    }

    public static String reason(String error) {
        return prefix(error) + " Detalle: " + (error == null ? "" : error);
    }

    /** Older persisted jobs included the entire extractor log in their error field. */
    public static String notice(String error) {
        if (error == null) return "";
        int spanish = error.indexOf(" Detalle: ");
        int english = error.indexOf(" Details: ");
        int end = spanish < 0 ? english : english < 0 ? spanish : Math.min(spanish, english);
        return end < 0 ? error : error.substring(0, end).trim();
    }

    /** True si el error se arregla probablemente con cookies de una sesión de YouTube. */
    public static boolean needsCookies(String error) {
        String lowered = (error == null ? "" : error).toLowerCase(Locale.ROOT);
        return lowered.contains("sign in") || lowered.contains("not a bot");
    }
}
