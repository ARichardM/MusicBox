package cl.arichard.descargador;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.adapter.PlaylistAdapter;
import cl.arichard.descargador.data.PlaylistManager;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es-w411dp-h891dp")
public class PlaylistOrganizationUiTest {
    private void call(MainActivity activity, String name, Class<?> type, Object argument) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, type);
        method.setAccessible(true); method.invoke(activity, argument);
    }
    private void touch(RecyclerView rows, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        rows.dispatchTouchEvent(event); event.recycle();
    }
    @Test public void holdingAndDraggingAPlaylistPersistsTheOrderAndDoesNotOpenIt() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("musicbox_prefs", 0).edit().putBoolean("all_permissions_requested_v1", true).commit();
        context.getSharedPreferences("library_navigation", 0).edit()
                .putInt("mode", 3).putBoolean("search_tab", false).commit();
        PlaylistManager manager = PlaylistManager.getInstance(context);
        manager.resetForAccount();
        for (String name : Arrays.asList("Viajes", "Chill", "Gym")) manager.createPlaylist(name);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create().start().visible()) {
            MainActivity activity = controller.get();
            call(activity, "setLibraryMode", int.class, 3);
            call(activity, "switchTab", boolean.class, false);
            View decor = activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 411, 891);
            RecyclerView rows = activity.findViewById(R.id.recyclerLibrary);
            rows.measure(View.MeasureSpec.makeMeasureSpec(379, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
            rows.layout(0, 0, 379, 500);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertEquals(3, rows.getAdapter().getItemCount());
            View first = rows.findViewHolderForAdapterPosition(0).itemView;
            View last = rows.findViewHolderForAdapterPosition(1).itemView;
            float start = first.getTop() + first.getHeight() / 2f;
            float end = last.getBottom() - 1;
            long down = SystemClock.uptimeMillis();
            touch(rows, down, MotionEvent.ACTION_DOWN, 100, start);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100));
            for (int step = 1; step <= 12; step++) {
                touch(rows, down, MotionEvent.ACTION_MOVE, 100, start + (end - start) * step / 12f);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
            }
            touch(rows, down, MotionEvent.ACTION_UP, 100, end);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue(rows.getAdapter() instanceof PlaylistAdapter);
            assertEquals(Arrays.asList("Chill", "Viajes", "Gym"), ((PlaylistAdapter) rows.getAdapter()).getPlaylistOrder());
            assertEquals(Arrays.asList("Chill", "Viajes", "Gym"), manager.getVisiblePlaylistNames(Collections.emptyList()));
        }
    }
}
