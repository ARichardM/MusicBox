package cl.arichard.descargador.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.Settings;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Sincroniza metadatos con transacciones y una base por dispositivo; los archivos permanecen locales. */
public final class CloudSyncManager {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaylistManager playlists;
    private final SearchHistoryManager history;
    private final ClickStats clicks;
    private volatile boolean needsUpload;
    private final Runnable uiChanged;
    private DocumentReference document;
    private ListenerRegistration registration, revocationWatch;
    private static volatile CloudSyncManager active;
    private boolean applying, stateLoaded, uploading;
    private JSONObject playlistBase;
    private final Runnable pendingUpload = this::uploadNow;

    public CloudSyncManager(Context context, Runnable uiChanged) {
        this.context = context.getApplicationContext();
        this.uiChanged = uiChanged;
        playlists = PlaylistManager.getInstance(context);
        history = SearchHistoryManager.getInstance(context);
        clicks = ClickStats.getInstance(context);
    }

    public void start() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        SharedPreferences owner = context.getSharedPreferences("musicbox_cloud", Context.MODE_PRIVATE);
        String previousUid = owner.getString("owner_uid", "");
        if (!previousUid.isEmpty() && !previousUid.equals(user.getUid())) {
            playlists.resetForAccount(); history.resetForAccount(); clicks.resetForAccount();
            owner.edit().remove("playlists_base").apply();
        }
        try {
            String stored = owner.getString("playlists_base", "");
            if (!stored.isEmpty()) playlistBase = new JSONObject(stored);
        } catch (Exception ignored) { playlistBase = null; }
        owner.edit().putString("owner_uid", user.getUid()).apply();
        document = FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                .collection("state").document("musicbox");
        active = this;
        revocationWatch = DeviceSessions.watch(context);
        playlists.setChangeListener(this::scheduleUpload);
        history.setChangeListener(this::scheduleUpload);
        clicks.setChangeListener(this::scheduleUpload);
        registration = document.addSnapshotListener((snapshot, error) -> {
            if (error != null || snapshot == null) return;
            if (!snapshot.exists()) { stateLoaded = true; playlistBase = new JSONObject(); scheduleUpload(); return; }
            String payload = snapshot.getString("payload");
            if (payload == null || payload.isEmpty()) return;
            try {
                JSONObject root = new JSONObject(payload);
                applying = true;
                JSONObject incoming = root.optJSONObject("playlists");
                if (incoming != null) {
                    JSONObject local = playlists.exportJson();
                    JSONObject merged = PlaylistSync.merge(playlistBase == null ? local : playlistBase, local, incoming);
                    if (!PlaylistSync.same(local, merged)) playlists.importJson(merged);
                    needsUpload |= !PlaylistSync.same(merged, incoming);
                    // Una escritura pendiente todavía puede fallar: la base persistida solo avanza tras confirmación.
                    if (!snapshot.getMetadata().hasPendingWrites()) {
                        playlistBase = new JSONObject(incoming.toString());
                        owner.edit().putString("playlists_base", playlistBase.toString()).apply();
                    }
                }
                if (root.has("history")) history.importJson(root.optJSONObject("history"));
                needsUpload |= clicks.merge(root.optJSONObject("clicks"));
                JSONObject preferences = root.optJSONObject("preferences");
                if (preferences != null && preferences.has("darkMode"))
                    context.getSharedPreferences("musicbox_prefs", Context.MODE_PRIVATE).edit()
                            .putBoolean("dark_mode", preferences.optBoolean("darkMode", true)).apply();
                owner.edit().putString("devices_json", root.optJSONObject("devices") == null ? "{}" : root.optJSONObject("devices").toString()).apply();
                applying = false;
                boolean first = !stateLoaded;
                stateLoaded = true;
                if (needsUpload || first) scheduleUpload();
                if (uiChanged != null) uiChanged.run();
            } catch (Exception errorParsing) {
                applying = false;
                android.util.Log.w("MusicBoxSync", "No se pudo leer el estado remoto; se conserva la copia local", errorParsing);
            }
        });
    }

    public static void requestUpload() {
        CloudSyncManager current = active;
        if (current != null) current.scheduleUpload();
    }

    public void scheduleUpload() {
        if (applying || document == null) return;
        needsUpload = true;
        handler.removeCallbacks(pendingUpload);
        handler.postDelayed(pendingUpload, 650);
    }

    private void uploadNow() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (document == null || user == null || !stateLoaded || uploading) return;
        uploading = true;
        needsUpload = false;
        final DocumentReference target = document;
        final String uid = user.getUid();
        try {
            final JSONObject base = playlistBase == null ? new JSONObject() : new JSONObject(playlistBase.toString());
            final JSONObject local = playlists.exportJson();
            final JSONObject localHistory = history.exportJson();
            final boolean dark = context.getSharedPreferences("musicbox_prefs", Context.MODE_PRIVATE).getBoolean("dark_mode", true);
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            final String deviceId = id == null ? "android" : id;
            FirebaseFirestore.getInstance().runTransaction(transaction -> {
                try {
                    FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
                    if (current == null || !uid.equals(current.getUid()))
                        throw new IllegalStateException("La sesión cambió durante la sincronización");
                    com.google.firebase.firestore.DocumentSnapshot snapshot = transaction.get(target);
                    String payload = snapshot.getString("payload");
                    JSONObject root = payload == null ? new JSONObject() : new JSONObject(payload);
                    JSONObject previous = root.optJSONObject("playlists");
                    JSONObject merged = PlaylistSync.merge(base, local, previous);
                    root.put("schema", 1).put("playlists", merged).put("history", localHistory);
                    JSONObject lists = merged.optJSONObject("playlists");
                    if (lists != null && lists.has("Favoritos")) root.put("favorites", lists.getJSONArray("Favoritos"));
                    clicks.merge(root.optJSONObject("clicks"));
                    root.put("clicks", clicks.exportJson());
                    JSONObject preferences = root.optJSONObject("preferences");
                    if (preferences == null) preferences = new JSONObject();
                    preferences.put("darkMode", dark); root.put("preferences", preferences);
                    JSONObject devices = root.optJSONObject("devices");
                    if (devices == null) devices = new JSONObject();
                    devices.put(deviceId, new JSONObject().put("name", Build.MANUFACTURER + " " + Build.MODEL)
                            .put("platform", "Android").put("lastSeen", System.currentTimeMillis()));
                    root.put("devices", devices);
                    PlaylistSync.appendAudit(root, previous, merged, deviceId, System.currentTimeMillis());
                    Map<String, Object> update = new HashMap<>(); update.put("payload", root.toString());
                    transaction.set(target, update, SetOptions.merge());
                    return true;
                } catch (Exception failure) {
                    throw new FirebaseFirestoreException("No se pudo sincronizar sin perder cambios",
                            FirebaseFirestoreException.Code.ABORTED, failure);
                }
            }).addOnSuccessListener(ignored -> {
                uploading = false;
                if (document == target && needsUpload) scheduleUpload();
            }).addOnFailureListener(error -> {
                uploading = false; needsUpload = true;
                if (document == target) handler.postDelayed(pendingUpload, 5000);
                android.util.Log.w("MusicBoxSync", "Se conservan los cambios locales para reintentar", error);
            });
        } catch (Exception error) {
            uploading = false; needsUpload = true;
            android.util.Log.w("MusicBoxSync", "No se pudo preparar la sincronización", error);
        }
    }

    public void stop() {
        handler.removeCallbacks(pendingUpload);
        playlists.setChangeListener(null); history.setChangeListener(null); clicks.setChangeListener(null);
        if (registration != null) registration.remove();
        if (revocationWatch != null) revocationWatch.remove();
        document = null;
        if (active == this) active = null;
    }
}
