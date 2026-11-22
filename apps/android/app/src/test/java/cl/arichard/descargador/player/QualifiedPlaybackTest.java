package cl.arichard.descargador.player;

import android.app.Application;
import android.media.AudioDeviceInfo;
import android.view.SurfaceHolder;
import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.model.Song;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class QualifiedPlaybackTest {
    private static class Audio implements PlaybackEngine {
        int position;
        @Override public void prepare(String source, Map<String, String> headers, boolean videoMode, boolean allowOffload, SurfaceHolder display, Callback callback) {}
        @Override public void play() {}
        @Override public void pause() {}
        @Override public void seekTo(int positionMs) { position = positionMs; }
        @Override public boolean isPlaying(){return true;}
        @Override public int getCurrentPosition(){return position;}
        @Override public int getDuration(){return 120000;}
        @Override public int getVideoWidth(){return 0;}
        @Override public int getVideoHeight(){return 0;}
        @Override public void setVolume(float volume){}
        @Override public void setDisplay(SurfaceHolder holder){}
        @Override public AudioDeviceInfo getRoutedDevice(){return null;}
        @Override public boolean isSleepingForOffload(){return false;}
        @Override public void release(){}
    }
    private static class Player extends PlayerManager {@Override protected void startCurrent(){}}
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=PlayerManager.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static void call(Object target,String name)throws Exception {
        Method method=PlayerManager.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);
    }
    private static void listen(Player player,Audio audio,int seconds)throws Exception {
        for(int i=0;i<seconds;i++){audio.position+=1000;ShadowSystemClock.advanceBy(Duration.ofSeconds(1));call(player,"sampleListening");}
    }
    @Test public void recoveryDoesNotDuplicateAQualifiedPlayButAnExplicitReplayCanCountAgain()throws Exception {
        var context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("player",0).edit().clear().putBoolean("autoplay",false).commit();
        Player player=new Player();player.init(context);
        Song song=new Song(0,"Qualified test","Artist","Album",120000,"youtube:qualify1234","",0);
        Audio audio=new Audio();
        player.playSong(song,Collections.singletonList(song),false);
        set(player,"activePlayer",audio);set(player,"prepared",true);call(player,"startListening");
        int baseline=ClickStats.getInstance(context).count(song);
        listen(player,audio,29);assertEquals(baseline,ClickStats.getInstance(context).count(song));
        listen(player,audio,1);assertEquals(baseline+1,ClickStats.getInstance(context).count(song));
        call(player,"startListening"); // Recovering the source or changing audio/video prepares the same listening session again.
        listen(player,audio,35);assertEquals(baseline+1,ClickStats.getInstance(context).count(song));
        player.playSong(song,Collections.singletonList(song),false);audio.position=0;call(player,"startListening");
        listen(player,audio,30);assertEquals(baseline+2,ClickStats.getInstance(context).count(song));
        player.stopAndRelease();
    }
}
