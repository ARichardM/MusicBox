package cl.arichard.descargador;

import android.content.Context;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.util.PlaylistImport;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Prueba real y opt-in: usa red, crea una playlist temporal solo en verification y la elimina. */
@RunWith(AndroidJUnit4.class)
public class PlaylistImportLiveDeviceTest {
    @Test public void importsPublicYouTubePlaylistEndToEnd() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(context.getPackageName().endsWith(".verification"));
        assumeTrue("true".equals(arguments.getString("livePlaylistImport")));

        PlaylistImport.Link link = PlaylistImport.find(
                "https://music.youtube.com/playlist?list=OLAK5uy_mkSFLs89OXrugKhAxzlVKezh4hnNkOMwk");
        assertNotNull(link);
        PlaylistImport.Playlist source = PlaylistImport.fetch(link);
        assertFalse(source.tracks.isEmpty());

        PlaylistManager manager = PlaylistManager.getInstance(context);
        String temporaryName = "__MusicBox_verification_import__";
        manager.deletePlaylist(temporaryName);
        try {
            PlaylistImport.Outcome result = PlaylistImport.importInto(
                    manager, source, temporaryName, null, () -> false);
            assertEquals(source.tracks.size(), result.added);
            assertEquals(0, result.skipped);
            assertEquals(source.tracks.size(), manager.getSongPaths(temporaryName).size());
        } finally {
            manager.deletePlaylist(temporaryName);
        }
    }
}
