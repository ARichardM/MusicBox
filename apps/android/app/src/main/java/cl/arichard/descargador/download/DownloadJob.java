package cl.arichard.descargador.download;

/** Una descarga de la cola: un enlace (o una playlist completa) y su avance. */
public final class DownloadJob {
    public enum Status {
        WAITING, PREPARING, DOWNLOADING, PROCESSING, CANCELLING, CANCELLED, ERROR, PARTIAL, DONE;

        public boolean isActive() { return this == PREPARING || this == DOWNLOADING || this == PROCESSING || this == CANCELLING; }
        public boolean isFinished() { return this == CANCELLED || this == ERROR || this == PARTIAL || this == DONE; }
    }

    public final String id;
    public final String url;
    public final DownloadOptions options;
    /** Posición dentro del lote y tamaño del lote, para numerar los nombres. */
    public final int position, batchSize;
    public volatile Status status = Status.WAITING;
    public volatile String title = "";
    public volatile float progress;
    public volatile String speed = "", size = "";
    public volatile long etaSeconds = -1;
    public volatile int itemIndex = 1, itemCount = 1;
    public volatile int filesDone, filesExisting;
    public volatile String error = "";
    volatile boolean cancelRequested;

    public DownloadJob(String id, String url, DownloadOptions options, int position, int batchSize) {
        this.id = id; this.url = url; this.options = options; this.position = position; this.batchSize = batchSize;
    }

    /** Copia congelada para que la interfaz la lea sin que cambie a mitad de pintar. */
    public DownloadJob copy() {
        DownloadJob copy = new DownloadJob(id, url, options, position, batchSize);
        copy.status = status; copy.title = title; copy.progress = progress; copy.speed = speed; copy.size = size;
        copy.etaSeconds = etaSeconds; copy.itemIndex = itemIndex; copy.itemCount = itemCount;
        copy.filesDone = filesDone; copy.filesExisting = filesExisting; copy.error = error;
        return copy;
    }
}
