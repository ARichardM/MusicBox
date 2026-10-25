package cl.arichard.descargador.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import cl.arichard.descargador.model.Song;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MusicScanner {

    public static List<Song> scanAllMusic(Context context) {
        List<Song> songs = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        // 1. Scan App external Music directory (always writable and accessible)
        try {
            File appMusicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            scanDirectory(appMusicDir, songs, seenPaths);
        } catch (Exception ignored) {}

        java.util.List<String> chosenFolders = LibraryFolders.get(context);

        // 2. Scan standard MediaStore Audio. This covers public Downloads/Music
        // without requiring unrestricted filesystem access.
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED
            };
            try (Cursor cursor = resolver.query(uri, projection, selection, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                    int durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                    int dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);

                    while (cursor.moveToNext()) {
                        String path = dataCol >= 0 ? cursor.getString(dataCol) : null;
                        if (path == null || seenPaths.contains(path)) continue;
                        if (!LibraryFolders.accepts(chosenFolders, path)) continue;
                        File file = new File(path);
                        if (!file.exists() || !isAudioFile(file.getName())) continue;

                        long id = idCol >= 0 ? cursor.getLong(idCol) : System.currentTimeMillis();
                        String title = titleCol >= 0 ? cursor.getString(titleCol) : file.getName();
                        String artist = artistCol >= 0 ? cursor.getString(artistCol) : cl.arichard.descargador.util.Tr.t("Artista desconocido");
                        String album = albumCol >= 0 ? cursor.getString(albumCol) : "";
                        long duration = durCol >= 0 ? cursor.getLong(durCol) : 0;
                        long dateAdded = dateCol >= 0 ? cursor.getLong(dateCol) : file.lastModified();
                        String folder = file.getParentFile() != null ? file.getParentFile().getName() : "Música";

                        seenPaths.add(path);
                        songs.add(new Song(id, cleanTitle(title, file.getName()), artist, album, duration, path, folder, dateAdded));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return songs;
    }

    private static void scanDirectory(File dir, List<Song> songs, Set<String> seenPaths) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, songs, seenPaths);
            } else if (file.isFile() && isAudioFile(file.getName())) {
                String path = file.getAbsolutePath();
                if (seenPaths.contains(path)) continue;
                seenPaths.add(path);

                long duration = 0;
                String title = file.getName();
                String artist = cl.arichard.descargador.util.Tr.t("Artista desconocido");
                String album = "";

                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(path);
                    String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String al = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (t != null && !t.trim().isEmpty()) title = t.trim();
                    if (a != null && !a.trim().isEmpty()) artist = a.trim();
                    if (al != null && !al.trim().isEmpty()) album = al.trim();
                    if (d != null) duration = Long.parseLong(d);
                    mmr.release();
                } catch (Exception ignored) {}

                String folder = dir.getName();
                songs.add(new Song(System.currentTimeMillis(), cleanTitle(title, file.getName()), artist, album, duration, path, folder, file.lastModified()));
            }
        }
    }

    private static boolean isAudioFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".ogg");
    }

    private static String cleanTitle(String title, String filename) {
        if (title != null && !title.trim().isEmpty() && !title.endsWith(".mp3")) {
            return title.trim();
        }
        if (filename == null) return "Canción";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) filename = filename.substring(0, dot);
        return filename.replace('_', ' ').trim();
    }

    public static Map<String, List<Song>> groupByFolder(List<Song> songs) {
        Map<String, List<Song>> map = new LinkedHashMap<>();
        for (Song song : songs) {
            String folder = song.getFolder();
            if (!map.containsKey(folder)) {
                map.put(folder, new ArrayList<>());
            }
            map.get(folder).add(song);
        }
        return map;
    }
}
