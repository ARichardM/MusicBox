package cl.arichard.descargador.util;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Looks for a newer GitHub release and presents it in a MusicBox-styled sheet. */
public final class UpdateChecker {
    private static final String LATEST = "https://api.github.com/repos/ARichardM/MusicBox/releases/latest";
    private static final String APK = "MusicBox-Android.apk";
    private static final String SKIPPED = "skipped_update";

    private UpdateChecker() {}

    static final class Release {
        String version, apkUrl, pageUrl;
        final List<String> notes = new ArrayList<>();
    }

    public static void check(Activity activity) {
        String current = currentVersion(activity);
        // Las compilaciones de pruebas conviven con la app normal y no siguen sus publicaciones.
        if (current == null || current.contains("-")) return;
        SharedPreferences prefs = activity.getSharedPreferences("update", Activity.MODE_PRIVATE);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Release release = fetch();
                if (release == null || !isNewer(release.version, current)) return;
                if (release.version.equals(prefs.getString(SKIPPED, ""))) return;
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) show(activity, release, current, prefs);
                });
            } catch (Exception ignored) {
                // Sin conexión o límite de la API: se vuelve a intentar al abrir la app.
            }
        });
    }

    private static String currentVersion(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    private static Release fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "MusicBox-Android");
        if (connection.getResponseCode() != 200) return null;
        String body;
        try (InputStream in = connection.getInputStream(); Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            body = scanner.hasNext() ? scanner.next() : "";
        }
        JSONObject json = new JSONObject(body);
        Release release = new Release();
        release.version = json.optString("tag_name").replaceFirst("^[vV]", "");
        release.pageUrl = json.optString("html_url");
        JSONArray assets = json.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (APK.equals(asset.optString("name"))) release.apkUrl = asset.optString("browser_download_url");
        }
        if (release.version.isEmpty() || release.apkUrl == null) return null;
        for (String line : json.optString("body").split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("**Full Changelog")) continue;
            line = line.replaceFirst("^[-*+]\\s+", "")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                    .replaceAll("https?://\\S+", "")
                    .replace("**", "").replace("`", "").trim();
            if (!line.isEmpty() && release.notes.size() < 8) release.notes.add(line);
        }
        return release;
    }

    static boolean isNewer(String candidate, String current) {
        int[] a = parts(candidate), b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        String[] raw = version.split("-")[0].split("\\.");
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            try {
                out[i] = Integer.parseInt(raw[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static void show(Activity activity, Release release, String current, SharedPreferences prefs) {
        float d = activity.getResources().getDisplayMetrics().density;
        int cyan = Color.rgb(70, 221, 251);
        int muted = Color.rgb(142, 147, 163);
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * d);
        root.setPadding(pad, Math.round(28 * d), pad, pad);
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(28, 36, 48), Color.rgb(17, 21, 28)});
        float r = 28 * d;
        surface.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(surface);

        root.addView(label(activity, Tr.t("Nueva versión disponible"), 22, Color.WHITE, true));
        root.addView(label(activity, "MusicBox " + release.version + Tr.t("  ·  tienes la ") + current, 13, muted, false));
        TextView heading = label(activity, Tr.t("NOVEDADES"), 11, cyan, true);
        heading.setLetterSpacing(0.14f);
        ((LinearLayout.LayoutParams) heading.getLayoutParams()).topMargin = Math.round(22 * d);
        root.addView(heading);
        List<String> notes = release.notes.isEmpty()
                ? Collections.singletonList(Tr.t("Mejoras de rendimiento y correcciones.")) : release.notes;
        for (String note : notes) {
            TextView item = label(activity, "•  " + note, 14, Color.WHITE, false);
            item.setLineSpacing(0f, 1.1f);
            ((LinearLayout.LayoutParams) item.getLayoutParams()).topMargin = Math.round(8 * d);
            root.addView(item);
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = Math.round(26 * d);
        actions.setLayoutParams(actionParams);
        TextView later = button(activity, Tr.t("Más tarde"), muted, null);
        later.setOnClickListener(v -> dialog.dismiss());
        TextView skip = button(activity, Tr.t("Omitir"), muted, null);
        skip.setOnClickListener(v -> {
            prefs.edit().putString(SKIPPED, release.version).apply();
            dialog.dismiss();
        });
        View spacer = new View(activity);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(24 * d);
        pill.setColor(Color.rgb(10, 132, 255));
        TextView update = button(activity, Tr.t("Actualizar ahora"), Color.WHITE, pill);
        update.setOnClickListener(v -> {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)));
            dialog.dismiss();
        });
        actions.addView(later);
        actions.addView(skip);
        actions.addView(spacer);
        actions.addView(update);
        root.addView(actions);

        dialog.setContentView(root);
        dialog.show();
    }

    private static TextView label(Activity activity, String text, int sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private static TextView button(Activity activity, String text, int color, GradientDrawable background) {
        float d = activity.getResources().getDisplayMetrics().density;
        TextView view = label(activity, text, 14, color, true);
        view.setLayoutParams(new LinearLayout.LayoutParams(-2, Math.round(48 * d)));
        view.setGravity(Gravity.CENTER);
        view.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
        if (background != null) view.setBackground(background);
        return view;
    }
}
