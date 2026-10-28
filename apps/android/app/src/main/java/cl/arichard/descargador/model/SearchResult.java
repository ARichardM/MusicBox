package cl.arichard.descargador.model;

import java.io.Serializable;

public class SearchResult implements Serializable {
    private final String id;
    private final String title;
    private final String channel;
    private final long durationSeconds;
    private final String thumbnailUrl;
    private final String url;

    public SearchResult(String id, String title, String channel, long durationSeconds, String thumbnailUrl, String url) {
        this.id = id != null ? id : "";
        this.title = title != null && !title.trim().isEmpty() ? title.trim() : cl.arichard.descargador.util.Tr.t("Sin título");
        this.channel = channel != null && !channel.trim().isEmpty() ? channel.trim() : "YouTube";
        this.durationSeconds = durationSeconds;
        this.thumbnailUrl = thumbnailUrl != null && !thumbnailUrl.isEmpty() ? thumbnailUrl : ("https://i.ytimg.com/vi/" + this.id + "/mqdefault.jpg");
        this.url = url != null && !url.isEmpty() ? url : ("https://www.youtube.com/watch?v=" + this.id);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getChannel() { return channel; }
    public long getDurationSeconds() { return durationSeconds; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getUrl() { return url; }

    public String getFormattedDuration() {
        if (durationSeconds <= 0) return "--:--";
        long mins = durationSeconds / 60;
        long secs = durationSeconds % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", mins, secs);
    }
}
