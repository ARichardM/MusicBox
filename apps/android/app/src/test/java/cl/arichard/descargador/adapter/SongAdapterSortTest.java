package cl.arichard.descargador.adapter;

import android.app.Application;

import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.model.Song;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class SongAdapterSortTest {
    private SongAdapter adapter;

    private static Song song(String title, String artist, long seconds, long added) {
        return new Song(added, title, artist, "", seconds * 1000, "/m/" + title + ".mp3", "m", added);
    }

    @Before public void setUp() {
        ClickStats.getInstance(RuntimeEnvironment.getApplication()).resetForAccount();
        adapter = new SongAdapter(RuntimeEnvironment.getApplication(), Arrays.asList(
                song("Beta", "Zeta", 200, 30), song("álbum", "Ana", 100, 10), song("Alfa", "Ana", 300, 20)), new SongAdapter.OnSongClickListener() {
            @Override public void onSongClick(Song song, List<Song> queue, int position) {}
            @Override public void onFavoriteClick(Song song, int position) {}
            @Override public void onMenuClick(Song song, android.view.View anchor, int position) {}
        });
    }

    private List<String> titles() {
        List<String> titles = new ArrayList<>();
        for (Song song : adapter.getDisplayedList()) titles.add(song.getTitle());
        return titles;
    }

    @Test public void defaultKeepsTheReceivedOrderAndCanBeReversed() {
        assertEquals(Arrays.asList("Beta", "álbum", "Alfa"), titles());
        adapter.setSort(SongAdapter.Sort.DEFAULT, false);
        assertEquals(Arrays.asList("Alfa", "álbum", "Beta"), titles());
    }

    @Test public void titlesIgnoreCaseAndAccents() {
        adapter.setSort(SongAdapter.Sort.TITLE, true);
        assertEquals(Arrays.asList("álbum", "Alfa", "Beta"), titles());
        adapter.setSort(SongAdapter.Sort.TITLE, false);
        assertEquals(Arrays.asList("Beta", "Alfa", "álbum"), titles());
    }

    @Test public void artistsThenTitles() {
        adapter.setSort(SongAdapter.Sort.ARTIST, true);
        assertEquals(Arrays.asList("álbum", "Alfa", "Beta"), titles());
    }

    @Test public void artistFilterIsExactAndCombinesWithSearchWithoutChangingPicker() {
        adapter.setArtistFilter(" ana ");
        assertEquals(Arrays.asList("álbum", "Alfa"), titles());
        assertEquals(Arrays.asList("Ana", "Zeta"), adapter.getArtists());
        adapter.filter("Alfa");
        assertEquals(Arrays.asList("Alfa"), titles());
        assertEquals(Arrays.asList("Ana", "Zeta"), adapter.getArtists());
        adapter.filter("");
        adapter.setArtistFilter("");
        assertEquals(3, adapter.getItemCount());
        adapter.setArtistFilter("An");
        assertEquals(0, adapter.getItemCount());
    }

    @Test public void legacyOnlineAlbumsAndLocalSongsShareOneArtistChoice() {
        adapter.updateSongs(Arrays.asList(song("Local", "Ana", 200, 1),
                new Song(2, "Online", "ana · Álbum", "YouTube", 200000,
                        "youtube:abcdefghijk", "YouTube", 2), song("Otra", "Zeta", 200, 3)));
        assertEquals(Arrays.asList("Ana", "Zeta"), adapter.getArtists());
        adapter.setArtistFilter("Ana");
        assertEquals(Arrays.asList("Local", "Online"), titles());
    }

    @Test public void recentIsNewestFirstAndOldestWhenReversed() {
        adapter.setSort(SongAdapter.Sort.RECENT, true);
        assertEquals(Arrays.asList("Beta", "Alfa", "álbum"), titles());
        adapter.setSort(SongAdapter.Sort.RECENT, false);
        assertEquals(Arrays.asList("álbum", "Alfa", "Beta"), titles());
    }

    @Test public void durationGoesFromShortestToLongest() {
        adapter.setSort(SongAdapter.Sort.DURATION, true);
        assertEquals(Arrays.asList("álbum", "Beta", "Alfa"), titles());
        adapter.setSort(SongAdapter.Sort.DURATION, false);
        assertEquals(Arrays.asList("Alfa", "Beta", "álbum"), titles());
    }

    @Test public void mostPlayedFirst() {
        ClickStats stats = ClickStats.getInstance(RuntimeEnvironment.getApplication());
        Song alfa = adapter.getDisplayedList().get(2);
        stats.record(alfa); stats.record(alfa); stats.record(adapter.getDisplayedList().get(0));
        adapter.setSort(SongAdapter.Sort.PLAYS, true);
        assertEquals(Arrays.asList("Alfa", "Beta", "álbum"), titles());
        adapter.setSort(SongAdapter.Sort.PLAYS, false);
        assertEquals("Alfa", titles().get(2));
    }

    @Test public void filteringKeepsTheChosenOrder() {
        adapter.setSort(SongAdapter.Sort.TITLE, true);
        adapter.filter("a");
        assertEquals(Arrays.asList("álbum", "Alfa", "Beta"), titles());
        adapter.filter("be");
        assertEquals(Arrays.asList("Beta"), titles());
    }

    @Test public void selectAllTogglesAndTracksEverything() {
        assertFalse(adapter.areAllSelected());
        adapter.selectAll();
        assertTrue(adapter.areAllSelected());
        assertEquals(3, adapter.getSelectionCount());
        assertTrue(adapter.isSelectionMode());
        adapter.toggleSelectAll();
        assertFalse(adapter.isSelectionMode());
        assertEquals(0, adapter.getSelectionCount());
        adapter.activateSelectionMode(adapter.getDisplayedList().get(1));
        assertEquals(1, adapter.getSelectedSongs().size());
        adapter.toggleSelectAll();
        assertTrue(adapter.areAllSelected());
    }

    @Test public void selectedSongsFollowTheDisplayedOrder() {
        adapter.setSort(SongAdapter.Sort.TITLE, true);
        adapter.selectAll();
        List<String> picked = new ArrayList<>();
        for (Song song : adapter.getSelectedSongs()) picked.add(song.getTitle());
        assertEquals(titles(), picked);
    }
}
