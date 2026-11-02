package cl.arichard.descargador;

import android.app.Application;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import androidx.appcompat.app.AppCompatDelegate;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.player.PlayerManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DescargadorApp extends Application {
    public static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private volatile boolean ready;
    private volatile String initError;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private static final long UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    @Override public void onCreate() {
        super.onCreate();
        cl.arichard.descargador.util.ScreenRotation.install(this);
        // Immediately enforce saved theme before any activity or layout inflater runs
        SharedPreferences prefs = getSharedPreferences("musicbox_prefs", MODE_PRIVATE);
        boolean isDark = prefs.getBoolean("dark_mode", true);
        AppCompatDelegate.setDefaultNightMode(isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

        PlayerManager.getInstance().init(this);
        PlaylistManager.getInstance(this);

        IO.execute(() -> {
            try {
                YoutubeDL.getInstance().init(this);
                FFmpeg.getInstance().init(this);
                // El extractor incluido ya sirve: la app queda lista ahora y no espera a la actualización.
                // Antes la actualización diaria iba primero y, si GitHub tardaba o no respondía, ninguna
                // canción online podía cargar hasta cerrar la app.
                ready = true;
            } catch (Exception e) {
                initError = e.getLocalizedMessage();
            } finally {
                readyLatch.countDown();
            }
            if (ready) {
                warmUpExtractor();
                updateExtractorIfDue(prefs);
            }
        });
    }

    /**
     * La primera canción online pagaba el arranque en frío de Python y yt-dlp (varios segundos). Una ejecución
     * corta al abrir la app deja ese arranque hecho antes de que el usuario toque reproducir.
     */
    private void warmUpExtractor() {
        try {
            com.yausername.youtubedl_android.YoutubeDLRequest request =
                    new com.yausername.youtubedl_android.YoutubeDLRequest(java.util.Collections.emptyList());
            request.addOption("--version");
            YoutubeDL.getInstance().execute(request);
        } catch (Exception ignored) {
            // Solo es para ganar tiempo; si falla, la primera canción arranca como siempre.
        }
    }

    /** Actualiza yt-dlp una vez al día, en segundo plano y sin bloquear la reproducción. */
    private void updateExtractorIfDue(SharedPreferences prefs) {
        long lastUpdate = prefs.getLong("ytdlp_last_update", 0L);
        if (!hasNetwork() || System.currentTimeMillis() - lastUpdate <= UPDATE_INTERVAL_MS) return;
        try {
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
            prefs.edit().putLong("ytdlp_last_update", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
            // El extractor incluido sigue disponible si la actualización no responde.
        }
    }

    public boolean isReady() { return ready; }
    public String getInitError() { return initError; }
    public boolean awaitReady(long timeoutSeconds) {
        try { return readyLatch.await(timeoutSeconds, TimeUnit.SECONDS) && ready; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    public boolean hasNetwork() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        android.net.Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
