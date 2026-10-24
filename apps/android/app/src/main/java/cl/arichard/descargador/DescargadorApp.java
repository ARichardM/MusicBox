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
                long lastUpdate = prefs.getLong("ytdlp_last_update", 0L);
                if (hasNetwork() && System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL_MS) {
                    try {
                        YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
                        prefs.edit().putLong("ytdlp_last_update", System.currentTimeMillis()).apply();
                    } catch (Exception ignored) {
                        // El extractor incluido sigue disponible si la actualización no responde.
                    }
                }
                ready = true;
            } catch (Exception e) {
                initError = e.getLocalizedMessage();
            } finally {
                readyLatch.countDown();
            }
        });
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
