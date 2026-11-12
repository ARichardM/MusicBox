package cl.arichard.descargador;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import cl.arichard.descargador.util.AccentColors;
import com.google.android.material.button.MaterialButtonToggleGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Exercises the actual settings screen in the isolated verification package. */
@RunWith(AndroidJUnit4.class)
public class AccentAppearanceDeviceTest {
    private static <T> T main(Supplier<T> action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action.get();
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    private static Activity resumed() {
        return main(() -> ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .stream().findFirst().orElse(null));
    }

    private static void waitFor(Supplier<Boolean> ready) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (!ready.get() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue("Appearance settles after recreation", ready.get());
    }

    @Test public void settingsSwitchesFourThemesAndOrangeHasNoBlueInterfacePixels() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Only the independent verification app", context.getPackageName().endsWith(".verification"));
        SharedPreferences prefs = context.getSharedPreferences("musicbox_prefs", 0);
        String previousAccent = prefs.getString(AccentColors.KEY, "blue");
        boolean previousDark = prefs.getBoolean("dark_mode", true);
        try {
            InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitFor(() -> resumed() instanceof SettingsActivity);
            for (boolean dark : new boolean[]{false, true}) {
                main(() -> {
                    ((com.google.android.material.switchmaterial.SwitchMaterial) resumed().findViewById(R.id.switchTheme))
                            .setChecked(dark);
                    return true;
                });
                waitFor(() -> resumed() instanceof SettingsActivity && main(() ->
                        (resumed().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                == (dark ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO)));
                Thread.sleep(350);
                for (String accent : new String[]{"blue", "orange"}) {
                    main(() -> {
                        ((MaterialButtonToggleGroup) resumed().findViewById(R.id.accentColorToggle))
                                .check("orange".equals(accent) ? R.id.accentOrange : R.id.accentBlue);
                        return true;
                    });
                    int expected = Color.parseColor("orange".equals(accent)
                            ? (dark ? "#FF9F43" : "#B94700") : (dark ? "#0A84FF" : "#007AFF"));
                    try {
                        waitFor(() -> resumed() instanceof SettingsActivity && main(() ->
                                AccentColors.getColor(resumed(), R.color.accent) == expected));
                    } catch (AssertionError failure) {
                        Activity current = resumed();
                        fail("Palette " + accent + " dark=" + dark + " expected=" + Integer.toHexString(expected)
                                + " actual=" + (current == null ? "no activity" : main(() -> Integer.toHexString(
                                        AccentColors.getColor(current, R.color.accent))) + " uiMode=" + current.getResources().getConfiguration().uiMode));
                    }
                    Thread.sleep(850);
                    // Azul es el valor por defecto; pulsarlo ya seleccionado no necesita escribir una preferencia.
                    assertEquals(accent, prefs.getString(AccentColors.KEY, "blue"));
                    Bitmap image = main(() -> {
                        View content = resumed().findViewById(android.R.id.content);
                        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
                        content.draw(new Canvas(bitmap));
                        return bitmap;
                    });
                    File folder = new File(context.getExternalFilesDir(null), "accent-previews");
                    folder.mkdirs();
                    try (FileOutputStream stream = new FileOutputStream(new File(folder,
                            "android-" + accent + "-" + (dark ? "dark" : "light") + "-settings.png"))) {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
                    }
                    if ("orange".equals(accent)) {
                        float[] hsv = new float[3];
                        int bluePixels = 0;
                        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                            Color.colorToHSV(image.getPixel(x, y), hsv);
                            if (hsv[0] > 173 && hsv[0] < 267 && hsv[1] > .4f && hsv[2] > .18f) bluePixels++;
                        }
                        assertEquals("No saturated blue remains in the orange settings interface", 0, bluePixels);
                    }
                    image.recycle();
                }
            }
        } finally {
            main(() -> {
                Activity current = resumed();
                if (current != null) current.finish();
                prefs.edit().putString(AccentColors.KEY, previousAccent).putBoolean("dark_mode", previousDark).apply();
                AppCompatDelegate.setDefaultNightMode(previousDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
                return true;
            });
        }
    }
}
