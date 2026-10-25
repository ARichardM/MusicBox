package cl.arichard.descargador.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import cl.arichard.descargador.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Remote sign-out. A device revokes another by writing a timestamp for it in its own small
 * document, so a concurrent upload of the main state can never erase the request. Every device
 * compares that timestamp with the moment its own session started.
 */
public final class DeviceSessions {
    private static final String PREFS = "musicbox_cloud";
    private static final String SIGNED_IN_AT = "signed_in_at";

    private DeviceSessions() {}

    public static String deviceId(Context context) {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "android" : id;
    }

    private static String field(String deviceId) { return "d_" + deviceId; }

    private static DocumentReference revocations(String uid) {
        return FirebaseFirestore.getInstance().collection("users").document(uid).collection("state").document("revocations");
    }

    private static DocumentReference state(String uid) {
        return FirebaseFirestore.getInstance().collection("users").document(uid).collection("state").document("musicbox");
    }

    /** Call right after a successful sign-in. */
    public static void markSignedIn(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(SIGNED_IN_AT, System.currentTimeMillis()).apply();
    }

    /** Call when the user signs out on purpose. */
    public static void markSignedOut(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SIGNED_IN_AT).apply();
    }

    private static long signedInAt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long value = prefs.getLong(SIGNED_IN_AT, 0);
        if (value == 0) {
            // Sessions created before this feature existed start counting now.
            value = System.currentTimeMillis();
            prefs.edit().putLong(SIGNED_IN_AT, value).apply();
        }
        return value;
    }

    /** Starts watching for a revocation aimed at this device. */
    public static ListenerRegistration watch(Context context) {
        Context app = context.getApplicationContext();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return null;
        long since = signedInAt(app);
        String key = field(deviceId(app));
        return revocations(user.getUid()).addSnapshotListener((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            Long revokedAt = snapshot.getLong(key);
            if (revokedAt != null && revokedAt > since) signOutLocally(app);
        });
    }

    public static void signOutLocally(Context context) {
        FirebaseAuth.getInstance().signOut();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SIGNED_IN_AT).apply();
        context.startActivity(new Intent(context, LoginActivity.class)
                .putExtra(LoginActivity.EXTRA_REVOKED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    public interface Result { void done(boolean success); }

    /** Signs another device out and removes it from the device list. */
    public static void revoke(Context context, String targetDeviceId, Result result) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || targetDeviceId == null || targetDeviceId.equals(deviceId(context))) { result.done(false); return; }
        Map<String, Object> mark = new HashMap<>();
        mark.put(field(targetDeviceId), System.currentTimeMillis());
        revocations(user.getUid()).set(mark, SetOptions.merge()).addOnSuccessListener(ignored -> {
            removeFromList(context, user.getUid(), targetDeviceId);
            result.done(true);
        }).addOnFailureListener(e -> result.done(false));
    }

    private static void removeFromList(Context context, String uid, String targetDeviceId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject devices = new JSONObject(prefs.getString("devices_json", "{}"));
            devices.remove(targetDeviceId);
            prefs.edit().putString("devices_json", devices.toString()).apply();
        } catch (Exception ignored) {}
        state(uid).get().addOnSuccessListener(snapshot -> {
            try {
                String payload = snapshot.getString("payload");
                if (payload == null) return;
                JSONObject root = new JSONObject(payload);
                JSONObject devices = root.optJSONObject("devices");
                if (devices == null || !devices.has(targetDeviceId)) return;
                devices.remove(targetDeviceId);
                Map<String, Object> update = new HashMap<>();
                update.put("payload", root.toString());
                state(uid).set(update);
            } catch (Exception ignored) {}
        });
    }
}
