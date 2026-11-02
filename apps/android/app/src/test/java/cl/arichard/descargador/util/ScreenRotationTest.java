package cl.arichard.descargador.util;

import android.content.pm.ActivityInfo;
import org.junit.Test;
import static org.junit.Assert.*;

/** Las tablets siguen el giro del sistema y los teléfonos se quedan en vertical. */
public class ScreenRotationTest {
    @Test public void tabletsRotateAndPhonesStayPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, ScreenRotation.orientationFor(800));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, ScreenRotation.orientationFor(600));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ScreenRotation.orientationFor(411));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ScreenRotation.orientationFor(360));
    }
}
