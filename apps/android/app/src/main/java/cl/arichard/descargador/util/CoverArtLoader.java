package cl.arichard.descargador.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import cl.arichard.descargador.R;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CoverArtLoader {
    private static CoverArtLoader instance;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CoverArtLoader() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(maxMemory / 8) {
            @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getByteCount() / 1024); }
        };
    }

    public static synchronized CoverArtLoader getInstance() {
        if (instance == null) instance = new CoverArtLoader();
        return instance;
    }

    public Bitmap getBitmap(String path) {
        if (path == null) return null;
        Bitmap cached = cache.get(path);
        if (cached != null) return cached;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return ImageLoader.getInstance().getBitmap(path);
        }
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            byte[] data;
            try { mmr.setDataSource(path); data = mmr.getEmbeddedPicture(); }
            finally { mmr.release(); }
            if (data != null && data.length > 0) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bmp != null) cache.put(path, bmp);
                return bmp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void loadSongCover(String songPath, ImageView imageView) {
        if (imageView == null) return;
        if (songPath != null && songPath.equals(imageView.getTag())) return;
        imageView.clearAnimation();
        if (songPath == null || songPath.isEmpty()) {
            imageView.setTag(songPath); imageView.setImageResource(R.drawable.bg_cover_fallback); return;
        }

        // If online YouTube thumbnail url, delegate to ImageLoader
        if (songPath.startsWith("http://") || songPath.startsWith("https://")) {
            ImageLoader.getInstance().load(songPath, imageView);
            return;
        }

        imageView.setTag(songPath);

        Bitmap cached = cache.get(songPath);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageDrawable(new ShimmerDrawable(imageView.getContext()));

        executor.execute(() -> {
            Bitmap bmp = null;
            try {
                byte[] data;
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                try { mmr.setDataSource(songPath); data = mmr.getEmbeddedPicture(); }
                finally { mmr.release(); }
                if (data != null && data.length > 0) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 1;
                    while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 640) options.inSampleSize *= 2;
                    bmp = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                }
            } catch (Exception ignored) {}

            final Bitmap result = bmp;
            handler.post(() -> {
                if (songPath.equals(imageView.getTag())) {
                    if (result != null) {
                        cache.put(songPath, result);
                        imageView.setImageBitmap(result);
                    } else {
                        imageView.setImageResource(R.drawable.bg_cover_fallback);
                    }
                }
            });
        });
    }
}
