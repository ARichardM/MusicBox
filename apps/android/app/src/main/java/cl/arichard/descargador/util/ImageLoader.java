package cl.arichard.descargador.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ImageLoader {
    private static ImageLoader instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** One network/decode operation per URL; recycled rows can request the same cover at once. */
    private final Map<String, List<Consumer<Bitmap>>> inFlight = new HashMap<>();
    static final int MAX_IMAGE_PIXELS = 640;

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
    public void loadBitmap(String url, Consumer<Bitmap> callback) {
        Bitmap cached = getBitmap(url);
        if (cached != null || url == null || url.isEmpty()) {
            mainHandler.post(() -> callback.accept(cached));
            return;
        }
        synchronized (inFlight) {
            List<Consumer<Bitmap>> waiting = inFlight.get(url);
            if (waiting != null) {
                waiting.add(callback);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(callback);
            inFlight.put(url, waiting);
        }
        executor.execute(() -> {
            Bitmap image = downloadBitmap(url);
            if (image != null) memoryCache.put(url, image);
            List<Consumer<Bitmap>> waiting;
            synchronized (inFlight) { waiting = inFlight.remove(url); }
            if (waiting != null) mainHandler.post(() -> {
                for (Consumer<Bitmap> target : waiting) target.accept(image);
            });
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

        loadBitmap(url, bitmap -> {
            if (bitmap != null) {
                if (url.equals(imageView.getTag())) imageView.setImageBitmap(bitmap);
            } else {
                if (url.equals(imageView.getTag())) {
                    imageView.setTag(null);
                    imageView.setImageResource(cl.arichard.descargador.R.drawable.bg_cover_fallback);
                }
            }
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
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(16 * 1024, conn.getContentLength()));
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = is.read(chunk)) != -1) bytes.write(chunk, 0, read);
            return decodeSampled(bytes.toByteArray(), MAX_IMAGE_PIXELS);
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                if (is != null) is.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    /** Decode near the displayed size instead of uploading multi-megapixel thumbnails to the GPU. */
    static Bitmap decodeSampled(byte[] bytes, int maxPixels) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > maxPixels || bounds.outHeight / sample > maxPixels)
            sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
