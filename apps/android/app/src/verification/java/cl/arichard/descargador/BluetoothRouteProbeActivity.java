package cl.arichard.descargador;

import android.app.Activity;
import android.media.AudioDeviceInfo;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;

/** ADB-only, verification-flavor probe for real Android Bluetooth routing. */
public final class BluetoothRouteProbeActivity extends Activity {
    private static final String TAG = "BluetoothRouteProbe";
    private static final int POLL_MS = 250;
    private static final int ROUTE_TIMEOUT_MS = 90000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlayerManager player;
    private Song sample;
    private TextView status;
    private long startedAt;
    private long changedAt;
    private long stoppedAt;
    private int initialRouteId = -1;
    private int candidateRouteId = -1;
    private boolean completed;

    private final Runnable inspectRoute = new Runnable() {
        @Override public void run() {
            if (isFinishing() || completed) return;
            AudioDeviceInfo route = routedOutput();
            long now = System.currentTimeMillis();
            if (player.isPlaying()) {
                stoppedAt = 0;
            } else if (initialRouteId >= 0) {
                if (stoppedAt == 0) stoppedAt = now;
                if (now - stoppedAt > 2000) {
                    finishProbe("FALLO: la reproducción se detuvo durante el cambio.", false);
                    return;
                }
            }
            if (initialRouteId < 0) {
                if (player.isPlaying() && isBluetooth(route)) {
                    initialRouteId = route.getId();
                    update("REPRODUCIENDO por Bluetooth (" + route.getProductName()
                            + "). Cambia ahora a la segunda salida.");
                    Log.i(TAG, "READY routeId=" + initialRouteId + " type=" + route.getType());
                } else if (now - startedAt > 15000) {
                    finishProbe("No se detectó reproducción por Bluetooth. Ruta: " + describe(route), false);
                    return;
                }
            } else if (player.isPlaying() && isBluetooth(route) && route.getId() != initialRouteId) {
                if (candidateRouteId != route.getId()) {
                    candidateRouteId = route.getId();
                    changedAt = now;
                    update("Cambio de ruta detectado. Confirmando reproducción...");
                    Log.i(TAG, "ROUTE_CHANGED routeId=" + candidateRouteId + " type=" + route.getType());
                } else if (now - changedAt >= 1500) {
                    finishProbe("APROBADO: la reproducción sigue activa en " + route.getProductName() + ".", true);
                    return;
                }
            } else if (now - startedAt > ROUTE_TIMEOUT_MS) {
                finishProbe("Sin cambio detectado. Mantén dos salidas Bluetooth disponibles.", false);
                return;
            } else {
                candidateRouteId = -1;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setPadding(32, 32, 32, 32);
        Button stop = new Button(this);
        stop.setText("Detener prueba");
        stop.setOnClickListener(view -> finish());
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        layout.addView(stop);
        setContentView(layout);

        try {
            player = PlayerManager.getInstance();
            sample = createSilentSample();
            player.playSong(sample, Collections.singletonList(sample), false);
            startedAt = System.currentTimeMillis();
            update("Preparando audio sintético privado...");
            handler.postDelayed(inspectRoute, POLL_MS);
        } catch (Exception error) {
            update("No se pudo iniciar la prueba: " + error.getMessage());
            Log.e(TAG, "Could not start Bluetooth route probe", error);
        }
    }

    private Song createSilentSample() throws Exception {
        int sampleRate = 16000;
        int dataBytes = sampleRate * 2 * 120;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + dataBytes).put(new byte[]{'W','A','V','E','f','m','t',' '});
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2);
        wav.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(dataBytes);
        File file = new File(getFilesDir(), "bluetooth-route-probe.wav");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(wav.array());
        }
        return new Song(0, "Bluetooth route verification", "Verification", "Synthetic", 120000,
                file.getPath(), "Verification", 0);
    }

    private AudioDeviceInfo routedOutput() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || player == null) return null;
        try {
            Field field = PlayerManager.class.getDeclaredField("mediaPlayer");
            field.setAccessible(true);
            MediaPlayer mediaPlayer = (MediaPlayer) field.get(player);
            return mediaPlayer == null ? null : mediaPlayer.getRoutedDevice();
        } catch (ReflectiveOperationException error) {
            Log.e(TAG, "Could not inspect current audio route", error);
            return null;
        }
    }

    private boolean isBluetooth(AudioDeviceInfo route) {
        return route != null && (route.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || route.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && route.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET));
    }

    private String describe(AudioDeviceInfo route) {
        return route == null ? "desconocida" : route.getProductName() + " (tipo "
                + route.getType() + ", id " + route.getId() + ")";
    }

    private void update(String message) {
        status.setText(message);
    }

    private void finishProbe(String message, boolean passed) {
        completed = true;
        update(message);
        Log.i(TAG, (passed ? "PASS: " : "FAIL: ") + message);
        if (!passed && player != null) player.stopAndRelease();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(inspectRoute);
        if (player != null) player.stopAndRelease();
        if (sample != null) new File(sample.getPath()).delete();
        super.onDestroy();
    }
}
