package cl.arichard.descargador.util;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Busca una versión nueva en GitHub y la descarga e instala sin salir de la app. */
public final class UpdateChecker {
    private static final String LATEST = "https://api.github.com/repos/ARichardM/MusicBox/releases/latest";
    private static final String APK = "MusicBox-Android.apk";
    private static final String SKIPPED = "skipped_update";
    private static final int MAX_NOTES = 12;

    private UpdateChecker() {}

    public static final class Release {
        public String version, apkUrl, pageUrl;
        public final List<String> notes = new ArrayList<>();
    }

    public enum State { UP_TO_DATE, AVAILABLE, ERROR }

    public interface Callback {
        /** Siempre en el hilo de la interfaz. */
        void onResult(State state, Release release);
    }

    /** Versión instalada, tal como la ve el usuario. */
    public static String versionName(Activity activity) {
        String version = currentVersion(activity);
        return version == null ? "" : version;
    }

    /** Comprobación manual desde el perfil: ignora «omitir» y también sirve en las compilaciones de pruebas. */
    public static void checkNow(Activity activity, Callback callback) {
        String current = currentVersion(activity);
        Executors.newSingleThreadExecutor().execute(() -> {
            State state;
            Release release = null;
            try {
                release = fetch();
                state = release == null ? State.ERROR : current != null && isNewer(release.version, current) ? State.AVAILABLE : State.UP_TO_DATE;
            } catch (Exception e) {
                state = State.ERROR;
            }
            State finalState = state;
            Release finalRelease = release;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) callback.onResult(finalState, finalRelease);
            });
        });
    }

    /** Abre la hoja de actualización para una versión ya encontrada. */
    public static void showSheet(Activity activity, Release release) {
        String current = currentVersion(activity);
        if (current == null || release == null) return;
        show(activity, release, current, activity.getSharedPreferences("update", Activity.MODE_PRIVATE));
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
        release.notes.addAll(notesFor(json.optString("body")));
        return release;
    }

    /**
     * Novedades que corresponden a Android: se omiten las secciones que son solo de Windows
     * (por ejemplo «### Windows») y se conservan las generales o las que nombran a Android.
     */
    static List<String> notesFor(String body) {
        List<String> notes = new ArrayList<>();
        boolean include = true;
        for (String raw : String.valueOf(body).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#")) {
                String heading = line.replaceFirst("^#+\\s*", "").toLowerCase(java.util.Locale.ROOT);
                include = !(heading.contains("windows") && !heading.contains("android"));
                continue;
            }
            if (!include || line.isEmpty() || line.startsWith("**Full Changelog")) continue;
            line = line.replaceFirst("^[-*+]\\s+", "")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                    .replaceAll("https?://\\S+", "")
                    .replace("**", "").replace("`", "").trim();
            if (!line.isEmpty() && notes.size() < MAX_NOTES) notes.add(line);
        }
        return notes;
    }

    /**
     * Titular corto de una novedad. «Título. Descripción…» se muestra como «Título — primera frase de la descripción»,
     * porque un título solo («Mi música.») no dice qué cambió; si es largo se corta en una coma o en un espacio.
     */
    static String headline(String note) {
        String text = note.trim();
        int stop = text.indexOf(". ");
        if (stop > 0 && stop < 32) {
            String rest = text.substring(stop + 2).trim();
            int next = rest.indexOf(". ");
            if (next > 0) rest = rest.substring(0, next + 1);
            text = text.substring(0, stop) + " — " + rest;
        } else if (stop > 0) {
            text = text.substring(0, stop + 1);
        }
        final int limit = 120;
        if (text.length() <= limit) return text;
        int clause = Math.max(text.lastIndexOf(", ", limit + 6), text.lastIndexOf("; ", limit + 6));
        if (clause > 40) return text.substring(0, clause).replaceAll("[,;:\\s]+$", "") + ".";
        int cut = text.lastIndexOf(' ', limit - 2);
        return text.substring(0, cut > 40 ? cut : limit - 2).replaceAll("[,;:\\s]+$", "") + "…";
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

    // ---------------------------------------------------------------- interfaz

    /** Un ScrollView que nunca ocupa más de la altura indicada, para que los botones siempre se vean. */
    private static final class CappedScrollView extends ScrollView {
        private final int maxHeight;

        CappedScrollView(Activity activity, int maxHeight) {
            super(activity);
            this.maxHeight = maxHeight;
            setOverScrollMode(OVER_SCROLL_NEVER);
            setVerticalScrollBarEnabled(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
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

        TextView title = label(activity, Tr.t("¡Hay una versión nueva!"), 22, Color.WHITE, true);
        root.addView(title);
        root.addView(label(activity, "MusicBox " + release.version + Tr.t("  ·  tienes la ") + current, 13, muted, false));
        TextView heading = label(activity, Tr.t("LO NUEVO"), 11, cyan, true);
        heading.setLetterSpacing(0.14f);
        ((LinearLayout.LayoutParams) heading.getLayoutParams()).topMargin = Math.round(20 * d);
        root.addView(heading);

        List<String> notes = release.notes.isEmpty()
                ? Collections.singletonList(Tr.t("Mejoras de rendimiento y correcciones.")) : release.notes;
        List<String> brief = new ArrayList<>();
        for (String note : notes) if (brief.size() < 4) brief.add(headline(note));
        LinearLayout notesBox = new LinearLayout(activity);
        notesBox.setOrientation(LinearLayout.VERTICAL);
        boolean[] expanded = {false};
        Runnable fill = () -> {
            notesBox.removeAllViews();
            for (String note : expanded[0] ? notes : brief) {
                TextView item = label(activity, "•  " + note, expanded[0] ? 13 : 14, Color.WHITE, false);
                item.setLineSpacing(0f, 1.1f);
                ((LinearLayout.LayoutParams) item.getLayoutParams()).topMargin = Math.round(8 * d);
                notesBox.addView(item);
            }
        };
        fill.run();
        // Las novedades largas se desplazan dentro de su zona; los botones quedan siempre a la vista.
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        ScrollView scroll = new CappedScrollView(activity, Math.round(screen * 0.34f));
        scroll.addView(notesBox);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        boolean hasMore = notes.size() > brief.size() || !brief.equals(notes);
        TextView details = hasMore ? label(activity, Tr.t("Ver detalles"), 13, cyan, true) : null;
        if (details != null) {
            ((LinearLayout.LayoutParams) details.getLayoutParams()).topMargin = Math.round(10 * d);
            details.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                details.setText(expanded[0] ? Tr.t("Ver menos") : Tr.t("Ver detalles"));
                fill.run();
                scroll.scrollTo(0, 0);
            });
            root.addView(details);
        }

        // Progreso y estado de la descarga (oculto hasta que se pulsa Actualizar).
        LinearLayout progressBox = new LinearLayout(activity);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, -2);
        progressParams.topMargin = Math.round(18 * d);
        progressBox.setLayoutParams(progressParams);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.getProgressDrawable().setColorFilter(Color.rgb(10, 132, 255), android.graphics.PorterDuff.Mode.SRC_IN);
        progressBox.addView(bar, new LinearLayout.LayoutParams(-1, Math.round(8 * d)));
        TextView status = label(activity, "", 13, muted, false);
        ((LinearLayout.LayoutParams) status.getLayoutParams()).topMargin = Math.round(8 * d);
        progressBox.addView(status);
        root.addView(progressBox);

        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(26 * d);
        pill.setColor(Color.rgb(10, 132, 255));
        TextView update = button(activity, Tr.t("Actualizar ahora"), Color.WHITE, pill);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-1, Math.round(52 * d));
        updateParams.topMargin = Math.round(22 * d);
        update.setLayoutParams(updateParams);
        root.addView(update);

        LinearLayout secondary = new LinearLayout(activity);
        secondary.setGravity(Gravity.CENTER);
        TextView later = button(activity, Tr.t("Más tarde"), muted, null);
        TextView skip = button(activity, Tr.t("Omitir esta versión"), muted, null);
        secondary.addView(later);
        secondary.addView(skip);
        root.addView(secondary, new LinearLayout.LayoutParams(-1, -2));

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean busy = new AtomicBoolean();
        File apk = new File(new File(activity.getCacheDir(), "updates"), "MusicBox-" + release.version + ".apk");

        later.setOnClickListener(v -> dialog.dismiss());
        skip.setOnClickListener(v -> {
            prefs.edit().putString(SKIPPED, release.version).apply();
            dialog.dismiss();
        });
        Runnable[] start = new Runnable[1];
        start[0] = () -> {
            if (busy.get()) {
                cancelled.set(true);
                return;
            }
            busy.set(true);
            cancelled.set(false);
            progressBox.setVisibility(View.VISIBLE);
            secondary.setVisibility(View.GONE);
            if (details != null) details.setVisibility(View.GONE);
            update.setText(Tr.t("Cancelar"));
            update.setBackground(null);
            update.setTextColor(muted);
            bar.setIndeterminate(true);
            status.setTextColor(muted);
            status.setText(Tr.t("Conectando…"));
            Executors.newSingleThreadExecutor().execute(() -> {
                String error = null;
                try {
                    if (!(apk.isFile() && isValidUpdate(activity, apk, release.version))) {
                        download(release.apkUrl, apk, cancelled, (fraction, megabytes) -> activity.runOnUiThread(() -> {
                            bar.setIndeterminate(false);
                            bar.setProgress(Math.round(fraction * 1000));
                            status.setText(Tr.f("Descargando… %d %%  ·  %.0f MB", Math.round(fraction * 100), megabytes));
                        }));
                        activity.runOnUiThread(() -> { bar.setIndeterminate(true); status.setText(Tr.t("Verificando…")); });
                        if (!isValidUpdate(activity, apk, release.version)) throw new IllegalStateException("invalid");
                    }
                } catch (CancelledException e) {
                    error = "";
                } catch (IllegalStateException e) {
                    apk.delete();
                    error = Tr.t("La actualización descargada no es válida. Inténtalo de nuevo.");
                } catch (Exception e) {
                    error = Tr.t("No se pudo descargar la actualización. Revisa tu conexión.");
                }
                String finalError = error;
                activity.runOnUiThread(() -> {
                    busy.set(false);
                    bar.setIndeterminate(false);
                    if (finalError == null) {
                        install(activity, apk, status, update, muted, start[0], pill);
                    } else {
                        bar.setProgress(0);
                        status.setTextColor(finalError.isEmpty() ? muted : Color.rgb(255, 99, 99));
                        status.setText(finalError.isEmpty() ? Tr.t("Descarga cancelada.") : finalError);
                        secondary.setVisibility(View.VISIBLE);
                        update.setText(Tr.t("Reintentar"));
                        update.setBackground(pill);
                        update.setTextColor(Color.WHITE);
                    }
                });
            });
        };
        update.setOnClickListener(v -> start[0].run());

        dialog.setOnDismissListener(v -> cancelled.set(true));
        dialog.setContentView(root);
        // El contenedor propio del diálogo no debe verse detrás de las esquinas redondeadas.
        View container = root.getParent() instanceof View ? (View) root.getParent() : null;
        if (container != null) container.setBackgroundColor(Color.TRANSPARENT);
        dialog.setOnShowListener(v -> {
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
        });
        dialog.show();
    }

    // ---------------------------------------------------------------- descarga e instalación

    private interface Progress {
        void update(float fraction, float megabytes);
    }

    private static final class CancelledException extends Exception {}

    private static void download(String url, File target, AtomicBoolean cancelled, Progress progress) throws Exception {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cache");
        File partial = new File(target.getPath() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "MusicBox-Android");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            long total = connection.getContentLengthLong();
            long read = 0;
            long lastReport = 0;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    if (cancelled.get()) throw new CancelledException();
                    out.write(buffer, 0, length);
                    read += length;
                    long now = System.nanoTime();
                    if (total > 0 && now - lastReport > 120_000_000L) {
                        lastReport = now;
                        progress.update(Math.min(1f, read / (float) total), read / 1048576f);
                    }
                }
            }
            if (total > 0 && read != total) throw new java.io.IOException("incompleta");
            if (target.exists() && !target.delete()) throw new java.io.IOException("reemplazo");
            if (!partial.renameTo(target)) throw new java.io.IOException("renombrar");
            progress.update(1f, read / 1048576f);
        } finally {
            connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    /** El APK descargado debe ser esta misma app, de una versión más nueva y firmada con la misma clave. */
    static boolean isValidUpdate(Activity activity, File apk, String version) {
        try {
            PackageManager manager = activity.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = manager.getPackageArchiveInfo(apk.getPath(), flags);
            if (archive == null || !activity.getPackageName().equals(archive.packageName)) return false;
            if (archive.versionName == null || !archive.versionName.equals(version)) return false;
            PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), flags);
            return Arrays.equals(signatures(archive), signatures(installed));
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.signingInfo == null ? new Signature[0] : info.signingInfo.getApkContentsSigners();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static void install(Activity activity, File apk, TextView status, TextView update, int muted,
                                Runnable retry, GradientDrawable pill) {
        update.setBackground(pill);
        update.setTextColor(Color.WHITE);
        update.setText(Tr.t("Instalar"));
        status.setTextColor(muted);
        // Android pide un permiso por app para instalar paquetes; se abre su pantalla y se vuelve a pulsar Instalar.
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            status.setText(Tr.t("Permite instalar desde MusicBox, vuelve y pulsa «Instalar»."));
            update.setOnClickListener(v -> {
                if (activity.getPackageManager().canRequestPackageInstalls()) openInstaller(activity, apk, status);
                else activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            });
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        update.setOnClickListener(v -> openInstaller(activity, apk, status));
        openInstaller(activity, apk, status);
    }

    private static void openInstaller(Activity activity, File apk, TextView status) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
            status.setText(Tr.t("Confirma la instalación y MusicBox se actualizará."));
        } catch (Exception e) {
            status.setTextColor(Color.rgb(255, 99, 99));
            status.setText(Tr.t("No se pudo abrir el instalador."));
        }
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
