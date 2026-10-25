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
import com.google.firebase.firestore.ListenerRegistration;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Synchronizes lightweight MusicBox state. Media files always remain local. */
public final class CloudSyncManager {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaylistManager playlists;
    private final SearchHistoryManager history;
    private final ClickStats clicks;
    private boolean needsUpload;
    private final Runnable uiChanged;
    private DocumentReference document;
    private ListenerRegistration registration;
    private ListenerRegistration revocationWatch;
    private static volatile CloudSyncManager active;
    private JSONObject lastRoot = new JSONObject();
    private boolean applying;
    private boolean deviceRegistered;
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
        }
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
            if (!snapshot.exists()) { scheduleUpload(); return; }
            String payload = snapshot.getString("payload");
            if (payload == null || payload.isEmpty()) return;
            try {
                JSONObject root = new JSONObject(payload); lastRoot = root;
                context.getSharedPreferences("musicbox_cloud", Context.MODE_PRIVATE).edit()
                        .putString("devices_json", root.optJSONObject("devices") == null ? "{}" : root.optJSONObject("devices").toString()).apply();
                applying = true;
                if (root.has("playlists")) playlists.importJson(root.optJSONObject("playlists"));
                if (root.has("history")) history.importJson(root.optJSONObject("history"));
                if (clicks.merge(root.optJSONObject("clicks"))) needsUpload = true;
                JSONObject preferences = root.optJSONObject("preferences");
                if (preferences != null && preferences.has("darkMode")) {
                    context.getSharedPreferences("musicbox_prefs", Context.MODE_PRIVATE).edit()
                            .putBoolean("dark_mode", preferences.optBoolean("darkMode", true)).apply();
                }
                applying = false;
                if (needsUpload) { needsUpload = false; scheduleUpload(); }
                if (uiChanged != null) uiChanged.run();
                if (!deviceRegistered) { deviceRegistered = true; scheduleUpload(); }
            } catch (Exception ignored) { applying = false; }
        });
    }

    /** Lets other screens (settings) ask the running sync to upload preferences. */
    public static void requestUpload() {
        CloudSyncManager current = active;
        if (current != null) current.scheduleUpload();
    }

    public void scheduleUpload() {
        if (applying || document == null) return;
        handler.removeCallbacks(pendingUpload);
        handler.postDelayed(pendingUpload, 650);
    }

    private void uploadNow() {
        if (document == null || FirebaseAuth.getInstance().getCurrentUser() == null) return;
        try {
            JSONObject root = new JSONObject(lastRoot.toString());
            root.put("schema", 1);
            root.put("playlists", playlists.exportJson());
            root.put("history", history.exportJson());
            root.put("clicks", clicks.exportJson());
            SharedPreferences prefs = context.getSharedPreferences("musicbox_prefs", Context.MODE_PRIVATE);
            JSONObject preferences = root.optJSONObject("preferences");
            if (preferences == null) preferences = new JSONObject();
            preferences.put("darkMode", prefs.getBoolean("dark_mode", true));
            root.put("preferences", preferences);
            JSONObject devices = root.optJSONObject("devices");
            if (devices == null) devices = new JSONObject();
            String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            JSONObject currentDevice = new JSONObject();
            currentDevice.put("name", Build.MANUFACTURER + " " + Build.MODEL);
            currentDevice.put("platform", "Android");
            currentDevice.put("lastSeen", System.currentTimeMillis());
            devices.put(deviceId == null ? "android" : deviceId, currentDevice);
            root.put("devices", devices);
            context.getSharedPreferences("musicbox_cloud", Context.MODE_PRIVATE).edit()
                    .putString("devices_json", devices.toString()).apply();
            Map<String, Object> update = new HashMap<>(); update.put("payload", root.toString());
            document.set(update);
        } catch (Exception ignored) {}
    }

    public void stop() {
        handler.removeCallbacks(pendingUpload);
        playlists.setChangeListener(null); history.setChangeListener(null); clicks.setChangeListener(null);
        if (registration != null) registration.remove();
        if (revocationWatch != null) revocationWatch.remove();
        if (active == this) active = null;
    }
}
