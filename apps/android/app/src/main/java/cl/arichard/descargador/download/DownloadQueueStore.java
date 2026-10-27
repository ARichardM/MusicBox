package cl.arichard.descargador.download;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class DownloadQueueStore {
    private final AtomicFile file;

    DownloadQueueStore(File directory) {
        file = new AtomicFile(new File(directory, "download-queue.json"));
    }

    synchronized List<DownloadJob> load() throws IOException {
        List<DownloadJob> jobs = new ArrayList<>();
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return jobs;
        try {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) throw new IOException("Unsupported download queue version");
            JSONArray entries = root.getJSONArray("jobs");
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.getJSONObject(index);
                DownloadOptions options = new DownloadOptions(entry.getBoolean("mp3"), entry.getString("quality"),
                        entry.getBoolean("playlist"), entry.getString("items"), entry.getString("cookies"),
                        entry.getString("destination"));
                DownloadJob job = new DownloadJob(entry.getString("id"), entry.getString("url"), options,
                        entry.getInt("position"), entry.getInt("batchSize"));
                job.title = entry.optString("title", "");
                jobs.add(job);
            }
            return jobs;
        } catch (JSONException exception) {
            throw new IOException("Invalid download queue", exception);
        }
    }

    synchronized void save(List<DownloadJob> jobs) throws IOException {
        JSONArray entries = new JSONArray();
        try {
            for (DownloadJob job : jobs) {
                if (job.status.isFinished() || job.cancelRequested || job.status == DownloadJob.Status.CANCELLING) continue;
                DownloadOptions options = job.options;
                entries.put(new JSONObject().put("id", job.id).put("url", job.url).put("title", job.title)
                        .put("position", job.position).put("batchSize", job.batchSize)
                        .put("mp3", options.mp3).put("quality", options.quality).put("playlist", options.playlist)
                        .put("items", options.items).put("cookies", options.cookies).put("destination", options.destination));
            }
            byte[] bytes = new JSONObject().put("version", 1).put("jobs", entries).toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(bytes);
                file.finishWrite(output);
            } catch (IOException exception) {
                file.failWrite(output);
                throw exception;
            }
        } catch (JSONException exception) {
            throw new IOException("Could not encode download queue", exception);
        }
    }
}
