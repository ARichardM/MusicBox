package cl.arichard.descargador;

import android.app.Application;

import cl.arichard.descargador.model.SearchResult;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class VideoSearchLogicTest {
    private static SearchResult video(String id, long seconds) {
        return new SearchResult(id, "Título " + id, "Canal", seconds, null, null);
    }

    @Test public void durationsAreShownAsClockTimes() {
        assertEquals("", VideoSearchSheet.durationLabel(0));
        assertEquals("0:09", VideoSearchSheet.durationLabel(9));
        assertEquals("3:34", VideoSearchSheet.durationLabel(214));
        assertEquals("1:02:03", VideoSearchSheet.durationLabel(3723));
    }

    @Test public void liveStreamsAndRepeatedVideosAreDropped() {
        List<SearchResult> found = Arrays.asList(video("a", 200), video("live", 0), video("a", 200), video("b", 90), video("", 50));
        List<SearchResult> usable = VideoSearchSheet.usable(found);
        assertEquals(2, usable.size());
        assertEquals("a", usable.get(0).getId());
        assertEquals("b", usable.get(1).getId());
    }
}
