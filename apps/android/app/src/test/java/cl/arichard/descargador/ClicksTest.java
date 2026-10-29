package cl.arichard.descargador;

import cl.arichard.descargador.util.Clicks;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClicksTest {
    @Test public void listeningMetricUsesHeadphonesInsteadOfPlayIcon() {
        assertEquals("", Clicks.suffix(0));
        assertEquals(" • 🎧 12", Clicks.suffix(12));
        assertEquals(" • 🎧 1.2K", Clicks.suffix(1234));
    }
}
