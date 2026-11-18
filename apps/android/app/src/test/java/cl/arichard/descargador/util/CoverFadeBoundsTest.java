package cl.arichard.descargador.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Los degradados de la portada ampliada deben ir sobre los bordes de la imagen, no sobre los del marco (que puede ser más alto). */
public class CoverFadeBoundsTest {
    @Test
    public void squareArtInsideATallerFrameKeepsTheFadesOnTheImage() {
        // Marco hasta el título (950 px) y portada cuadrada de 720 px centrada: hay 115 px de barra negra arriba y abajo.
        int[] bounds = CoverFadeBounds.visible(950, 720);
        assertEquals(115, bounds[0]);
        assertEquals(835, bounds[1]);
    }

    @Test
    public void imageTallerThanTheFrameFillsItAndKeepsTheOldBehaviour() {
        int[] bounds = CoverFadeBounds.visible(800, 1200);
        assertEquals(0, bounds[0]);
        assertEquals(800, bounds[1]);
    }

    @Test
    public void imageAsTallAsTheFrameUsesTheWholeFrame() {
        int[] bounds = CoverFadeBounds.visible(720, 720);
        assertEquals(0, bounds[0]);
        assertEquals(720, bounds[1]);
    }

    @Test
    public void unknownOrEmptyImageFallsBackToTheFrame() {
        int[] bounds = CoverFadeBounds.visible(640, 0);
        assertEquals(0, bounds[0]);
        assertEquals(640, bounds[1]);
    }

    @Test
    public void theBottomFadeNeverStartsAboveTheImageTop() {
        // Una imagen más baja que el degradado: éste se acorta a la imagen en vez de salirse por arriba.
        assertEquals(100, CoverFadeBounds.bottomFadeTop(100, 300, 400));
        assertEquals(560, CoverFadeBounds.bottomFadeTop(100, 750, 190));
    }
}
