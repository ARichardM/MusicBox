package cl.arichard.descargador;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import cl.arichard.descargador.util.AccentColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** No account, playback, device library or update checks are involved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "es")
public class AccentColorTest {
    public static class PaletteActivity extends AppearanceActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_MusicBox);
            super.onCreate(state);
            setContentView(R.layout.activity_settings);
        }
    }

    @Test public void blueAndOrangeResolveAcrossLightAndDarkIncludingDialogsAndDrawables() {
        Context app = RuntimeEnvironment.getApplication();
        for (boolean dark : new boolean[]{false, true}) {
            RuntimeEnvironment.setQualifiers(dark ? "es-night" : "es-notnight");
            AppCompatDelegate.setDefaultNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
            for (String accent : new String[]{"blue", "orange"}) {
                app.getSharedPreferences("musicbox_prefs", 0).edit().putString(AccentColors.KEY, accent).commit();
                try (ActivityController<PaletteActivity> controller = Robolectric.buildActivity(PaletteActivity.class).setup()) {
                    PaletteActivity activity = controller.get();
                    int expected = Color.parseColor("orange".equals(accent)
                            ? (dark ? "#FF9F43" : "#B94700") : (dark ? "#0A84FF" : "#007AFF"));
                    assertEquals(expected, AccentColors.getColor(activity, R.color.accent));
                    assertEquals(expected, AccentColors.getColor(activity, R.color.now_playing));
                    assertEquals(expected, AccentColors.getColor(activity, R.color.accent_primary));
                    TextView paste = LayoutInflater.from(activity).inflate(R.layout.activity_downloader, null)
                            .findViewById(R.id.btnPasteLinks);
                    assertEquals(expected, paste.getCurrentTextColor());
                    // Inflate every main screen to catch unresolved attributes in XML/widgets/vectors.
                    for (int layout : new int[]{R.layout.activity_main, R.layout.activity_now_playing,
                            R.layout.activity_importer, R.layout.item_song, R.layout.item_video_candidate})
                        assertNotNull(LayoutInflater.from(activity).inflate(layout, null));
                    Drawable button = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, R.drawable.bg_button_accent);
                    assertEquals(expected, ((GradientDrawable) button).getColor().getDefaultColor());
                    androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                            .setTitle("Color").setPositiveButton("Aceptar", null).show();
                    assertEquals(expected, dialog.getButton(-1).getCurrentTextColor());
                    dialog.dismiss();
                    assertEquals(accent, AccentColors.selected(activity));
                }
            }
        }
    }

    @Test public void invalidStoredAccentFallsBackToBlueAndUnrelatedColorsStayIntact() {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("musicbox_prefs", 0).edit().putString(AccentColors.KEY, "invalid").commit();
        assertEquals("blue", AccentColors.selected(app));
        assertFalse(AccentColors.isValid(null));
        assertFalse(AccentColors.isValid("invalid"));
        assertTrue(AccentColors.isValid("orange"));
        try (ActivityController<PaletteActivity> controller = Robolectric.buildActivity(PaletteActivity.class).setup()) {
            PaletteActivity activity = controller.get();
            assertEquals(androidx.core.content.ContextCompat.getColor(activity, R.color.heart_red),
                    AccentColors.getColor(activity, R.color.heart_red));
        }
    }
}
