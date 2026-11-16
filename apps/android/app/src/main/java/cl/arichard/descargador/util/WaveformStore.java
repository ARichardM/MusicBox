package cl.arichard.descargador.util;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Relieve de volumen de cada canción (sus "altos y bajos"), calculado decodificando el audio una sola vez y guardado en
 * disco. Para canciones en streaming se baja el mismo audio en segundo plano; si no se puede, la barra queda lisa.
 */
public final class WaveformStore {
    public static final int COARSE = 120, FINE = 128, FINE_SECONDS = 16;
    private static final int VERSION = 3;

    /** coarse: toda la canción; head/tail: los primeros y últimos FINE_SECONDS con más detalle (para la mezcla). */
    public static final class Envelope {
        public final float[] coarse, head, tail;
        public final int durationMs;
        /** Primer y último instante en que de verdad suena algo (la pista puede traer silencio al inicio o al final). */
        public final int audibleStartMs, audibleEndMs;
        Envelope(float[] coarse, float[] head, float[] tail, int durationMs, int audibleStartMs, int audibleEndMs) {
            this.coarse = coarse; this.head = head; this.tail = tail; this.durationMs = durationMs;
            this.audibleStartMs = audibleStartMs; this.audibleEndMs = audibleEndMs;
        }
    }

    public interface Callback { void onReady(Envelope envelope); }

