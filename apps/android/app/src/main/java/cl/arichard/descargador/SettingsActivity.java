package cl.arichard.descargador;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import cl.arichard.descargador.data.CloudSyncManager;
import cl.arichard.descargador.data.DeviceSessions;
import cl.arichard.descargador.data.LibraryFolders;
import cl.arichard.descargador.util.AppNotice;
import cl.arichard.descargador.util.Tr;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/** Account, appearance and connected devices. */
public final class SettingsActivity extends AppCompatActivity {
    private static final String DEVICES_KEY = "devices_json";
    private SharedPreferences cloudPrefs;
    private final java.util.Map<String, View> deviceRows = new java.util.HashMap<>();
    private final long fallbackSeen = System.currentTimeMillis();
    private final SharedPreferences.OnSharedPreferenceChangeListener devicesListener = (prefs, key) -> {
        if (DEVICES_KEY.equals(key)) runOnUiThread(this::renderDevices);
    };

    private final ActivityResultLauncher<android.net.Uri> pickFolder =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                String path = LibraryFolders.pathFromTree(uri);
                if (path == null) {
                    AppNotice.error(findViewById(R.id.btnSettingsLogout), Tr.t("No se pudo leer esa carpeta."));
                    return;
                }
                LibraryFolders.add(this, path);
                renderFolders();
            });

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.btnSettingsBack).setOnClickListener(v -> finish());
        setupLanguage();
        setupTheme();
        findViewById(R.id.btnAddFolder).setOnClickListener(v -> pickFolder.launch(null));
        findViewById(R.id.rowDownloader).setOnClickListener(v -> startActivity(new Intent(this, DownloaderActivity.class)));
        cl.arichard.descargador.util.SafeTap.installRowTap(findViewById(R.id.rowDownloader));
        renderFolders();
        renderStats();
        findViewById(R.id.cardStats).setOnClickListener(v -> startActivity(new Intent(this, StatisticsActivity.class)));
        cl.arichard.descargador.util.SafeTap.installRowTap(findViewById(R.id.cardStats));
        setupAbout();

        FirebaseUser user = com.google.firebase.FirebaseApp.getApps(this).isEmpty()
                ? null : FirebaseAuth.getInstance().getCurrentUser();
        String name = user != null && user.getDisplayName() != null ? user.getDisplayName() : Tr.t("Cuenta de Google");
        String email = user != null && user.getEmail() != null ? user.getEmail() : "";
        ((TextView) findViewById(R.id.txtSettingsName)).setText(name);
        ((TextView) findViewById(R.id.txtSettingsEmail)).setText(email);
        String initial = (name.isEmpty() ? "U" : name.substring(0, 1)).toUpperCase(java.util.Locale.ROOT);
        ((TextView) findViewById(R.id.txtSettingsAvatar)).setText(initial);

        cloudPrefs = getSharedPreferences("musicbox_cloud", MODE_PRIVATE);
        cloudPrefs.registerOnSharedPreferenceChangeListener(devicesListener);
        renderDevices();

        findViewById(R.id.btnSettingsLogout).setVisibility(user == null ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnSettingsLogout).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(Tr.t("Cerrar sesión"))
                .setMessage(Tr.t("Tus descargas permanecerán en este dispositivo."))
                .setNegativeButton(Tr.t("Cancelar"), null)
                .setPositiveButton(Tr.t("Cerrar sesión"), (dialog, which) -> {
                    DeviceSessions.markSignedOut(this);
                    FirebaseAuth.getInstance().signOut();
                    startActivity(new Intent(this, LoginActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                }).show());

        if (state == null) animateIn(findViewById(R.id.cardAccount), 0);
        if (state == null) animateIn(findViewById(R.id.rowDownloader), 40);
        if (state == null) animateIn(findViewById(R.id.cardAppearance), 80);
    }

    /** Total "clics" (plays) across the library, plus the most played of the recent songs. */
    private void renderStats() {
        cl.arichard.descargador.data.ClickStats stats = cl.arichard.descargador.data.ClickStats.getInstance(this);
        int total = stats.total(), distinct = stats.playedSongs();
        ((TextView) findViewById(R.id.txtStatsClicks)).setText(Tr.t("Ver mis estadísticas") + "  ›");
        ((TextView) findViewById(R.id.txtStatsClicks)).setTextSize(18);
        ((TextView) findViewById(R.id.txtStatsSummary)).setText(Tr.f("%d clics en %d canciones", total, distinct));
        StringBuilder top = new StringBuilder();
        java.util.List<Object[]> ranked = stats.top(3);
        for (int i = 0; i < ranked.size(); i++) {
            if (top.length() > 0) top.append("\n");
            top.append(i + 1).append(". ").append(ranked.get(i)[0]).append("  ▶ ")
                    .append(cl.arichard.descargador.util.Clicks.format((Integer) ranked.get(i)[1]));
        }
        TextView topView = findViewById(R.id.txtStatsTop);
        topView.setText(top);
        topView.setVisibility(android.view.View.GONE);
    }

    // ------------------------------------------------------------ versión y actualizaciones

    private cl.arichard.descargador.util.UpdateChecker.Release foundRelease;
    private boolean checkingUpdate;

    /** Cambia un texto con un fundido corto para que el cambio de estado se sienta suave. */
    private void swapText(TextView view, CharSequence text) {
        if (text.toString().contentEquals(view.getText())) return;
        view.animate().cancel();
        view.animate().alpha(0f).translationY(dp(4)).setDuration(110).withEndAction(() -> {
            view.setText(text);
            view.setTranslationY(-dp(4));
            view.animate().alpha(1f).translationY(0f).setDuration(200).start();
        }).start();
    }

    private void showBadge(String text, int background, int foreground) {
        TextView badge = findViewById(R.id.txtUpdateBadge);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(background);
        badge.setBackground(shape);
        badge.setTextColor(foreground);
        badge.setText(text);
        if (badge.getVisibility() != View.VISIBLE) {
            badge.setScaleX(0.6f);
            badge.setScaleY(0.6f);
            badge.setAlpha(0f);
            badge.setVisibility(View.VISIBLE);
        }
        badge.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
    }

    private void setupAbout() {
        ((TextView) findViewById(R.id.txtAboutTitle)).setText(Tr.t("ACERCA DE"));
        ((TextView) findViewById(R.id.txtVersionNumber)).setText(Tr.f("Versión %s", cl.arichard.descargador.util.UpdateChecker.versionName(this)));
        TextView status = findViewById(R.id.txtUpdateStatus);
        status.setText(Tr.t("Toca el botón para ver si hay una versión nueva."));
        com.google.android.material.button.MaterialButton button = findViewById(R.id.btnCheckUpdate);
        button.setText(Tr.t("Buscar actualizaciones"));
        button.setOnClickListener(v -> {
            if (foundRelease != null) cl.arichard.descargador.util.UpdateChecker.showSheet(this, foundRelease);
            else checkForUpdate(true);
        });
        checkForUpdate(false);
    }

    /** [manual] muestra siempre el resultado; la comprobación al abrir el perfil solo avisa si hay algo nuevo. */
    private void checkForUpdate(boolean manual) {
        if (checkingUpdate) return;
        checkingUpdate = true;
        foundRelease = null;
        TextView status = findViewById(R.id.txtUpdateStatus);
        View bar = findViewById(R.id.barUpdate);
        com.google.android.material.button.MaterialButton button = findViewById(R.id.btnCheckUpdate);
        button.setEnabled(false);
        button.setText(Tr.t("Buscando…"));
        swapText(status, Tr.t("Buscando actualizaciones…"));
        bar.setAlpha(0f);
        bar.setVisibility(View.VISIBLE);
        bar.animate().alpha(1f).setDuration(180).start();
        long started = android.os.SystemClock.uptimeMillis();
        cl.arichard.descargador.util.UpdateChecker.checkNow(this, (state, release) -> {
            // Un mínimo de tiempo para que el estado «buscando» se alcance a ver y no parpadee.
            long wait = Math.max(0, 700 - (android.os.SystemClock.uptimeMillis() - started));
            status.postDelayed(() -> {
                checkingUpdate = false;
                if (isFinishing() || isDestroyed()) return;
                bar.animate().alpha(0f).setDuration(200).withEndAction(() -> bar.setVisibility(View.GONE)).start();
                button.setEnabled(true);
                switch (state) {
                    case AVAILABLE:
                        foundRelease = release;
                        swapText(status, Tr.f("Hay una versión nueva: %s. Toca para ver las novedades.", release.version));
                        button.setText(Tr.t("Ver novedades y actualizar"));
                        showBadge(Tr.t("Nueva"), Color.rgb(10, 132, 255), Color.WHITE);
                        break;
                    case UP_TO_DATE:
                        swapText(status, Tr.t("Estás al día. Tienes la última versión de MusicBox."));
                        button.setText(Tr.t("Volver a buscar"));
                        showBadge(Tr.t("Al día"), Color.rgb(16, 48, 33), Color.rgb(48, 209, 88));
                        break;
                    default:
                        button.setText(Tr.t("Reintentar"));
                        swapText(status, manual ? Tr.t("No se pudo comprobar. Revisa tu conexión e inténtalo de nuevo.")
                                : Tr.t("Toca el botón para ver si hay una versión nueva."));
                }
            }, wait);
        });
    }

    @Override protected void onDestroy() {
        if (cloudPrefs != null) cloudPrefs.unregisterOnSharedPreferenceChangeListener(devicesListener);
        super.onDestroy();
    }

    private void animateIn(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    /** The theme lives here now; the choice is applied at once and synced with the account. */
    private void setupTheme() {
        SharedPreferences prefs = getSharedPreferences("musicbox_prefs", MODE_PRIVATE);
        SwitchMaterial toggle = findViewById(R.id.switchTheme);
        toggle.setChecked(prefs.getBoolean("dark_mode", true));
        toggle.setOnCheckedChangeListener((button, dark) -> {
            prefs.edit().putBoolean("dark_mode", dark).apply();
            CloudSyncManager.requestUpload();
            AppCompatDelegate.setDefaultNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        findViewById(R.id.rowTheme).setOnClickListener(v -> toggle.toggle());
    }

    /** Language follows the system by default; the toggle stores an app-specific override. */
    private void setupLanguage() {
        MaterialButtonToggleGroup group = findViewById(R.id.languageToggle);
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        String tag = current.isEmpty() || current.get(0) == null ? "" : current.get(0).getLanguage();
        group.check("es".equals(tag) ? R.id.langEs : "en".equals(tag) ? R.id.langEn : R.id.langAuto);
        group.addOnButtonCheckedListener((toggle, id, checked) -> {
            if (!checked) return;
            String language = id == R.id.langEs ? "es" : id == R.id.langEn ? "en" : "";
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
        });
    }

    /** Chosen music folders; none chosen means the whole device is read. */
    private void renderFolders() {
        LinearLayout container = findViewById(R.id.layoutMusicFolders);
        container.removeAllViews();
        java.util.List<String> folders = LibraryFolders.get(this);
        if (folders.isEmpty()) {
            container.addView(folderRow(Tr.t("Toda la música del dispositivo"), null, null));
            return;
        }
        for (String path : folders) {
            String name = new java.io.File(path).getName();
            container.addView(folderRow(name.isEmpty() ? path : name, path, () -> {
                LibraryFolders.remove(this, path);
                renderFolders();
            }));
        }
        TextView reset = new TextView(this);
        reset.setText(Tr.t("Restablecer"));
        reset.setTextSize(12);
        reset.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        reset.setTextColor(ContextCompat.getColor(this, R.color.accent));
        reset.setPadding(dp(6), dp(2), dp(6), dp(12));
        reset.setOnClickListener(v -> { LibraryFolders.reset(this); renderFolders(); });
        container.addView(reset);
    }

    private View folderRow(String title, String path, Runnable onRemove) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(10), dp(14));
        row.setBackgroundResource(R.drawable.bg_settings_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        if (path != null) {
            TextView detail = new TextView(this);
            detail.setText(path);
            detail.setTextSize(12);
            detail.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            texts.addView(detail);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onRemove != null) {
            TextView remove = new TextView(this);
            remove.setText("✕");
            remove.setTextSize(16);
            remove.setGravity(Gravity.CENTER);
            remove.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            remove.setContentDescription(Tr.t("Quitar carpeta"));
            remove.setBackgroundResource(R.drawable.bg_settings_icon_button);
            remove.setOnClickListener(v -> onRemove.run());
            row.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        return row;
    }

    private void renderDevices() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout container = findViewById(R.id.layoutConnectedDevices);
        String raw = cloudPrefs.getString(DEVICES_KEY, "{}");
        String currentId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        List<JSONObject> devices = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(raw);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (root.optJSONObject(key) != null) ids.add(key);
            }
            java.util.Collections.sort(ids);
            for (String key : ids) devices.add(root.optJSONObject(key));
        } catch (Exception ignored) {}
        if (devices.isEmpty()) {
            JSONObject current = new JSONObject();
            try {
                current.put("name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                current.put("platform", "Android");
                current.put("lastSeen", fallbackSeen);
            } catch (Exception ignored) {}
            devices.add(current); ids.add(currentId);
        }
        java.util.Set<String> retained = new java.util.HashSet<>();
        int index = 0;
        for (int pass = 0; pass < 2; pass++) {              // this device first, then the others
            for (int i = 0; i < devices.size(); i++) {
                boolean current = ids.get(i) != null && ids.get(i).equals(currentId);
                if (current != (pass == 0)) continue;
                JSONObject data = devices.get(i);
                if (data == null) continue;
                String id = ids.get(i);
                View row = deviceRows.get(id);
                if (row == null) {
                    row = addDeviceRow(container, id, data, currentId);
                    deviceRows.put(id, row);
                }
                retained.add(id);
                bindDeviceRow((LinearLayout) row, id, data, current);
                if (container.indexOfChild(row) != index) {
                    container.removeView(row);
                    container.addView(row, index);
                }
                index++;
            }
        }
        for (String id : new ArrayList<>(deviceRows.keySet())) if (!retained.contains(id)) {
            container.removeView(deviceRows.remove(id));
        }
    }

    private void bindDeviceRow(LinearLayout row, String id, JSONObject device, boolean current) {
        LinearLayout top = (LinearLayout) row.getChildAt(0);
        setTextIfChanged((TextView) top.getChildAt(0), device.optString("name", Tr.t("Dispositivo")));
        String platform = device.optString("platform", "");
        setTextIfChanged((TextView) top.getChildAt(1), platform.isEmpty() ? "?" : platform);
        long seen = device.optLong("lastSeen", 0);
        String date = seen > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(seen)) : Tr.t("Sin actividad reciente");
        setTextIfChanged((TextView) row.getChildAt(1), current ? Tr.t("Este dispositivo") + " · " + date : date);
        if (!current && row.getChildCount() > 2) row.getChildAt(2).setOnClickListener(
                v -> confirmDisconnect(id, device.optString("name", Tr.t("Dispositivo")), row));
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private View addDeviceRow(LinearLayout container, String id, JSONObject device, String currentId) {
        if (device == null) return null;
        boolean isCurrent = id != null && id.equals(currentId);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(12));
        row.setBackgroundResource(R.drawable.bg_settings_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView platform = new TextView(this);
        platform.setText(device.optString("platform", "").isEmpty() ? "?" : device.optString("platform", ""));
        platform.setTextSize(11);
        platform.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        platform.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        platform.setBackgroundResource(R.drawable.bg_platform_chip);
        platform.setPadding(dp(10), dp(4), dp(10), dp(4));
        TextView name = new TextView(this);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setText(device.optString("name", Tr.t("Dispositivo")));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginEnd(dp(8));
        top.addView(name, nameParams);
        top.addView(platform);
        row.addView(top);

        long seen = device.optLong("lastSeen", 0);
        String date = seen > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(seen)) : Tr.t("Sin actividad reciente");
        TextView detail = new TextView(this);
        detail.setTextColor(ContextCompat.getColor(this, isCurrent ? R.color.accent : R.color.text_secondary));
        detail.setTextSize(13);
        detail.setPadding(0, dp(5), 0, 0);
        detail.setText(isCurrent ? Tr.t("Este dispositivo") + " · " + date : date);
        row.addView(detail);

        if (!isCurrent && id != null) {
            TextView disconnect = new TextView(this);
            disconnect.setText(Tr.t("Desconectar"));
            disconnect.setTextSize(14);
            disconnect.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            disconnect.setTextColor(Color.rgb(255, 69, 58));
            disconnect.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            disconnect.setPadding(dp(12), dp(10), dp(4), dp(10));
            disconnect.setBackgroundResource(android.R.color.transparent);
            disconnect.setOnClickListener(v -> confirmDisconnect(id, device.optString("name", Tr.t("Dispositivo")), row));
            LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            action.topMargin = dp(4);
            row.addView(disconnect, action);
        } else {
            row.setPadding(dp(18), dp(16), dp(18), dp(16));
        }
        container.addView(row);
        return row;
    }

    private void confirmDisconnect(String id, String name, View row) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(Tr.t("¿Desconectar este dispositivo?"))
                .setMessage(Tr.f("Se cerrará la sesión guardada en %s. Tendrá que volver a iniciar sesión.", name))
                .setNegativeButton(Tr.t("Cancelar"), null)
                .setPositiveButton(Tr.t("Desconectar"), (dialog, which) -> {
                    row.animate().alpha(0.4f).setDuration(150).start();
                    DeviceSessions.revoke(this, id, success -> runOnUiThread(() -> {
                        if (isFinishing()) return;
                        if (success) {
                            row.animate().alpha(0f).translationX(dp(40)).setDuration(220)
                                    .withEndAction(this::renderDevices).start();
                        } else {
                            row.animate().alpha(1f).setDuration(150).start();
                            AppNotice.error(findViewById(R.id.btnSettingsLogout),
                                    Tr.t("No se pudo desconectar el dispositivo. Revisa tu conexión."));
                        }
                    }));
                }).show();
    }
}
