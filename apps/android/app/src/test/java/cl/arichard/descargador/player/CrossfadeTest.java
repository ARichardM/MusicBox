package cl.arichard.descargador.player;

import android.app.Application;
import android.media.AudioDeviceInfo;
import android.view.SurfaceHolder;
import cl.arichard.descargador.model.Song;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class CrossfadeTest {
    private static class Audio implements PlaybackEngine {
        boolean released;
        float volume=1;
        @Override public void prepare(String source, Map<String, String> headers, boolean videoMode, boolean allowOffload, SurfaceHolder display, Callback callback) {}
        @Override public void play() {}
        @Override public void pause() {}
        @Override public void seekTo(int positionMs) {}
        @Override public boolean isPlaying() { return true; }
        @Override public int getCurrentPosition() { return 57000; }
        @Override public int getDuration() { return 60000; }
        @Override public int getVideoWidth() { return 0; }
        @Override public int getVideoHeight() { return 0; }
        @Override public void setVolume(float volume) { this.volume=volume; }
        @Override public void setDisplay(SurfaceHolder holder) {}
        @Override public AudioDeviceInfo getRoutedDevice() { return null; }
        @Override public boolean isSleepingForOffload() { return false; }
        @Override public void release() { released=true; }
    }
    private static class Player extends PlayerManager {
        int starts;
        @Override protected void startCurrent() { starts++; }
    }
    private static void set(PlayerManager player,String name,Object value) throws Exception {
        Field field=PlayerManager.class.getDeclaredField(name);field.setAccessible(true);field.set(player,value);
    }
    @Test public void preparedLocalNextTrackOverlapsAndPauseReleasesOutgoingPlayer() throws Exception {
        var context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player",0).edit().clear().putBoolean("autoplay",false).commit();
        Player player=new Player();player.init(context);player.setCrossfadeSeconds(5);
        File file=File.createTempFile("crossfade", ".wav",context.getCacheDir());
        Song first=new Song(0,"First","Artist","Album",60000,file.getPath(),"",0);
        Song next=new Song(1,"Next","Artist","Album",60000,file.getPath()+"-next","",0);
        File nextFile=new File(next.getPath());assertTrue(nextFile.createNewFile());
        Audio old=new Audio();
        try {
            set(player,"queue",new ArrayList<>(Arrays.asList(first,next)));
            set(player,"currentSong",first);set(player,"currentIndex",0);
            set(player,"repeatMode",PlayerManager.REPEAT_OFF);set(player,"activePlayer",old);set(player,"prepared",true);
            var maybe=PlayerManager.class.getDeclaredMethod("maybeCrossfade");maybe.setAccessible(true);
            assertEquals(true,maybe.invoke(player));
            assertSame(next,player.getCurrentSong());assertEquals(1,player.starts);
            assertFalse(old.released);
            player.pause();assertTrue(old.released);
            player.setCrossfadeSeconds(99);assertEquals(12,player.getCrossfadeSeconds());
            player.setCrossfadeSeconds(0);assertEquals(0,player.getCrossfadeSeconds());
        } finally {player.stopAndRelease();file.delete();nextFile.delete();}
    }
}
