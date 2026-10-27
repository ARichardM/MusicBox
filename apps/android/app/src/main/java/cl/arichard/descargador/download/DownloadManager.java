package cl.arichard.descargador.download;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cl.arichard.descargador.DescargadorApp;
import cl.arichard.descargador.util.DownloadProgress;
import cl.arichard.descargador.util.StorageUtil;
import cl.arichard.descargador.util.Tr;

/**
 * La cola de descargas del Descargador. Vive en el proceso (no en una pantalla), así que sigue trabajando
 * si se cierra la pantalla; {@link DownloadService} la mantiene viva en segundo plano con su notificación.
 * Las pantallas solo observan: {@link #addObserver} avisa de cada cambio y {@link #jobs()} entrega una foto.
 */
public final class DownloadManager {
    private static final int MAX_LOG = 1200, KEEP_LOG = 1000, MAX_FINISHED = 100;
    private static DownloadManager instance;

    public static final class LogLine {
        public final long time;
        /** info, warn, error o ready. */
        public final String level, text;
        LogLine(String level, String text) { this.time = System.currentTimeMillis(); this.level = level; this.text = text; }
    }

    /** Un archivo terminado en esta sesión. */
    public static final class ReadyFile {
        public final String path, title;
        public final boolean mp3, existing;
        ReadyFile(String path, String title, boolean mp3, boolean existing) {
            this.path = path; this.title = title; this.mp3 = mp3; this.existing = existing;
        }
    }

    public static synchronized DownloadManager get(Context context) {
        if (instance == null) instance = new DownloadManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final List<DownloadJob> jobs = new ArrayList<>();
    private final List<LogLine> log = new ArrayList<>();
    private final List<ReadyFile> ready = new ArrayList<>();
    private final List<Runnable> observers = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean running;
    private int counter;
    private long lastNotify;

    private DownloadManager(Context context) { this.context = context; }

    public void addObserver(Runnable observer) { observers.add(observer); }
    public void removeObserver(Runnable observer) { observers.remove(observer); }

    private void changed(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastNotify < 150) return;
        lastNotify = now;
        for (Runnable observer : observers) observer.run();
    }

    /** Carpeta donde se guarda por defecto: la misma que lee la biblioteca. */
    public static File defaultDestination(Context context) { return StorageUtil.getDownloadDir(context); }

    /** El cookies.txt que el usuario cargó, o vacío si no hay. */
    public static String cookiesPath(Context context) {
        File file = new File(context.getFilesDir(), "cookies.txt");
        return file.isFile() ? file.getAbsolutePath() : "";
    }

    // --- Estado para las pantallas ---

    public synchronized List<DownloadJob> jobs() {
        List<DownloadJob> copies = new ArrayList<>();
        for (DownloadJob job : jobs) copies.add(job.copy());
        return copies;
    }

    public synchronized List<LogLine> log() { return new ArrayList<>(log); }
    public synchronized List<ReadyFile> readyFiles() { return new ArrayList<>(ready); }

    /** True si hay algo esperando o descargando. */
    public synchronized boolean isBusy() {
        for (DownloadJob job : jobs) if (!job.status.isFinished()) return true;
        return false;
    }

    /** Fracción 0..1 de toda la cola pendiente y activa, para la notificación. */
    public synchronized float overallProgress() {
        float sum = 0; int count = 0;
        for (DownloadJob job : jobs) if (!job.status.isFinished() || job.status == DownloadJob.Status.DONE) {
            sum += job.status == DownloadJob.Status.DONE ? 1f : job.progress; count++;
        }
        return count == 0 ? 0f : sum / count;
    }

    // --- Acciones ---

    /** Añade los enlaces a la cola y arranca. Devuelve cuántos eran nuevos (los repetidos y en curso se omiten). */
    public int enqueue(List<String> urls, DownloadOptions options) {
        int added = 0;
        synchronized (this) {
            int position = 0;
            List<String> fresh = new ArrayList<>();
            for (String url : urls) {
                boolean duplicate = fresh.contains(url);
                for (DownloadJob job : jobs) if (!job.status.isFinished() && job.url.equals(url)
                        && job.options.mp3 == options.mp3 && job.options.destination.equals(options.destination)) duplicate = true;
                if (!duplicate) fresh.add(url);
            }
            for (String url : fresh) {
                jobs.add(new DownloadJob("dl" + (++counter), url, options, ++position, fresh.size()));
                added++;
            }
            if (added > 0) addLog("info", Tr.f("Destino: %s · Formato: %s · %s", options.destination, options.format(), options.quality));
            trimFinished();
        }
        if (added > 0) { changed(true); DownloadService.start(context); pump(); }
        return added;
    }

