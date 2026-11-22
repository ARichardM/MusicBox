package cl.arichard.descargador.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import cl.arichard.descargador.NowPlayingActivity;
import cl.arichard.descargador.R;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.CoverArtLoader;

public class MusicService extends Service {
    public static final String CHANNEL_ID = "musicbox_channel";
    public static final int NOTIFICATION_ID = 2024;

    public static final String ACTION_TOGGLE = "cl.arichard.descargador.TOGGLE";
    public static final String ACTION_NEXT = "cl.arichard.descargador.NEXT";
    public static final String ACTION_PREV = "cl.arichard.descargador.PREV";
    public static final String ACTION_STOP = "cl.arichard.descargador.STOP";
    public static final String ACTION_UPDATE = "cl.arichard.descargador.UPDATE";
    public static final String ACTION_SHUFFLE = "cl.arichard.descargador.SHUFFLE";
    public static final String ACTION_REPEAT = "cl.arichard.descargador.REPEAT";
    public static final String ACTION_FAVORITE = "cl.arichard.descargador.FAVORITE";

    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    private static final String EXTRA_KEEP_FOREGROUND = "extra_keep_foreground";
    private static final String EXTRA_STARTED_FOREGROUND = "extra_started_foreground";

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile MusicService active;

    private MediaSessionCompat mediaSession;
    private boolean inForeground;

