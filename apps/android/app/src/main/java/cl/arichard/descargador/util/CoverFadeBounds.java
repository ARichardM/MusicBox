package cl.arichard.descargador.util;

/**
 * Dónde caen los degradados de la portada ampliada. La portada se ajusta al ancho y se centra dentro de un marco que llega hasta
 * el título, así que suele ser más baja que el marco: los degradados deben apoyarse en los bordes de la imagen. Pegados a los del
 * marco caían sobre las barras negras y la imagen quedaba cortada de golpe (en MP4 el video llena el marco y no pasaba).
 */
public final class CoverFadeBounds {
    private CoverFadeBounds() {}

    /** {arriba, abajo} de la parte visible de la imagen, en píxeles dentro del marco. Sin imagen conocida, todo el marco. */
    public static int[] visible(int frameHeight, int imageHeight) {
        int frame = Math.max(0, frameHeight);
        if (imageHeight <= 0 || imageHeight >= frame) return new int[] {0, frame};
        int top = Math.round((frame - imageHeight) / 2f);
        return new int[] {top, frame - top};
    }

    /** Posición superior del degradado inferior para que termine en el borde de la imagen sin salirse por arriba. */
    public static int bottomFadeTop(int visibleTop, int visibleBottom, int fadeHeight) {
        return Math.max(visibleTop, visibleBottom - fadeHeight);
    }
}