    private static WaveformStore instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "waveform");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Map<String, Envelope> memory = new LinkedHashMap<String, Envelope>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Envelope> eldest) { return size() > 48; }
    };
    private final Map<String, List<Callback>> waiting = new HashMap<>();

    private WaveformStore(Context context) { this.context = context.getApplicationContext(); }

    public static synchronized WaveformStore get(Context context) {
        if (instance == null) instance = new WaveformStore(context);
        return instance;
    }

    private static String key(Song song) {
        if (song == null) return "";
        if (song.isOnline()) return "yt-" + song.getVideoId();
        File file = new File(song.getPath());
        return "f-" + Integer.toHexString((song.getPath() + file.length() + file.lastModified()).hashCode());
    }

    public synchronized Envelope peek(Song song) { return memory.get(key(song)); }

    /** Entrega el relieve en el hilo principal en cuanto esté (al instante si ya se conoce). */
    public void request(Song song, Callback callback) {
        if (song == null) return;
        String key = key(song);
        Envelope known;
        synchronized (this) {
            known = memory.get(key);
            if (known != null && known.coarse == null && waiting.get(key) != null && callback != null) waiting.get(key).add(callback);
            if (known == null) {
                List<Callback> list = waiting.get(key);
                if (list != null) { if (callback != null) list.add(callback); return; }
                list = new ArrayList<>();
                if (callback != null) list.add(callback);
                waiting.put(key, list);
            }
        }
        if (known != null) { if (callback != null) callback.onReady(known); return; }
        worker.execute(() -> compute(song, key, 0));
    }

    /** [last] false = entrega parcial (sin el relieve completo): quien espera sigue en la lista para recibir el definitivo. */
    private void deliver(String key, Envelope envelope, boolean last) {
        List<Callback> list;
        synchronized (this) {
            if (envelope != null) memory.put(key, envelope);
            list = last ? waiting.remove(key) : (waiting.get(key) == null ? null : new ArrayList<>(waiting.get(key)));
        }
        if (list == null || envelope == null) return;
        main.post(() -> { for (Callback callback : list) callback.onReady(envelope); });
    }
    private void deliver(String key, Envelope envelope) { deliver(key, envelope, true); }

    private void compute(Song song, String key, int attempt) {
        try {
            Envelope cached = readDisk(key);
            if (cached != null) { deliver(key, cached); return; }
            Envelope fresh = null;
            if (!song.isOnline()) {
                if (new File(song.getPath()).isFile()) fresh = decode(song.getPath(), null, song.getDuration(), partial -> deliver(key, partial, false));
            } else {
                PlayerManager.StreamSource source = PlayerManager.getInstance().streamSourceFor(song.getVideoId());
                if (source == null) {
                    // El enlace aún se está resolviendo: se reintenta sin bloquear a las demás canciones.
                    if (attempt < 20) { main.postDelayed(() -> worker.execute(() -> compute(song, key, attempt + 1)), 2500); return; }
                } else fresh = decode(source.url, source.headers, song.getDuration(), partial -> deliver(key, partial, false));
            }
            if (fresh != null) writeDisk(key, fresh);
            if (fresh != null) deliver(key, fresh, true);
            else synchronized (this) { waiting.remove(key); }
        } catch (Throwable ignored) {
            synchronized (this) { waiting.remove(key); }
        }
    }

    private File diskFile(String key) {
        File directory = new File(context.getFilesDir(), "waveforms");
        directory.mkdirs();
        return new File(directory, key + ".bin");
    }

    private Envelope readDisk(String key) {
        File file = diskFile(key);
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != VERSION) return null;
            int duration = in.readInt(), audibleStart = in.readInt(), audibleEnd = in.readInt();
            float[] coarse = new float[COARSE], head = new float[FINE], tail = new float[FINE];
            for (int i = 0; i < COARSE; i++) coarse[i] = in.readFloat();
            for (int i = 0; i < FINE; i++) head[i] = in.readFloat();
            for (int i = 0; i < FINE; i++) tail[i] = in.readFloat();
            return new Envelope(coarse, head, tail, duration, audibleStart, audibleEnd);
        } catch (Exception e) { return null; }
    }

    private void writeDisk(String key, Envelope envelope) {
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(diskFile(key))))) {
            out.writeInt(VERSION); out.writeInt(envelope.durationMs); out.writeInt(envelope.audibleStartMs); out.writeInt(envelope.audibleEndMs);
            for (float v : envelope.coarse) out.writeFloat(v);
            for (float v : envelope.head) out.writeFloat(v);
            for (float v : envelope.tail) out.writeFloat(v);
        } catch (Exception ignored) {}
    }

    /** Decodifica todo el audio y mide su volumen (RMS) por tramos. */
    public static Envelope analyse(String source, long durationMs) throws Exception { return decode(source, null, durationMs, null); }

    private interface Sink { void sample(double timeMs, double square); }

    /** Decodificador que salta a cada tramo que interesa en vez de recorrer toda la pista. */
    private static final class Windows {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec;
        int sampleRate = 44100, channels = 2;
        long durationMs;

        boolean open(String source, Map<String, String> headers, long fallbackMs) throws Exception {
            if (headers == null) extractor.setDataSource(source); else extractor.setDataSource(source, headers);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { extractor.selectTrack(i); format = candidate; break; }
            }
            if (format == null) return false;
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : fallbackMs * 1000L;
            if (durationUs <= 0) return false;
            durationMs = durationUs / 1000;
            // Una pista de horas (un directo, un mix) no merece analizarse para dibujar una barra.
            if (durationMs > 20 * 60 * 1000) return false;
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            return true;
        }

        /** Decodifica [startMs, endMs) y entrega el volumen de una de cada 8 tramas con su instante. */
        void window(long startMs, long endMs, Sink sink) {
            extractor.seekTo(Math.max(0, startMs) * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] samples = new short[16384];
            boolean inputDone = false;
            long limit = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < limit) {
                while (!inputDone) {
                    int index = codec.dequeueInputBuffer(0);
                    if (index < 0) break;
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    int size = extractor.readSampleData(buffer, 0);
                    long time = extractor.getSampleTime();
                    if (size < 0 || time / 1000 > endMs + 200) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                    else { codec.queueInputBuffer(index, 0, size, time, 0); extractor.advance(); }
                }
                int out = codec.dequeueOutputBuffer(info, 3000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat changed = codec.getOutputFormat();
                    if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    ByteBuffer pcm = codec.getOutputBuffer(out);
                    boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (pcm != null && info.size > 0) {
                        pcm.position(info.offset); pcm.limit(info.offset + info.size);
                        java.nio.ShortBuffer shorts = pcm.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                        int total = shorts.remaining(), frames = total / Math.max(1, channels);
                        if (samples.length < total) samples = new short[total];
                        shorts.get(samples, 0, total);
                        double base = info.presentationTimeUs / 1000.0;
                        for (int f = 0; f < frames; f += 8) {
                            double timeMs = base + f * 1000.0 / sampleRate;
                            if (timeMs < startMs || timeMs >= endMs) continue;
                            int mono = 0;
                            for (int c = 0; c < channels; c++) mono += samples[f * channels + c];
                            double value = mono / (double) channels / 32768.0;
                            sink.sample(timeMs, value * value);
                        }
                        if (base > endMs) last = true;
                    }
                    codec.releaseOutputBuffer(out, false);
                    if (last) return;
                }
            }
        }

        void close() {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            extractor.release();
        }
    }

    private static final int FINE_MS = FINE_SECONDS * 1000 / FINE;

    /** Mide el volumen en tres lugares: el relieve de toda la pista (tramos sueltos), su inicio y su final. */
    private static Envelope decode(String source, Map<String, String> headers, long fallbackDurationMs, Callback partialSink) throws Exception {
        Windows windows = new Windows();
        try {
            if (!windows.open(source, headers, fallbackDurationMs)) return null;
            int durationMs = (int) windows.durationMs;
            // Final: los últimos 45 s en tramos de 125 ms, para hallar dónde deja de sonar.
            int tailSpan = Math.min(durationMs, 45_000), tailStart = durationMs - tailSpan, tailBuckets = tailSpan / FINE_MS + 1;
            double[] tailSum = new double[tailBuckets];
            long[] tailCount = new long[tailBuckets];
            windows.window(tailStart, durationMs, (t, sq) -> {
                int i = Math.min(tailBuckets - 1, (int) ((t - tailStart) / FINE_MS));
                if (i >= 0) { tailSum[i] += sq; tailCount[i]++; }
            });
            float[] tailLevels = rms(tailSum, tailCount);
            // Inicio: los primeros 20 s.
            int headSpan = Math.min(durationMs, 20_000), headBuckets = headSpan / FINE_MS + 1;
            double[] headSum = new double[headBuckets];
            long[] headCount = new long[headBuckets];
            windows.window(0, headSpan, (t, sq) -> {
                int i = Math.min(headBuckets - 1, (int) (t / FINE_MS));
                if (i >= 0) { headSum[i] += sq; headCount[i]++; }
            });
            float[] headLevels = rms(headSum, headCount);
            if (partialSink != null) {
                // Primera entrega, en un par de segundos: ya se sabe dónde suena la pista (suficiente para mezclar bien).
                float[] none = null;
                float peakNow = 0.0001f;
                for (float v : tailLevels) peakNow = Math.max(peakNow, v);
                for (float v : headLevels) peakNow = Math.max(peakNow, v);
                boolean silentNow = peakNow < .003f;
                double thresholdNow = Math.max(.004, peakNow * .03);
                int lastNow = tailLevels.length - 1;
                while (lastNow > 0 && tailLevels[lastNow] < thresholdNow) lastNow--;
                int endNow = lastNow >= tailLevels.length - 2 ? durationMs : Math.min(durationMs, tailStart + (lastNow + 1) * FINE_MS);
                int firstNow = 0;
                while (firstNow < headLevels.length - 1 && headLevels[firstNow] < thresholdNow) firstNow++;
                float[] headNow = new float[FINE], tailNow = new float[FINE];
                for (int k = 0; k < FINE; k++) {
                    int hi = firstNow + k;
                    headNow[k] = hi < headLevels.length ? headLevels[hi] : 0f;
                    int ti = lastNow - (FINE - 1 - k);
                    tailNow[k] = ti >= 0 && ti < tailLevels.length ? tailLevels[ti] : 0f;
                }
                normalise(headNow, peakNow); normalise(tailNow, peakNow);
                partialSink.onReady(new Envelope(none, headNow, tailNow, durationMs,
                        silentNow ? 0 : (firstNow * FINE_MS < 300 ? 0 : firstNow * FINE_MS), silentNow ? durationMs : endNow));
            }
            // Toda la pista: un tramo corto en cada una de las COARSE posiciones.
            float[] coarse = new float[COARSE];
            for (int i = 0; i < COARSE; i++) {
                long centre = (long) ((i + .5) / COARSE * durationMs);
                double[] sum = new double[1];
                long[] count = new long[1];
                windows.window(Math.max(0, centre - 90), Math.min(durationMs, centre + 90), (t, sq) -> { sum[0] += sq; count[0]++; });
                coarse[i] = count[0] > 0 ? (float) Math.sqrt(sum[0] / count[0]) : (i > 0 ? coarse[i - 1] : 0f);
            }
            float peak = 0.0001f;
            for (float v : coarse) peak = Math.max(peak, v);
            for (float v : tailLevels) peak = Math.max(peak, v);
            for (float v : headLevels) peak = Math.max(peak, v);
            boolean silent = peak < .003f;
            double threshold = Math.max(.004, peak * .03);
            int lastSound = tailLevels.length - 1;
            while (lastSound > 0 && tailLevels[lastSound] < threshold) lastSound--;
            int audibleEnd = lastSound >= tailLevels.length - 2 ? durationMs : Math.min(durationMs, tailStart + (lastSound + 1) * FINE_MS);
            int firstSound = 0;
            while (firstSound < headLevels.length - 1 && headLevels[firstSound] < threshold) firstSound++;
            int audibleStart = firstSound * FINE_MS < 300 ? 0 : firstSound * FINE_MS;
            // head/tail: FINE tramos alineados al primer y último instante audibles.
            float[] head = new float[FINE], tail = new float[FINE];
            for (int k = 0; k < FINE; k++) {
                int hi = firstSound + k;
                head[k] = hi < headLevels.length ? headLevels[hi] : 0f;
                int ti = lastSound - (FINE - 1 - k);
                tail[k] = ti >= 0 && ti < tailLevels.length ? tailLevels[ti] : 0f;
            }
            normalise(coarse, peak); normalise(head, peak); normalise(tail, peak);
            // Un archivo del todo mudo (o una medición dudosa) no recorta nada: se mezcla como si no se supiera.
            if (silent) { audibleStart = 0; audibleEnd = durationMs; }
            return new Envelope(coarse, head, tail, durationMs, audibleStart, audibleEnd);
        } finally {
            windows.close();
        }
    }

    private static float[] rms(double[] sum, long[] count) {
        float[] out = new float[sum.length];
        float last = 0;
        for (int i = 0; i < sum.length; i++) {
            out[i] = count[i] > 0 ? (float) Math.sqrt(sum[i] / count[i]) : last;
            last = out[i];
        }
        return out;
    }

    /** Curva suave (raíz) para que los pasajes bajos también se vean, y un mínimo para que ninguna barra desaparezca. */
    private static void normalise(float[] values, float peak) {
        for (int i = 0; i < values.length; i++)
            values[i] = Math.max(.08f, Math.min(1f, (float) Math.pow(values[i] / peak, .62)));
    }
}
