package cl.arichard.descargador.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.animation.AlphaAnimation;
import android.widget.ImageView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageLoader {
    private static ImageLoader instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageLoader() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(1024 * 4, maxMemory / 8);
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    public static synchronized ImageLoader getInstance() {
        if (instance == null) {
            instance = new ImageLoader();
        }
        return instance;
    }

    public Bitmap getBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        return memoryCache.get(url);
    }

    /** La misma caché de imágenes para avatares usados como drawable de texto. */
    public void loadBitmap(String url, java.util.function.Consumer<Bitmap> callback) {
        Bitmap cached = getBitmap(url);
        if (cached != null || url == null || url.isEmpty()) {
            mainHandler.post(() -> callback.accept(cached));
            return;
        }
        executor.execute(() -> {
            Bitmap image = downloadBitmap(url);
            if (image != null) memoryCache.put(url, image);
            mainHandler.post(() -> callback.accept(image));
        });
    }

    public void load(String url, ImageView imageView) {
        if (imageView == null) return;
        if (url == null || url.isEmpty()) {
            imageView.setTag(null);
            imageView.clearAnimation();
            imageView.setImageResource(cl.arichard.descargador.R.drawable.bg_cover_fallback);
            return;
        }
        if (url.equals(imageView.getTag())) return;

        imageView.clearAnimation();
        imageView.setTag(url);
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageDrawable(new ShimmerDrawable(imageView.getContext()));

        executor.execute(() -> {
            Bitmap bitmap = downloadBitmap(url);
            if (bitmap != null) {
                memoryCache.put(url, bitmap);
                mainHandler.post(() -> {
                    if (url.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                        AlphaAnimation fadeIn = new AlphaAnimation(0.2f, 1.0f);
                        fadeIn.setDuration(220);
                        imageView.startAnimation(fadeIn);
                    }
                });
            } else mainHandler.post(() -> {
                if (url.equals(imageView.getTag())) {
                    imageView.setTag(null);
                    imageView.setImageResource(cl.arichard.descargador.R.drawable.bg_cover_fallback);
                }
            });
        });
    }

    private Bitmap downloadBitmap(String urlStr) {
        InputStream is = null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
            conn.connect();
            is = conn.getInputStream();
            return BitmapFactory.decodeStream(is);
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                if (is != null) is.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
