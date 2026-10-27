package cl.arichard.descargador.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import cl.arichard.descargador.DownloaderActivity;
import cl.arichard.descargador.R;
import cl.arichard.descargador.util.Tr;

/**
 * Mantiene vivo el proceso mientras hay descargas y muestra su avance en una notificación con "Cancelar".
 * El trabajo lo hace {@link DownloadManager}; el servicio solo lo observa y se detiene cuando la cola se vacía.
 */
public final class DownloadService extends Service {
    private static final String CHANNEL_ID = "musicbox_downloads";
    private static final int NOTIFICATION_ID = 4410;
    private static final String ACTION_CANCEL = "cl.arichard.descargador.download.CANCEL";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable observer = () -> main.post(this::refresh);
    private DownloadManager manager;
    private boolean foreground;

    public static void start(Context context) {
        try { ContextCompat.startForegroundService(context, new Intent(context, DownloadService.class)); }
        catch (Exception ignored) { /* sin permiso para iniciar en segundo plano: la cola sigue mientras la app esté abierta */ }
    }

    @Override public void onCreate() {
        super.onCreate();
        manager = DownloadManager.get(this);
        createChannel();
        manager.addObserver(observer);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) manager.cancelAll();
        refresh();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        manager.removeObserver(observer);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void refresh() {
        Notification notification = buildNotification();
        // Android exige llamar a startForeground tras startForegroundService, aunque la cola ya esté vacía.
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        }
        if (!manager.isBusy()) {
            stopForeground(true);
            foreground = false;
            stopSelf();
            return;
        }
        if (foreground) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        int waiting = 0, active = 0;
        String title = "";
        for (DownloadJob job : manager.jobs()) {
            if (job.status == DownloadJob.Status.WAITING) waiting++;
            else if (job.status.isActive()) { active++; if (!job.title.isEmpty()) title = job.title; }
        }
        int percent = Math.round(manager.overallProgress() * 100);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DownloaderActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, DownloadService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = (title.isEmpty() ? Tr.t("Descargando…") : title)
                + (waiting > 0 ? " · " + Tr.f("%d en espera", waiting) : "");
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(Tr.t("Descargador") + " · " + percent + "%")
                .setContentText(text)
                .setProgress(100, percent, active == 0 && percent == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .addAction(0, Tr.t("Cancelar"), cancel)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, Tr.t("Descargas"), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(Tr.t("Avance de las descargas en segundo plano"));
        channel.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
