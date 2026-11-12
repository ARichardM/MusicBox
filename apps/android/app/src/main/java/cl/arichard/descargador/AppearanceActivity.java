package cl.arichard.descargador;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import cl.arichard.descargador.util.AccentColors;

/** Applies the saved accent to each screen, including screens restored from the back stack. */
public abstract class AppearanceActivity extends AppCompatActivity {
    private String appliedAccent;
    private boolean recreating;
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener = (prefs, key) -> {
        if (AccentColors.KEY.equals(key)) refreshAccent();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        appliedAccent = AccentColors.selected(this);
        AccentColors.apply(this);
    }

    @Override protected void onStart() {
        super.onStart();
        getSharedPreferences("musicbox_prefs", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(appearanceListener);
        refreshAccent();
    }

    private void refreshAccent() {
        if (recreating || AccentColors.selected(this).equals(appliedAccent)) return;
        recreating = true;
        // Defer until the current preference/control callback has finished.
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) recreate();
        });
    }

    @Override protected void onStop() {
        getSharedPreferences("musicbox_prefs", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(appearanceListener);
        super.onStop();
    }
}
