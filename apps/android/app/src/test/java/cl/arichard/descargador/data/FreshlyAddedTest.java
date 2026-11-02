package cl.arichard.descargador.data;

import android.app.Application;
import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Las canciones recién agregadas a una playlist se destacan una sola vez al abrirla. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class FreshlyAddedTest {
    @Test public void addedSongsAreHandedOverOnceAndRepeatsAreIgnored() {
        Context context = RuntimeEnvironment.getApplication();
        PlaylistManager manager = PlaylistManager.getInstance(context);
        manager.createPlaylist("Nuevas");
        manager.addSongToPlaylist("Nuevas", "youtube:aaaaaaaaaaa");
        manager.takeFreshlyAdded("Nuevas");
        assertFalse("Repetida: no es nueva", manager.addSongToPlaylist("Nuevas", "youtube:aaaaaaaaaaa"));
        manager.addSongToPlaylist("Nuevas", "youtube:bbbbbbbbbbb");
        manager.addSongToPlaylist("Nuevas", "/musica/c.mp3");
        manager.addSongToPlaylist("Otra", "youtube:zzzzzzzzzzz");
        assertEquals(Arrays.asList("youtube:bbbbbbbbbbb", "/musica/c.mp3"),
                new java.util.ArrayList<>(manager.takeFreshlyAdded("Nuevas")));
        assertEquals(Collections.emptySet(), manager.takeFreshlyAdded("Nuevas"));
        assertEquals(Collections.singleton("youtube:zzzzzzzzzzz"), manager.takeFreshlyAdded("Otra"));
        manager.keepFreshlyAdded("Otra", Collections.singleton("youtube:zzzzzzzzzzz"));
        assertEquals("Lo que no se alcanzó a mostrar se anima la próxima vez",
                Collections.singleton("youtube:zzzzzzzzzzz"), manager.takeFreshlyAdded("Otra"));
    }
}