    public void cancelAll() {
        List<String> processes = new ArrayList<>();
        synchronized (this) {
            for (DownloadJob job : jobs) {
                if (job.status == DownloadJob.Status.WAITING) job.status = DownloadJob.Status.CANCELLED;
                else if (job.status.isActive()) { job.cancelRequested = true; job.status = DownloadJob.Status.CANCELLING; processes.add("dl-" + job.id); }
            }
            addLog("warn", Tr.t("Descarga cancelada"));
        }
        changed(true);
        for (String process : processes) YoutubeDL.getInstance().destroyProcessById(process);
    }

    public void cancel(String jobId) {
        String process = null;
        synchronized (this) {
            for (DownloadJob job : jobs) if (job.id.equals(jobId)) {
                if (job.status == DownloadJob.Status.WAITING) job.status = DownloadJob.Status.CANCELLED;
                else if (job.status.isActive()) { job.cancelRequested = true; job.status = DownloadJob.Status.CANCELLING; process = "dl-" + job.id; }
            }
        }
        changed(true);
        if (process != null) YoutubeDL.getInstance().destroyProcessById(process);
    }

    /** Quita de la lista lo que ya terminó. */
    public synchronized void clearFinished() {
        jobs.removeIf(job -> job.status.isFinished());
        ready.clear();
        changed(true);
    }

    public synchronized void clearLog() { log.clear(); changed(true); }

    private void trimFinished() {
        int finished = 0;
        for (DownloadJob job : jobs) if (job.status.isFinished()) finished++;
        for (int i = 0; finished > MAX_FINISHED && i < jobs.size(); ) {
            if (jobs.get(i).status.isFinished()) { jobs.remove(i); finished--; } else i++;
        }
    }

    private synchronized void addLog(String level, String text) {
        log.add(new LogLine(level, text));
        if (log.size() > MAX_LOG) log.subList(0, log.size() - KEEP_LOG).clear();
    }

    // --- Trabajo ---

    private synchronized void pump() {
        if (running) return;
        running = true;
        worker.execute(this::runQueue);
    }

    private synchronized DownloadJob nextWaiting() {
        for (DownloadJob job : jobs) if (job.status == DownloadJob.Status.WAITING) return job;
        return null;
    }

    private void runQueue() {
        while (true) {
            DownloadJob job = nextWaiting();
            if (job == null) {
                synchronized (this) {
                    if (nextWaiting() == null) { running = false; break; }
                    continue;
                }
            }
            try { process(job); }
            catch (Throwable t) { fail(job, String.valueOf(t.getMessage())); }
        }
        int done = 0, failed = 0;
        synchronized (this) {
            for (DownloadJob job : jobs) {
                if (job.status == DownloadJob.Status.DONE) done++;
                else if (job.status == DownloadJob.Status.ERROR || job.status == DownloadJob.Status.PARTIAL) failed++;
            }
            addLog(failed > 0 ? "warn" : "info", failed > 0 ? Tr.t("El proceso terminó con errores · revisa el registro") : Tr.t("¡Descargas terminadas!"));
        }
        changed(true);
    }

    /** El motivo en el idioma de la app, con el detalle técnico de yt-dlp al final. */
    private static String describe(String error) {
        return Tr.t(ErrorReason.prefix(error)) + " " + Tr.t("Detalle:") + " " + (error == null ? "" : error);
    }

    private void fail(DownloadJob job, String message) {
        job.error = describe(message);
        job.status = DownloadJob.Status.ERROR;
        addLog("error", Tr.t(ErrorReason.prefix(message)) + " " + message);
        changed(true);
    }

