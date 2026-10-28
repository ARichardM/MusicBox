package cl.arichard.descargador.download;

import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class DownloadNoticeTest {
    @Test public void oldPersistedErrorsAlsoHaveConciseNotices() {
        assertEquals("Timeout.", ErrorReason.notice("Timeout. Details: WARNING: [youtube] skipped"));
        assertEquals("Tiempo agotado.", ErrorReason.notice("Tiempo agotado. Detalle: WARNING: skipped"));
        assertEquals("Error simple", ErrorReason.notice("Error simple"));
        assertEquals("", ErrorReason.notice(null));
    }
    @Test public void technicalWarningsStayOutOfTheUserNotice() throws Exception {
        Method describe = DownloadManager.class.getDeclaredMethod("describe", String.class);
        describe.setAccessible(true);
        String raw = "WARNING: [youtube] formats skipped\nERROR: The read operation timed out";
        String message = (String) describe.invoke(null, raw);
        assertEquals(cl.arichard.descargador.util.Tr.t(ErrorReason.prefix(raw)), message);
        assertFalse(message.contains("WARNING"));
        assertFalse(message.contains("Details:"));
    }
}
