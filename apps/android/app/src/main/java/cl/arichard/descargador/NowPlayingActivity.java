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
    private ImageView btnBack, btnFavorite;
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

    private View nowPlayingCoordinator;
    private com.google.android.material.bottomsheet.BottomSheetBehavior<View> queueBehavior;
    private cl.arichard.descargador.player.QueueSheet queuePanel;

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
        setupQueueSheet();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (queueBehavior != null && queueBehavior.getState() == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                    queueBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED);
                } else closeWithSlideDown();
            }
        });

        handleIncomingIntent(getIntent());
    }

    private void initViews() {
        nowPlayingRoot = findViewById(R.id.nowPlayingRoot);
        nowPlayingCoordinator = findViewById(R.id.nowPlayingCoordinator);
        btnBack = findViewById(R.id.btnBackNowPlaying);
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

    private void setupPlayerGestures() {
        cl.arichard.descargador.util.PlayerDismissLayout gestures =
                (cl.arichard.descargador.util.PlayerDismissLayout) nowPlayingRoot;
        gestures.setDismissAction(this::closeWithSlideDown);
        gestures.setTrackActions(
                () -> PlayerManager.getInstance().playNext(),
                () -> PlayerManager.getInstance().skipPrevious());
    }

    /** The queue peeks from the bottom edge; dragging it up reveals the full, editable list. */
    private void setupQueueSheet() {
        android.widget.FrameLayout sheet = findViewById(R.id.queueSheet);
        float density = getResources().getDisplayMetrics().density;
        queueBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
        queueBehavior.setPeekHeight(Math.round(84 * density));
        queueBehavior.setHideable(false);
        queueBehavior.setFitToContents(false);
        queueBehavior.setExpandedOffset(Math.round(56 * density));
        queueBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED);
        queuePanel = cl.arichard.descargador.player.QueueSheet.embed(this, sheet, queueBehavior);
        queueBehavior.addBottomSheetCallback(new com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(View view, int newState) {
                if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) queuePanel.scrollToCurrent();
            }
            @Override public void onSlide(View view, float offset) {
                float progress = Math.max(0f, offset);
                nowPlayingRoot.setAlpha(1f - 0.55f * progress);
            }
        });
    }

    /** "Reproduciendo desde": the folder, the playlist, all songs or the YouTube search the queue came from. */
    private void updatePlayingFrom() {
        ((TextView) findViewById(R.id.txtPlayingFromName)).setText(PlayerManager.getInstance().playingFromLabel());
    }

    private void closeWithSlideDown() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        nowPlayingCoordinator.animate()
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
    protected void onDestroy() {
        if (queuePanel != null) queuePanel.detach();
        super.onDestroy();
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
        updatePlayingFrom();

        updateFavoriteButton(song);

        if (imgNowPlayingCover != null) {
            CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), imgNowPlayingCover);
        }
    }

    private void toggleCurrentFavorite() {
        Song song = PlayerManager.getInstance().getCurrentSong();
        if (song == null) return;
        PlaylistManager.getInstance(this).toggleFavoriteSong(song);
        updateFavoriteButton(song);
    }

    private void updateFavoriteButton(Song song) {
        boolean favorite = PlaylistManager.getInstance(this).isFavoriteSong(song);
        btnFavorite.setImageResource(favorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
        btnFavorite.clearColorFilter();
        btnFavorite.setContentDescription(favorite ? cl.arichard.descargador.util.Tr.t("Quitar de favoritos") : cl.arichard.descargador.util.Tr.t("Agregar a favoritos"));
    }

    private void showPlayerMenu(View anchor) {
        Song song = PlayerManager.getInstance().getCurrentSong();
        if (song == null) return;
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Agregar a playlist")).setIcon(R.drawable.ic_playlist).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.addToPlaylist(this, song, () -> updateSongInfo(PlayerManager.getInstance().getCurrentSong())); return true;
        });
        if (song.isOnline()) menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Descargar")).setIcon(R.drawable.ic_download).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.download(this, song, () -> {}); return true;
        });
        if (!song.isOnline()) menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Eliminar del dispositivo")).setIcon(R.drawable.ic_delete).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.delete(this, song, this::finish); return true;
        });
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Cola de reproducci\u00f3n")).setIcon(R.drawable.ic_queue).setOnMenuItemClickListener(item -> {
            queueBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED); return true;
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
        return mode == PlayerManager.REPEAT_ONE ? cl.arichard.descargador.util.Tr.t("Repetir canción") :
                mode == PlayerManager.REPEAT_ALL ? cl.arichard.descargador.util.Tr.t("Repetir lista") : cl.arichard.descargador.util.Tr.t("Bucle desactivado");
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
        if (loading) txtCurrentTime.setText(cl.arichard.descargador.util.Tr.t("Cargando…"));
        runOnUiThread(() -> {
            cl.arichard.descargador.util.LoadingRingView ring = findViewById(R.id.playLoadingRing);
            if (ring == null) return;
            ring.setActive(loading);
            btnPlayPauseIcon.animate().alpha(loading ? 0f : 1f).setDuration(160).start();
        });
    }

    @Override
    public void onPlaybackError(String message) {
        txtCurrentTime.setText(cl.arichard.descargador.util.Tr.t("Error"));
        txtTotalTime.setText(cl.arichard.descargador.util.Tr.t("Reintentar ▶"));
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
