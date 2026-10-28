package cl.arichard.descargador.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import cl.arichard.descargador.R;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Fotos de perfil por artista, sin bloquear la interfaz ni retener actividades. */
public final class ArtistAvatarLoader {
    interface Resolver { String photo(String artist) throws Exception; }
    interface Images { void load(String url, Consumer<Bitmap> callback); }
    private static ArtistAvatarLoader instance;
    private final SharedPreferences photos;
    private final Executor executor;
    private final Resolver resolver;
    private final Images images;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<Request>> pending = new HashMap<>();
    private final Map<String, Long> retryAfter = new HashMap<>();

    private static final class Request {
        final WeakReference<TextView> target;
        final String key;
        final int pixels;
        Request(TextView view, String key, int pixels) {
            target = new WeakReference<>(view); this.key = key; this.pixels = pixels;
        }
    }
    public static synchronized ArtistAvatarLoader getInstance(Context context) {
        Context app = context.getApplicationContext();
        if (instance == null) instance = new ArtistAvatarLoader(app,
                Executors.newFixedThreadPool(2), artist -> {
                    android.net.ConnectivityManager network = (android.net.ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                    android.net.Network active = network == null ? null : network.getActiveNetwork();
                    android.net.NetworkCapabilities capabilities = active == null ? null : network.getNetworkCapabilities(active);
                    if (capabilities == null || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "";
                    YtMusicApi.Artist found = YtMusicApi.findArtist(artist);
                    return found == null ? "" : found.photo;
                }, (url, callback) -> ImageLoader.getInstance().loadBitmap(url, callback));
        return instance;
    }
    ArtistAvatarLoader(Context context, Executor executor, Resolver resolver, Images images) {
        photos = context.getSharedPreferences("artist_avatar_urls", Context.MODE_PRIVATE);
        this.executor = executor; this.resolver = resolver; this.images = images;
    }
    /** Llamar en el hilo de interfaz. Conserva la flecha y el área táctil de la píldora. */
    public void load(String artist, TextView view, int pixels) {
        String key = artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT);
        if (key.equals(view.getTag(R.id.artistAvatarKey))) return;
        view.setTag(R.id.artistAvatarKey, key);
        Drawable fallback = ContextCompat.getDrawable(view.getContext(), R.drawable.ic_artist_avatar);
        fallback.setBounds(0, 0, pixels, pixels);
        Drawable[] current = view.getCompoundDrawablesRelative();
        view.setCompoundDrawablesRelative(fallback, current[1], current[2], current[3]);
        if (key.isEmpty() || retryAfter.getOrDefault(key, 0L) > SystemClock.elapsedRealtime()) return;
        Request request = new Request(view, key, pixels);
        if (pending.containsKey(key)) { pending.get(key).add(request); return; }
        List<Request> targets = new ArrayList<>(); targets.add(request); pending.put(key, targets);
        String known = photos.getString(key, "");
        if (!known.isEmpty()) { fetch(key, known); return; }
        executor.execute(() -> {
            String resolved;
            try { resolved = resolver.photo(artist); } catch (Exception ignored) { resolved = ""; }
            final String url = resolved == null ? "" : resolved;
            main.post(() -> {
                if (url.isEmpty()) { complete(key, null); return; }
                photos.edit().putString(key, url).apply();
                fetch(key, url);
            });
        });
    }
    private void fetch(String key, String url) {
        images.load(url, image -> main.post(() -> complete(key, image)));
    }
    private void complete(String key, Bitmap image) {
        List<Request> targets = pending.remove(key);
        if (image == null) retryAfter.put(key, SystemClock.elapsedRealtime() + 60000);
        if (targets == null || image == null) return;
        retryAfter.remove(key);
        for (Request request : targets) {
            TextView target = request.target.get();
            if (target == null || !key.equals(target.getTag(R.id.artistAvatarKey))) continue;
            RoundedBitmapDrawable avatar = RoundedBitmapDrawableFactory.create(target.getResources(), image);
            avatar.setCircular(true); avatar.setAntiAlias(true);
            avatar.setBounds(0, 0, request.pixels, request.pixels);
            Drawable[] current = target.getCompoundDrawablesRelative();
            target.setCompoundDrawablesRelative(avatar, current[1], current[2], current[3]);
        }
    }
}