    public static boolean isInForeground() {
        MusicService current = active;
        return current != null && current.inForeground;
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        createNotificationChannel();
        initMediaSession();
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "MusicBoxSession");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { PlayerManager.getInstance().play(); }
            @Override public void onPause() { PlayerManager.getInstance().pause(); }
            @Override public void onSkipToNext() { PlayerManager.getInstance().playNext(); }
            @Override public void onSkipToPrevious() { PlayerManager.getInstance().playPrevious(); }
            @Override public void onSeekTo(long pos) { PlayerManager.getInstance().seekTo((int) pos); }
            @Override public void onStop() { PlayerManager.getInstance().pause(); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (handleControl(action)) refresh();
            }
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });
        mediaSession.setActive(true);
    }

    private void enterForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        inForeground = true;
    }

    private void detachForeground(Notification notification) {
        if (inForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            inForeground = false;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && notification != null) nm.notify(NOTIFICATION_ID, notification);
    }

    private void applyPausedState(String title, String artist) {
        if (title == null) return;
        PlayerManager pm = PlayerManager.getInstance();
        if (pm.shouldKeepServiceForeground()) return;
        Notification notification = buildNotification(title, artist != null ? artist : "MusicBox", false);
        detachForeground(notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            boolean startedForeground = intent.getBooleanExtra(EXTRA_STARTED_FOREGROUND, false);
            PlayerManager pm = PlayerManager.getInstance();

            // Handle headset / Bluetooth hardware media buttons
            if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                MediaButtonReceiver.handleIntent(mediaSession, intent);
                return START_NOT_STICKY;
            }

            if (ACTION_TOGGLE.equals(action)) {
                pm.togglePlayPause();
            } else if (ACTION_NEXT.equals(action)) {
                pm.playNext();
            } else if (ACTION_PREV.equals(action)) {
                pm.playPrevious();
            } else if (ACTION_SHUFFLE.equals(action) || ACTION_REPEAT.equals(action) || ACTION_FAVORITE.equals(action)) {
                handleControl(action);
            } else if (ACTION_STOP.equals(action)) {
                pm.pause();
                if (mediaSession != null) mediaSession.setActive(false);
                if (startedForeground && !inForeground) {
                    enterForeground(buildNotification("MusicBox", "", false));
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                inForeground = false;
                stopSelf();
                return START_NOT_STICKY;
            }

            String title = intent.getStringExtra(EXTRA_TITLE);
            String artist = intent.getStringExtra(EXTRA_ARTIST);
            boolean isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, pm.isPlaying());
            boolean keepForeground = intent.getBooleanExtra(EXTRA_KEEP_FOREGROUND, pm.shouldKeepServiceForeground());

            if (title == null && pm.getCurrentSong() != null) {
                title = pm.getCurrentSong().getTitle();
                artist = pm.getCurrentSong().getArtist();
                isPlaying = pm.isPlaying();
                keepForeground = pm.shouldKeepServiceForeground();
            } else if (ACTION_UPDATE.equals(action) && pm.getCurrentSong() != null && !pm.shouldKeepServiceForeground()) {
                isPlaying = pm.isPlaying();
                keepForeground = false;
            }

            if (title != null) {
                Notification notification = buildNotification(title, artist != null ? artist : "MusicBox", isPlaying);
                if (keepForeground) {
                    enterForeground(notification);
                } else {
                    // Android O+ exige llamar a startForeground tras startForegroundService;
                    // luego soltamos de inmediato para no retener el servicio en primer plano en pausa.
                    if (startedForeground && !inForeground) enterForeground(notification);
                    detachForeground(notification);
                }
            } else if (startedForeground && !inForeground) {
                enterForeground(buildNotification("MusicBox", "", false));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
                inForeground = false;
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    /** Shuffle, repeat and favorite buttons shared by the notification and the lock-screen controls. */
    private boolean handleControl(String action) {
        PlayerManager pm = PlayerManager.getInstance();
        if (ACTION_SHUFFLE.equals(action)) { pm.toggleShuffle(); return true; }
        if (ACTION_REPEAT.equals(action)) { pm.toggleRepeat(); return true; }
        if (ACTION_FAVORITE.equals(action)) {
            cl.arichard.descargador.data.PlaylistManager.getInstance(this).toggleFavoriteSong(pm.getCurrentSong());
            return true;
        }
        return false;
    }

    private void refresh() {
        PlayerManager pm = PlayerManager.getInstance();
        Song song = pm.getCurrentSong();
        if (song == null) return;
        Notification notification = buildNotification(song.getTitle(), song.getArtist(), pm.isPlaying());
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification);
    }

    private PendingIntent controlIntent(int code, String action) {
        Intent intent = new Intent(this, MusicService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(EXTRA_STARTED_FOREGROUND, true);
            return PendingIntent.getForegroundService(this, code, intent, flags);
        }
        return PendingIntent.getService(this, code, intent, flags);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                cl.arichard.descargador.util.Tr.t("Reproductor de Música"),
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(cl.arichard.descargador.util.Tr.t("Controles de música en segundo plano"));
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String artist, boolean isPlaying) {
        PlayerManager pm = PlayerManager.getInstance();
        Song song = pm.getCurrentSong();
        long duration = pm.getDuration();
        long position = pm.getCurrentPosition();

        if (mediaSession == null) {
            initMediaSession();
        }

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;

        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        boolean shuffle = pm.isShuffle();
        int repeat = pm.getRepeatMode();
        boolean favorite = cl.arichard.descargador.data.PlaylistManager.getInstance(this).isFavoriteSong(song);
        int repeatIcon = repeat == PlayerManager.REPEAT_ONE ? R.drawable.ic_repeat_one
                : repeat == PlayerManager.REPEAT_ALL ? R.drawable.ic_repeat_on : R.drawable.ic_repeat;
        String repeatLabel = repeat == PlayerManager.REPEAT_ONE ? cl.arichard.descargador.util.Tr.t("Repetir canción")
                : repeat == PlayerManager.REPEAT_ALL ? cl.arichard.descargador.util.Tr.t("Repetir lista")
                : cl.arichard.descargador.util.Tr.t("Bucle desactivado");
        String shuffleLabel = cl.arichard.descargador.util.Tr.t(shuffle ? "Aleatorio activado" : "Aleatorio");
        int shuffleIcon = shuffle ? R.drawable.ic_shuffle_on : R.drawable.ic_shuffle;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, isPlaying ? 1.0f : 0.0f)
                .addCustomAction(ACTION_SHUFFLE, shuffleLabel, shuffleIcon)
                .addCustomAction(ACTION_REPEAT, repeatLabel, repeatIcon)
                .addCustomAction(ACTION_FAVORITE,
                        cl.arichard.descargador.util.Tr.t(favorite ? "Quitar de favoritos" : "Agregar a favoritos"),
                        favorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite)
                .build();
        mediaSession.setPlaybackState(playbackState);

        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration : -1);

        Bitmap art = null;
        if (song != null) {
            if (song.getAlbum() != null) metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.getAlbum());
            if (song.getCoverPath() != null) {
                art = CoverArtLoader.getInstance().getBitmap(song.getCoverPath());
            }
        }
        if (art != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art);
        }
        mediaSession.setMetadata(metaBuilder.build());

        PendingIntent openApp = PendingIntent.getActivity(
            this, 0,
            new Intent(this, NowPlayingActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent pPrev = controlIntent(1, ACTION_PREV);
        PendingIntent pToggle = controlIntent(2, ACTION_TOGGLE);
        PendingIntent pNext = controlIntent(3, ACTION_NEXT);
        PendingIntent pStop = PendingIntent.getService(
            this, 6,
            new Intent(this, MusicService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int playIcon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("MusicBox")
            .setContentIntent(openApp)
            .setDeleteIntent(pStop)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(shuffleIcon, shuffleLabel, controlIntent(4, ACTION_SHUFFLE))
            .addAction(R.drawable.ic_skip_previous, cl.arichard.descargador.util.Tr.t("Anterior"), pPrev)
            .addAction(playIcon, isPlaying ? cl.arichard.descargador.util.Tr.t("Pausar") : cl.arichard.descargador.util.Tr.t("Reproducir"), pToggle)
            .addAction(R.drawable.ic_skip_next, cl.arichard.descargador.util.Tr.t("Siguiente"), pNext)
            .addAction(repeatIcon, repeatLabel, controlIntent(5, ACTION_REPEAT))
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(1, 2, 3)
                .setShowCancelButton(true)
                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)))
            .setOngoing(isPlaying);

        if (art != null) {
            builder.setLargeIcon(art);
        }

        return builder.build();
    }

    public static void updateNotification(Context context, Song song, boolean isPlaying) {
        updateNotification(context, song, isPlaying, isPlaying);
    }

    public static void updateNotification(Context context, Song song, boolean isPlaying, boolean keepForeground) {
        if (song == null || context == null) return;
        try {
            if (!keepForeground) {
                MusicService current = active;
                if (current == null) return;
                String title = song.getTitle();
                String artist = song.getArtist();
                MAIN.post(() -> {
                    if (active == current) current.applyPausedState(title, artist);
                });
                return;
            }
            Intent serviceIntent = new Intent(context, MusicService.class);
            serviceIntent.setAction(ACTION_UPDATE);
            serviceIntent.putExtra(EXTRA_TITLE, song.getTitle());
            serviceIntent.putExtra(EXTRA_ARTIST, song.getArtist());
            serviceIntent.putExtra(EXTRA_IS_PLAYING, isPlaying);
            serviceIntent.putExtra(EXTRA_KEEP_FOREGROUND, true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                serviceIntent.putExtra(EXTRA_STARTED_FOREGROUND, true);
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Al cerrar la app desde recientes la música se detiene y la notificación desaparece. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        PlayerManager.getInstance().stopAndRelease();
        if (mediaSession != null) mediaSession.setActive(false);
        stopForeground(true);
        inForeground = false;
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        inForeground = false;
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
