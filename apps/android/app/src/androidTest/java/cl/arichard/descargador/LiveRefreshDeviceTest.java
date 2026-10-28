package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.model.Song;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

@RunWith(AndroidJUnit4.class)
public class LiveRefreshDeviceTest {
    private Context context;
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
    private Activity launch(Class<? extends Activity> type) {
        return InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getPackageName().equals("cl.arichard.descargador.verification"));
    }
    @Test public void statisticsStayAttachedAcrossRepeatedLiveUpdates() throws Exception {
        StatisticsActivity activity = (StatisticsActivity) launch(StatisticsActivity.class);
        try {
            View root = main(() -> ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0));
            main(() -> { activity.findViewById(android.R.id.button2).performClick(); return true; });
            ClickStats stats = ClickStats.getInstance(context);
            Song sample = new Song(0, "Estabilidad de muestra", "Verificación", "", 10000,
                    "/verification/live-refresh.mp3", "Verificación", 0);
            for (int index = 0; index < 12; index++) {
                main(() -> { stats.recordListening(sample, "Lista de estabilidad", 500, false); stats.flushListening(); return true; });
                Thread.sleep(500);
                assertSame(root, main(() -> ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0)));
                assertTrue(main(() -> ((com.google.android.material.button.MaterialButton)
                        activity.findViewById(android.R.id.button2)).isChecked()));
            }
        } finally { main(() -> { activity.finish(); return true; }); }
    }
    @Test public void devicesKeepTheSameCardsAcrossHeartbeats() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("musicbox_cloud", 0);
        String previous = prefs.getString("devices_json", null);
        SettingsActivity activity = null;
        try {
            JSONObject device = new JSONObject().put("name", "PC de verificación").put("platform", "Windows").put("lastSeen", 100000);
            prefs.edit().putString("devices_json", new JSONObject().put("verification-pc", device).toString()).commit();
            activity = (SettingsActivity) launch(SettingsActivity.class);
            final SettingsActivity opened = activity;
            LinearLayout list = main(() -> opened.findViewById(R.id.layoutConnectedDevices));
            View card = main(() -> list.getChildAt(0));
            assertNotNull(card);
            for (int index = 0; index < 12; index++) {
                device.put("lastSeen", 100000 + index * 60000L);
                prefs.edit().putString("devices_json", new JSONObject().put("verification-pc", device).toString()).commit();
                Thread.sleep(500);
                assertSame(card, main(() -> list.getChildAt(0)));
                assertEquals(1f, main(card::getAlpha), .01f);
            }
        } finally {
            if (activity != null) {
                final SettingsActivity opened = activity;
                main(() -> { opened.finish(); return true; });
            }
            if (previous == null) prefs.edit().remove("devices_json").commit();
            else prefs.edit().putString("devices_json", previous).commit();
        }
    }
}
