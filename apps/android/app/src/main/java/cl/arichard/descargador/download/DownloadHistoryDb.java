package cl.arichard.descargador.download;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Historial local de lo descargado (no se sincroniza con la nube). Misma tabla que historial.py de escritorio. */
public final class DownloadHistoryDb extends SQLiteOpenHelper {
    public static final int PAGE_SIZE = 100;
    private static DownloadHistoryDb instance;

    public static final class Entry {
        public final long id;
        public final String createdAt, title, artist, filename, format, quality, sourceUrl, state;
        Entry(long id, String createdAt, String title, String artist, String filename, String format, String quality,
              String sourceUrl, String state) {
            this.id = id; this.createdAt = createdAt; this.title = title; this.artist = artist; this.filename = filename;
            this.format = format; this.quality = quality; this.sourceUrl = sourceUrl; this.state = state;
        }
        public boolean existing() { return "existing".equals(state); }
    }

    public static synchronized DownloadHistoryDb get(Context context) {
        if (instance == null) instance = new DownloadHistoryDb(context.getApplicationContext());
        return instance;
    }

    /** Para pruebas: una base en memoria o con otro nombre. */
    public DownloadHistoryDb(Context context, String name) { super(context, name, null, 1); }

    private DownloadHistoryDb(Context context) { this(context, "downloads.db"); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, title TEXT, "
                + "artist TEXT, filename TEXT, format TEXT, quality TEXT, source_url TEXT, state TEXT NOT NULL, search_text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX downloads_created ON downloads(created_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    private static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    public synchronized void record(String title, String artist, String filename, String format, String quality,
                                    String sourceUrl, boolean existing) {
        ContentValues values = new ContentValues();
        values.put("created_at", now());
        values.put("title", title); values.put("artist", artist); values.put("filename", filename);
        values.put("format", format); values.put("quality", quality); values.put("source_url", sourceUrl);
        values.put("state", existing ? "existing" : "downloaded");
        values.put("search_text", ((title == null ? "" : title) + " " + (artist == null ? "" : artist) + " "
                + (filename == null ? "" : filename)).toLowerCase(Locale.ROOT));
        getWritableDatabase().insert("downloads", null, values);
    }

    public synchronized int count(String search) {
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        try (Cursor cursor = query.isEmpty()
                ? getReadableDatabase().rawQuery("SELECT COUNT(*) FROM downloads", null)
                : getReadableDatabase().rawQuery("SELECT COUNT(*) FROM downloads WHERE instr(search_text, ?) > 0", new String[]{query})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Los más recientes primero; {@code search} filtra por título, artista o ruta sin distinguir mayúsculas. */
    public synchronized List<Entry> page(String search, int offset, int limit) {
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String sql = "SELECT id, created_at, title, artist, filename, format, quality, source_url, state FROM downloads"
                + (query.isEmpty() ? "" : " WHERE instr(search_text, ?) > 0") + " ORDER BY id DESC LIMIT ? OFFSET ?";
        List<String> args = new ArrayList<>();
        if (!query.isEmpty()) args.add(query);
        args.add(String.valueOf(limit)); args.add(String.valueOf(offset));
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) entries.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8)));
        }
        return entries;
    }
}
