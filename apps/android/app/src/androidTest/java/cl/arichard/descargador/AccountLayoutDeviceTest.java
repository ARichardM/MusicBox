package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import cl.arichard.descargador.data.PlaylistManager;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Visual geometry and scrolling, exclusively with the verification package and synthetic tracks. */
@RunWith(AndroidJUnit4.class)
public class AccountLayoutDeviceTest {
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->value.set(action.get()));return value.get();
    }
    private void waitFor(Supplier<Boolean> condition) throws Exception {
        long end=System.currentTimeMillis()+12000;
        while(!condition.get()&&System.currentTimeMillis()<end)Thread.sleep(60);
        assertTrue("Expected isolated UI state",condition.get());
    }
    private <T extends Activity> T launch(Class<T> type) throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cl.arichard.descargador.verification",context.getPackageName());
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.wakeUp();
        androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(0);
        main(()->{context.startActivity(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));return true;});
        AtomicReference<T> current=new AtomicReference<>();
        waitFor(()->main(()->{for(Activity activity:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(type.isInstance(activity))current.set(type.cast(activity));return current.get()!=null&&current.get().hasWindowFocus();}));
        return current.get();
    }
    @Test public void settingsCardsFollowOneReadableColumn() throws Exception {
        SettingsActivity activity=launch(SettingsActivity.class);
        try {
            main(()->{
                View account=activity.findViewById(R.id.cardAccount),appearance=activity.findViewById(R.id.cardAppearance),downloads=activity.findViewById(R.id.cardDownloadQuality);
                assertTrue(account.getBottom()<=appearance.getTop());
                assertTrue("Playback section and downloads follow appearance",appearance.getBottom()<downloads.getTop());
                assertTrue("Settings cards measure their contents",account.getHeight()>0&&appearance.getHeight()>0&&downloads.getHeight()>0);
                assertTrue("Account photos clip to the circular frame",
                        activity.findViewById(R.id.imgSettingsAvatar).getClipToOutline());
                return true;
            });
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(new java.io.File(activity.getExternalFilesDir(null),"settings-account-layout.png"));
        } finally {main(()->{activity.finish();return true;});}
    }

    @Test public void settingsAndTheQueueShareAutoplayAndShowTheArtworkDownloadSwitch() throws Exception {
        SettingsActivity activity = launch(SettingsActivity.class);
        var player = cl.arichard.descargador.player.PlayerManager.getInstance();
        var preferences = activity.getSharedPreferences("player", Context.MODE_PRIVATE);
        boolean oldAutoplay = player.isAutoplay();
        boolean oldArtwork = AnimatedArtwork.downloadsEnabled(activity);
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        UiDevice device = UiDevice.getInstance(instrumentation);
        try {
            SwitchMaterial artwork = main(() -> findSwitch(activity.findViewById(android.R.id.content),
                    cl.arichard.descargador.util.Tr.t("Descargar portadas animadas")));
            SwitchMaterial autoplay = main(() -> findSwitch(activity.findViewById(android.R.id.content),
                    cl.arichard.descargador.util.Tr.t("Reproducción automática")));
            assertNotNull(artwork);
            assertNotNull(autoplay);
            main(() -> {
                artwork.requestRectangleOnScreen(new android.graphics.Rect(0, 0, artwork.getWidth(), artwork.getHeight()), true);
                artwork.performClick();
                autoplay.performClick();
                return true;
            });
            assertEquals(!oldArtwork, AnimatedArtwork.downloadsEnabled(activity));
            assertEquals(!oldAutoplay, player.isAutoplay());
            device.takeScreenshot(new java.io.File(activity.getExternalFilesDir(null), "settings-playback.png"));
            main(() -> { cl.arichard.descargador.player.QueueSheet.show(activity); return true; });
            device.waitForIdle(2500);
            var toggle = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc(
                    cl.arichard.descargador.util.Tr.t("Reproducción automática"))), 6000);
            assertNotNull(toggle);
            assertEquals(!oldAutoplay, toggle.isChecked());
            toggle.click();
            waitFor(() -> player.isAutoplay() == oldAutoplay);
            waitFor(() -> main(() -> autoplay.isChecked() == oldAutoplay && player.isAutoplay() == oldAutoplay));
        } finally {
            main(() -> {
                preferences.edit().putBoolean(AnimatedArtwork.DOWNLOAD_PREFERENCE, oldArtwork).apply();
                player.setAutoplay(oldAutoplay);
                activity.finish();
                return true;
            });
        }
    }

    private SwitchMaterial findSwitch(View view, String label) {
        if (view instanceof SwitchMaterial && label.contentEquals(((SwitchMaterial) view).getText()))
            return (SwitchMaterial) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SwitchMaterial match = findSwitch(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }
    @Test public void collectionControlsHideDownAndReturnUp() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("cl.arichard.descargador.verification",context.getPackageName());
        PlaylistManager manager=PlaylistManager.getInstance(context);
        JSONObject previous=new JSONObject(manager.exportJson().toString());
        JSONArray entries=new JSONArray();JSONObject online=new JSONObject();
        for(int i=0;i<80;i++){String id=String.format(java.util.Locale.ROOT,"chrome%05d",i);entries.put("youtube:"+id);online.put(id,new JSONObject().put("title","Tema "+i).put("channel","Demostración").put("duration",120));}
        manager.importJson(new JSONObject().put("playlists",new JSONObject().put("Favoritos",new JSONArray()).put("Prueba de controles",entries)).put("onlineTracks",online));
        context.getSharedPreferences("musicbox_prefs",0).edit().putBoolean("all_permissions_requested_v1",true).commit();
        PreviewMainActivity activity=launch(PreviewMainActivity.class);
        try {
            waitFor(()->main(()->new androidx.lifecycle.ViewModelProvider(activity).get(MainUiState.class).libraryLoaded));
            main(()->{try{
                Method mode=MainActivity.class.getDeclaredMethod("setLibraryMode",int.class);mode.setAccessible(true);mode.invoke(activity,3);
                Method tab=MainActivity.class.getDeclaredMethod("switchTab",boolean.class);tab.setAccessible(true);tab.invoke(activity,false);
                Method open=MainActivity.class.getDeclaredMethod("showPlaylistSongs",String.class);open.setAccessible(true);open.invoke(activity,"Prueba de controles");
            }catch(Exception error){throw new AssertionError(error);}return true;});
            View controls=activity.findViewById(R.id.collectionControls),rows=activity.findViewById(R.id.recyclerLibrary);
            waitFor(()->main(()->controls.getHeight()>0&&rows.getHeight()>200));
            int[] rect=main(()->{int[] location=new int[2];rows.getLocationOnScreen(location);return new int[]{location[0]+rows.getWidth()/2,location[1],rows.getHeight()};});
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            // Keep the gesture inside the rows: the mini-player overlays the bottom of this viewport.
            device.swipe(rect[0],rect[1]+rect[2]*2/3,rect[0],rect[1]+80,24);
            waitFor(()->main(()->controls.getHeight()==0));
            Thread.sleep(400);
            assertEquals("Controls remain collapsed after layout settles",0,(int)main(()->controls.getHeight()));
            rect=main(()->{int[] location=new int[2];rows.getLocationOnScreen(location);return new int[]{location[0]+rows.getWidth()/2,location[1],rows.getHeight()};});
            device.swipe(rect[0],rect[1]+80,rect[0],rect[1]+rect[2]*2/3,24);
            waitFor(()->main(()->controls.getHeight()>0&&controls.getAlpha()>.95f));
        } catch(Throwable error){
            System.out.println(main(()->{androidx.recyclerview.widget.RecyclerView list=activity.findViewById(R.id.recyclerLibrary);View controls=activity.findViewById(R.id.collectionControls);return "Chrome height="+controls.getHeight()+" alpha="+controls.getAlpha()+" rows="+list.getAdapter().getItemCount()+" canUp="+list.canScrollVertically(-1); }));
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(new java.io.File(activity.getExternalFilesDir(null),"chrome-failure.png"));
            throw error;
        } finally {main(()->{activity.finish();return true;});manager.importJson(previous);}
    }
}