    private void process(DownloadJob job) throws Exception {
        OutputParser parser = new OutputParser(job.options.mp3);
        DownloadProgress stages = new DownloadProgress();
        synchronized (this) {
            if (job.status != DownloadJob.Status.WAITING) return;
            job.status = DownloadJob.Status.PREPARING;
        }
        job.progress = stages.current();
        changed(true);

        DescargadorApp app = (DescargadorApp) context.getApplicationContext();
        if (!app.awaitReady(120)) {
            if (job.cancelRequested) { job.status = DownloadJob.Status.CANCELLED; changed(true); return; }
            fail(job, app.getInitError() == null ? "yt-dlp no está listo" : app.getInitError());
            return;
        }
        if (job.cancelRequested) { job.status = DownloadJob.Status.CANCELLED; changed(true); return; }
        File destination = new File(job.options.destination);
        if (!destination.isDirectory() && !destination.mkdirs()) { fail(job, "No se pudo crear la carpeta de destino"); return; }

        YoutubeDLRequest request = new YoutubeDLRequest(job.url);
        try {
            for (String[] pair : job.options.args(job.position, job.batchSize)) {
                if (pair.length == 1) request.addOption(pair[0]); else request.addOption(pair[0], pair[1]);
            }
        } catch (LinkParser.LinkError e) {
            fail(job, Tr.t(e.getMessage()));
            return;
        }

        String error = null;
        YoutubeDLResponse response = null;
        try {
            response = YoutubeDL.getInstance().execute(request, "dl-" + job.id, (percent, eta, line) -> {
                onLine(job, parser, stages, percent, eta, line);
                return kotlin.Unit.INSTANCE;
            });
        } catch (YoutubeDL.CanceledException e) {
            job.cancelRequested = true;
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        // Si se canceló o falló, el último archivo quedó a medias: se conserva el parcial para reanudar, pero no cuenta como listo.
        boolean interrupted = job.cancelRequested || error != null;
        for (OutputParser.Finished file : interrupted ? parser.drain() : parser.end()) saveFile(job, file);
        if (job.filesDone + job.filesExisting == 0 && !interrupted && response != null && response.getOut() != null) {
            // Respaldo: si el avance en vivo no entregó los destinos, se leen de la salida completa.
            OutputParser again = new OutputParser(job.options.mp3);
            for (String line : response.getOut().split("\\r?\\n")) again.feed(line);
            for (OutputParser.Finished file : again.end()) saveFile(job, file);
        }

        int files = job.filesDone + job.filesExisting;
        if (job.cancelRequested) {
            job.status = DownloadJob.Status.CANCELLED;
        } else if (error != null) {
            job.error = describe(error);
            job.status = files > 0 ? DownloadJob.Status.PARTIAL : DownloadJob.Status.ERROR;
            addLog("error", Tr.t(ErrorReason.prefix(error)) + " " + error);
        } else if (files == 0) {
            job.error = Tr.t("No se descargó ningún archivo.");
            job.status = DownloadJob.Status.ERROR;
            addLog("error", job.error);
        } else {
            job.progress = 1f;
            job.status = DownloadJob.Status.DONE;
        }
        changed(true);
    }

    private void onLine(DownloadJob job, OutputParser parser, DownloadProgress stages, float percent, long eta, String line) {
        if (job.cancelRequested) return;
        parser.feed(line);
        for (OutputParser.Finished file : parser.drain()) saveFile(job, file);
        float stage = stages.update(percent, line);
        job.itemIndex = parser.itemIndex(); job.itemCount = parser.itemCount();
        job.progress = job.itemCount > 1 ? Math.min(0.99f, ((job.itemIndex - 1) + stage) / job.itemCount) : stage;
        job.speed = parser.speed(); job.size = parser.size();
        job.etaSeconds = percent > 0 && eta >= 0 ? eta : -1;
        job.status = parser.processing() ? DownloadJob.Status.PROCESSING
                : percent > 0 ? DownloadJob.Status.DOWNLOADING : job.status;
        if (!parser.lastDestination().isEmpty()) job.title = FileNames.title(parser.lastDestination());
        changed(false);
    }

    private void saveFile(DownloadJob job, OutputParser.Finished finished) {
        File file = new File(finished.path);
        String title = FileNames.title(finished.path);
        String artist = "";
        if (file.isFile()) {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    String tagTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String tagArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    if (tagTitle != null && !tagTitle.trim().isEmpty()) title = tagTitle.trim();
                    if (tagArtist != null) artist = tagArtist.trim();
                } finally { retriever.release(); }
            } catch (Exception ignored) {}
            StorageUtil.scanFile(context, file);
        }
        try {
            DownloadHistoryDb.get(context).record(title, artist, finished.path, job.options.format(), job.options.quality,
                    job.url, finished.existing);
        } catch (Exception e) {
            addLog("warn", Tr.t("No se pudo guardar en el historial") + ": " + e.getMessage());
        }
        String id = FileNames.videoId(finished.path);
        if (id != null) context.getSharedPreferences("downloaded_videos", Context.MODE_PRIVATE).edit().putBoolean(id, true).apply();
        synchronized (this) {
            if (finished.existing) job.filesExisting++; else job.filesDone++;
            ready.add(new ReadyFile(finished.path, title, job.options.mp3, finished.existing));
            addLog("ready", (finished.existing ? Tr.t("Se conserva el archivo terminado: ") : Tr.t("Listo: ")) + FileNames.baseName(finished.path));
        }
        changed(true);
    }
}
