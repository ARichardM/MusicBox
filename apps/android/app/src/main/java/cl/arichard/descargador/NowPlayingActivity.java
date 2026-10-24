package cl.arichard.descargador;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.CoverArtLoader;
import cl.arichard.descargador.util.StorageUtil;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;

public class NowPlayingActivity extends AppCompatActivity implements PlayerManager.PlaybackListener {

    public static final String EXTRA_VIDEO_ID = "extra_video_id";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_CHANNEL = "extra_channel";
    public static final String EXTRA_THUMBNAIL = "extra_thumbnail";

    private View nowPlayingRoot;
    private ImageView btnBack, btnAction, btnFavorite;
    private TextView tabSelectorMp3, tabSelectorMp4;
    private FrameLayout containerCoverArt, containerVideoPlayer, videoHostLayout;
    private ImageView imgNowPlayingCover;
    private TextView txtTitle, txtArtist;
    private SeekBar seekBar;
    private TextView txtCurrentTime, txtTotalTime;

    private ImageView btnShuffle, btnPrevious, btnPlayPauseIcon, btnNext, btnRepeat;
    private FrameLayout btnPlayPauseCircle;

    // Download Banner
    private LinearLayout playerDownloadCard;
    private TextView txtPlayerDownloadStatus, txtPlayerDownloadEta;
    private ProgressBar playerDownloadProgress;

    private boolean isMp3Mode = true;
    private boolean isUserSeeking = false;
    private boolean isDownloading = false;

