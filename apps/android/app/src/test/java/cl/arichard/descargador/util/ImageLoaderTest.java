package cl.arichard.descargador.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class ImageLoaderTest {
    @Test public void largeNetworkCoverIsSampledBeforeItReachesTheGpu() {
        Bitmap source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        source.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        Bitmap decoded = ImageLoader.decodeSampled(bytes.toByteArray(), 512);
        assertNotNull(decoded);
        assertTrue(decoded.getWidth() <= 512);
        assertTrue(decoded.getHeight() <= 512);
    }
}