    private String onlineVideoId;
    private String onlineUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        initViews();
        setupClickListeners();
        cl.arichard.descargador.util.SafeTap.installButtons(nowPlayingRoot);
        cl.arichard.descargador.util.SafeTap.install(btnPlayPauseCircle);
        setupPlayerGestures();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { closeWithSlideDown(); }
        });

        handleIncomingIntent(getIntent());
    }

    private void initViews() {
        nowPlayingRoot = findViewById(R.id.nowPlayingRoot);
        btnBack = findViewById(R.id.btnBackNowPlaying);
        btnAction = findViewById(R.id.btnNowPlayingAction);
        btnFavorite = findViewById(R.id.btnNowPlayingFavorite);

        tabSelectorMp3 = findViewById(R.id.tabSelectorMp3);
        tabSelectorMp4 = findViewById(R.id.tabSelectorMp4);

        containerCoverArt = findViewById(R.id.containerCoverArt);
        containerVideoPlayer = findViewById(R.id.containerVideoPlayer);
        videoHostLayout = findViewById(R.id.videoHostLayout);
        imgNowPlayingCover = findViewById(R.id.imgNowPlayingCover);

        txtTitle = findViewById(R.id.txtNowPlayingTitle);
        txtArtist = findViewById(R.id.txtNowPlayingArtist);
        txtCurrentTime = findViewById(R.id.txtCurrentTime);
        txtTotalTime = findViewById(R.id.txtTotalTime);
        seekBar = findViewById(R.id.seekBar);

        btnShuffle = findViewById(R.id.btnShuffle);
        btnPrevious = findViewById(R.id.btnPrevious);
        btnPlayPauseCircle = findViewById(R.id.btnPlayPauseCircle);
        btnPlayPauseIcon = findViewById(R.id.btnPlayPauseIcon);
        btnNext = findViewById(R.id.btnNext);
        btnRepeat = findViewById(R.id.btnRepeat);

        playerDownloadCard = findViewById(R.id.playerDownloadCard);
        txtPlayerDownloadStatus = findViewById(R.id.txtPlayerDownloadStatus);
        txtPlayerDownloadEta = findViewById(R.id.txtPlayerDownloadEta);
        playerDownloadProgress = findViewById(R.id.playerDownloadProgress);

        txtTitle.setSelected(true);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String vid = intent.getStringExtra(EXTRA_VIDEO_ID);
        String url = intent.getStringExtra(EXTRA_URL);
        if (vid != null && !vid.isEmpty()) {
            onlineVideoId = vid;
            onlineUrl = url;
        }
    }

    private void setupClickListeners() {
        findViewById(R.id.btnPlayerMore).setOnClickListener(this::showPlayerMenu);
        btnBack.setOnClickListener(v -> closeWithSlideDown());

        tabSelectorMp3.setOnClickListener(v -> setMediaMode(true));
        tabSelectorMp4.setOnClickListener(v -> setMediaMode(false));

        btnPlayPauseCircle.setOnClickListener(v -> PlayerManager.getInstance().togglePlayPause());
        btnNext.setOnClickListener(v -> PlayerManager.getInstance().playNext());
        btnPrevious.setOnClickListener(v -> PlayerManager.getInstance().playPrevious());

        btnShuffle.setOnClickListener(v -> {
            boolean active = PlayerManager.getInstance().toggleShuffle();
            updateShuffleButton(active);
        });

        btnRepeat.setOnClickListener(v -> {
            int mode = PlayerManager.getInstance().toggleRepeat();
            updateRepeatButton(mode);
            Toast.makeText(this, repeatLabel(mode), Toast.LENGTH_SHORT).show();
        });

        btnAction.setOnClickListener(v -> startTrackDownload());
        btnFavorite.setOnClickListener(v -> toggleCurrentFavorite());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    txtCurrentTime.setText(formatTime(progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { isUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                isUserSeeking = false;
                PlayerManager.getInstance().seekTo(sb.getProgress());
            }
        });
    }

    private void setMediaMode(boolean mp3) {
        mp3 = mp3 || !PlayerManager.getInstance().hasVideo();
        isMp3Mode = mp3;
        if (mp3) PlayerManager.getInstance().detachOnlineWebView();
        else PlayerManager.getInstance().attachOnlineWebView(videoHostLayout);
        int activeText = ContextCompat.getColor(this, R.color.chip_active_text);
        int inactiveText = ContextCompat.getColor(this, R.color.text_secondary);

        if (isMp3Mode) {
            tabSelectorMp3.setBackgroundResource(R.drawable.bg_chip_active);
            tabSelectorMp3.setTextColor(activeText);
            tabSelectorMp4.setBackground(null);
            tabSelectorMp4.setTextColor(inactiveText);

            containerCoverArt.setVisibility(View.VISIBLE);
            containerVideoPlayer.setVisibility(View.INVISIBLE); // Keep measured and rendering
        } else {
            tabSelectorMp4.setBackgroundResource(R.drawable.bg_chip_active);
            tabSelectorMp4.setTextColor(activeText);
            tabSelectorMp3.setBackground(null);
            tabSelectorMp3.setTextColor(inactiveText);

            containerCoverArt.setVisibility(View.GONE);
            containerVideoPlayer.setVisibility(View.VISIBLE);
        }
    }

    private void startTrackDownload() {
        if (isDownloading) {
            Toast.makeText(this, "Descarga en curso", Toast.LENGTH_SHORT).show();
            return;
        }

        String downloadUrl = onlineUrl;
        if ((downloadUrl == null || downloadUrl.isEmpty()) && onlineVideoId != null) {
            downloadUrl = "https://www.youtube.com/watch?v=" + onlineVideoId;
        }
        if (downloadUrl == null) {
            downloadUrl = PlayerManager.getInstance().getCurrentOnlineUrl();
        }
        if (downloadUrl == null) {
            Toast.makeText(this, "No se puede descargar esta pista", Toast.LENGTH_SHORT).show();
            return;
        }

        File dir = StorageUtil.getDownloadDir(this);
        YoutubeDLRequest request = new YoutubeDLRequest(downloadUrl);
        request.addOption("--no-playlist");
        request.addOption("--no-update");
        request.addOption("--extractor-args", "youtube:player_client=android,web");
        request.addOption("--no-mtime");
        request.addOption("--restrict-filenames");
        request.addOption("-o", new File(dir, "%(title).160s.%(ext)s").getAbsolutePath());

        if (isMp3Mode) {
            request.addOption("-x");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", "192K");
            request.addOption("--embed-thumbnail");
            request.addOption("--add-metadata");
        } else {
            request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
            request.addOption("--merge-output-format", "mp4");
        }

        isDownloading = true;
        playerDownloadCard.setVisibility(View.VISIBLE);
        playerDownloadProgress.setProgress(0);
        txtPlayerDownloadStatus.setText("Iniciando descarga (" + (isMp3Mode ? "MP3" : "MP4") + ")...");
        txtPlayerDownloadEta.setText("Conectando...");

        DescargadorApp.IO.execute(() -> {
            try {
                YoutubeDL.getInstance().execute(request, "now-playing-dl", (percent, eta, line) -> {
                    runOnUiThread(() -> {
                        int p = Math.round(percent);
                        playerDownloadProgress.setProgress(p);
                        txtPlayerDownloadStatus.setText("Descargando " + p + "% (" + (isMp3Mode ? "MP3" : "MP4") + ")");
                        txtPlayerDownloadEta.setText("Faltan aprox. " + eta + " s");
                    });
                    return kotlin.Unit.INSTANCE;
                });

                runOnUiThread(() -> {
                    playerDownloadProgress.setProgress(100);
                    txtPlayerDownloadStatus.setText("Descarga completada (" + (isMp3Mode ? "MP3" : "MP4") + ")");
                    txtPlayerDownloadEta.setText("Guardado en descargas");
                    isDownloading = false;
                    playerDownloadCard.postDelayed(() -> playerDownloadCard.setVisibility(View.GONE), 3500);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtPlayerDownloadStatus.setText("Error en la descarga");
                    txtPlayerDownloadEta.setText(e.getMessage() != null ? e.getMessage() : "Error");
                    isDownloading = false;
                    playerDownloadCard.postDelayed(() -> playerDownloadCard.setVisibility(View.GONE), 4000);
                });
            }
        });
    }

    private void setupPlayerGestures() {
        cl.arichard.descargador.util.PlayerDismissLayout gestures =
                (cl.arichard.descargador.util.PlayerDismissLayout) nowPlayingRoot;
        gestures.setDismissAction(this::closeWithSlideDown);
        gestures.setTrackActions(
                () -> PlayerManager.getInstance().playNext(),
                () -> PlayerManager.getInstance().skipPrevious());
    }

    private void closeWithSlideDown() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        nowPlayingRoot.animate()
                .translationY(screenHeight)
                .alpha(0.0f)
                .setDuration(220)
                .withEndAction(() -> { finish(); overridePendingTransition(0, 0); })
                .start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerManager.getInstance().addListener(this);


        Song current = PlayerManager.getInstance().getCurrentSong();
        if (current != null) {
            updateSongInfo(current);
            updatePlaybackState(PlayerManager.getInstance().isPlaying());
            int pos = PlayerManager.getInstance().getCurrentPosition();
            int dur = PlayerManager.getInstance().getDuration();
            updateProgress(pos, dur);
        }
        updateShuffleButton(PlayerManager.getInstance().isShuffle());
        updateRepeatButton(PlayerManager.getInstance().getRepeatMode());
        setMediaMode(isMp3Mode);
        onPlaybackLoading(PlayerManager.getInstance().isLoading());
    }

    @Override
    protected void onPause() {
        PlayerManager.getInstance().savePosition();
        super.onPause();
        PlayerManager.getInstance().removeListener(this);
        PlayerManager.getInstance().detachOnlineWebView();
    }

    private void updateSongInfo(Song song) {
        if (song == null) { finish(); return; }
        onlineVideoId = song.getVideoId();
        onlineUrl = song.isOnline() ? "https://www.youtube.com/watch?v=" + onlineVideoId : null;
        updateVideoAvailability();
        txtTitle.setText(song.getTitle());
        txtArtist.setText(song.getArtist());

        btnAction.setVisibility(song.isOnline() ? View.VISIBLE : View.GONE);
        updateFavoriteButton(song);

        if (imgNowPlayingCover != null) {
            CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), imgNowPlayingCover);
        }
    }

    private void toggleCurrentFavorite() {
        Song song = PlayerManager.getInstance().getCurrentSong();
        if (song == null) return;
        PlaylistManager playlists = PlaylistManager.getInstance(this);
        if (song.isOnline()) {
            String videoId = song.getVideoId();
            boolean favorite = playlists.isOnlineTrackInPlaylist("Favoritos", videoId);
            if (favorite) playlists.removeSongFromPlaylist("Favoritos", song.getPath());
            else playlists.addOnlineTrack("Favoritos", new SearchResult(videoId, song.getTitle(), song.getArtist(),
                    Math.max(0, song.getDuration() / 1000), song.getCoverPath(),
                    "https://www.youtube.com/watch?v=" + videoId));
        } else {
            playlists.toggleFavorite(song.getPath());
        }
        updateFavoriteButton(song);
    }

    private void updateFavoriteButton(Song song) {
        boolean favorite = song.isOnline()
                ? PlaylistManager.getInstance(this).isOnlineTrackInPlaylist("Favoritos", song.getVideoId())
                : PlaylistManager.getInstance(this).isFavorite(song.getPath());
        btnFavorite.setImageResource(favorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
        btnFavorite.clearColorFilter();
        btnFavorite.setContentDescription(favorite ? "Quitar de favoritos" : "Agregar a favoritos");
    }

    private void showPlayerMenu(View anchor) {
        Song song = PlayerManager.getInstance().getCurrentSong();
        if (song == null) return;
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        menu.getMenu().add("Agregar a playlist").setIcon(R.drawable.ic_playlist).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.addToPlaylist(this, song, () -> updateSongInfo(PlayerManager.getInstance().getCurrentSong())); return true;
        });
        menu.getMenu().add("Agregar a carpeta").setIcon(R.drawable.ic_folder).setOnMenuItemClickListener(item -> {
            DescargadorApp.IO.execute(() -> {
                java.util.List<Song> library = cl.arichard.descargador.data.MusicScanner.scanAllMusic(this);
                runOnUiThread(() -> { if (!isFinishing()) cl.arichard.descargador.util.SongActions.addToFolder(this, song, library, () -> {}); });
            });
            return true;
        });
        menu.getMenu().add("Eliminar canci\u00f3n").setIcon(R.drawable.ic_delete).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.delete(this, song, this::finish); return true;
        });
        menu.getMenu().add("Cola de reproducci\u00f3n").setIcon(R.drawable.ic_queue).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.player.QueueSheet.show(this); return true;
        });
        menu.getMenu().add("Guardar cola en playlist...").setIcon(R.drawable.ic_playlist).setOnMenuItemClickListener(item -> {
            java.util.List<Song> q = PlayerManager.getInstance().getQueue();
            if (q != null && !q.isEmpty()) {
                cl.arichard.descargador.util.SongActions.addMultipleToPlaylist(this, q, () -> {});
            } else {
                Toast.makeText(this, "La cola est\u00e1 vac\u00eda", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        for (int i = 0; i < menu.getMenu().size(); i++) {
            android.graphics.drawable.Drawable icon = menu.getMenu().getItem(i).getIcon();
            if (icon != null) icon.mutate().setTint(ContextCompat.getColor(this, R.color.text_primary));
        }
        menu.show();
    }
    private void updateVideoAvailability() {
        boolean available = PlayerManager.getInstance().hasVideo();
        tabSelectorMp4.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available && !isMp3Mode) setMediaMode(true);
    }
    @Override public void onVideoAvailabilityChanged(boolean available) { updateVideoAvailability(); }

    private void updatePlaybackState(boolean isPlaying) {
        btnPlayPauseIcon.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
    }

    private void updateProgress(int pos, int dur) {
        if (dur > 0) {
            seekBar.setMax(dur);
            seekBar.setProgress(pos);
            txtCurrentTime.setText(formatTime(pos));
            txtTotalTime.setText(formatTime(dur));
        } else {
            seekBar.setProgress(0);
            txtCurrentTime.setText("00:00");
            txtTotalTime.setText("00:00");
        }
    }

    private void updateShuffleButton(boolean active) {
        btnShuffle.setAlpha(active ? 1.0f : 0.45f);
        btnShuffle.setColorFilter(active ? ContextCompat.getColor(this, R.color.accent) : ContextCompat.getColor(this, R.color.text_primary));
    }

    private String repeatLabel(int mode) {
        return mode == PlayerManager.REPEAT_ONE ? "Repetir canción" :
                mode == PlayerManager.REPEAT_ALL ? "Repetir lista" : "Bucle desactivado";
    }

    private void updateRepeatButton(int mode) {
        btnRepeat.setContentDescription(repeatLabel(mode));
        ((TextView) findViewById(R.id.txtRepeatMode)).setText(repeatLabel(mode));
        if (mode == PlayerManager.REPEAT_OFF) {
            btnRepeat.setAlpha(0.45f);
            btnRepeat.setImageResource(R.drawable.ic_repeat);
            btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.text_primary));
        } else if (mode == PlayerManager.REPEAT_ALL) {
            btnRepeat.setAlpha(1.0f);
            btnRepeat.setImageResource(R.drawable.ic_repeat);
            btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent));
        } else {
            btnRepeat.setAlpha(1.0f);
            btnRepeat.setImageResource(R.drawable.ic_repeat_one);
            btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent));
        }
    }

    private String formatTime(int ms) {
        int totalSec = ms / 1000;
        int m = totalSec / 60;
        int s = totalSec % 60;
        return String.format("%02d:%02d", m, s);
    }

    @Override
    public void onTrackChanged(Song song) {
        runOnUiThread(() -> updateSongInfo(song));
    }

    @Override
    public void onPlaybackLoading(boolean loading) {
        if (loading) txtCurrentTime.setText("Cargando…");
    }

    @Override
    public void onPlaybackError(String message) {
        txtCurrentTime.setText("Error");
        txtTotalTime.setText("Reintentar ▶");
        cl.arichard.descargador.util.AppNotice.error(findViewById(R.id.seekBar), message,
                "REINTENTAR", () -> PlayerManager.getInstance().play());
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> updatePlaybackState(isPlaying));
    }

    @Override
    public void onProgressUpdate(int currentPos, int duration) {
        if (!isUserSeeking) {
            runOnUiThread(() -> updateProgress(currentPos, duration));
        }
    }
}
