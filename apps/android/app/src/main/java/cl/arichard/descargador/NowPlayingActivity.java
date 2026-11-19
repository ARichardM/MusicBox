package cl.arichard.descargador;

import cl.arichard.descargador.util.AccentColors;

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

public class NowPlayingActivity extends AppearanceActivity implements PlayerManager.PlaybackListener {
    private static final java.util.concurrent.ExecutorService LYRICS_IO =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    public static final String EXTRA_VIDEO_ID = "extra_video_id";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_CHANNEL = "extra_channel";
    public static final String EXTRA_THUMBNAIL = "extra_thumbnail";

    private View nowPlayingRoot;
    private ImageView btnBack, btnFavorite;
    private TextView tabSelectorMp3, tabSelectorMp4, tabSelectorLyrics;
    private FrameLayout containerCoverArt, containerVideoPlayer, containerLyrics, videoHostLayout;
    private ImageView imgNowPlayingCover;
    private AnimatedArtwork animatedArtwork;
    private TextView txtTitle, txtArtist;
    private SeekBar seekBar;
    private TextView txtCurrentTime, txtTotalTime;

    // Lyrics UI & State
    private androidx.recyclerview.widget.RecyclerView recyclerLyrics;
    private View layoutLyricsLoading, layoutLyricsEmpty, lyricsHeaderRow;
    private TextView txtLyricsBadge, txtLyricsSource, btnLyricsFollow, txtLyricsEmptyTitle, txtLyricsEmptyDetail, btnLyricsRetry;
    private LyricsAdapter lyricsAdapter;
    private boolean isLyricsMode = false;
    private boolean userScrollingLyrics = false;
    private String lyricsSongKey = "";
    private int lyricsDurationSeconds = -1;
    private int lyricsRequestToken = 0;
    private java.util.concurrent.Future<?> lyricsRequest;
    private cl.arichard.descargador.util.LyricsFetcher.Result currentLyrics = cl.arichard.descargador.util.LyricsFetcher.Result.empty();
    private int activeLyricsIndex = -1;
    private final Runnable lyricsTick = this::syncLyrics;
    private boolean screenResumed;
    private final Runnable resumeLyricsFollowRunnable = () -> {
        userScrollingLyrics = false;
        if (btnLyricsFollow != null) btnLyricsFollow.setVisibility(View.GONE);
        scrollToActiveLyric(true);
    };

    private ImageView btnShuffle, btnPrevious, btnPlayPauseIcon, btnNext, btnRepeat;
    /** Mientras carga, el anillo ocupa el lugar del icono de reproducir o pausar. */
    private boolean playbackLoading;
    private FrameLayout btnPlayPauseCircle;

    // Download Banner
    private LinearLayout playerDownloadCard;
    private TextView txtPlayerDownloadStatus, txtPlayerDownloadEta;
    private ProgressBar playerDownloadProgress;

    private View nowPlayingCoordinator;
    private com.google.android.material.bottomsheet.BottomSheetBehavior<View> queueBehavior;
    private cl.arichard.descargador.player.QueueSheet queuePanel;
    private final cl.arichard.descargador.util.SongDeleter.SystemPrompt deletePrompt = cl.arichard.descargador.util.SongDeleter.prompter(this);

    private View playerTopBar, artworkArea, discContainer, videoFadeTop, videoFadeBottom, coverFadeTop, coverFadeBottom, playingFromBlock;
    private androidx.cardview.widget.CardView videoCardView;
    /** Pantalla completa del video (como en YouTube Music): llena la parte alta y se funde con el fondo. */
    private boolean immersive;
    private float immersiveProgress;
    private android.animation.ValueAnimator immersiveAnimator;

    private boolean isMp3Mode = true;
    private boolean isUserSeeking = false;
    private boolean isDownloading = false;

    private String onlineVideoId;
    private String onlineUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_now_playing);
        // La cola se mide desde el borde de la pantalla: suma la barra de gestos para que asome entera sobre ella.
        cl.arichard.descargador.util.SystemBars.fit(this, bars -> {
            systemBars = bars;
            applyQueueInset();
        });

        initViews();
        setupClickListeners();
        cl.arichard.descargador.util.SafeTap.installButtons(nowPlayingRoot);
        cl.arichard.descargador.util.SafeTap.install(btnPlayPauseCircle);
        setupPlayerGestures();
        setupQueueSheet();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (immersive) { setVideoFullscreen(false); return; }
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
        tabSelectorLyrics = findViewById(R.id.tabSelectorLyrics);

        containerCoverArt = findViewById(R.id.containerCoverArt);
        containerVideoPlayer = findViewById(R.id.containerVideoPlayer);
        containerLyrics = findViewById(R.id.containerLyrics);
        videoHostLayout = findViewById(R.id.videoHostLayout);
        imgNowPlayingCover = findViewById(R.id.imgNowPlayingCover);
        playerTopBar = findViewById(R.id.playerTopBar);
        artworkArea = findViewById(R.id.playerArtworkArea);
        discContainer = findViewById(R.id.discContainer);
        animatedArtwork = new AnimatedArtwork((FrameLayout) discContainer);
        coverFadeTop = addCoverFade(R.drawable.bg_video_fade_top, 120, android.view.Gravity.TOP);
        coverFadeBottom = addCoverFade(R.drawable.bg_video_fade_bottom, 190, android.view.Gravity.BOTTOM);
        videoCardView = findViewById(R.id.videoCardView);
        videoFadeTop = findViewById(R.id.videoFadeTop);
        videoFadeBottom = findViewById(R.id.videoFadeBottom);
        playingFromBlock = findViewById(R.id.playingFromBlock);
        // La portada y el video se acomodan al espacio libre cada vez que este cambia (rotación, cola, barras).
        artworkArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutArtwork());
        videoCardView.setOnClickListener(v -> setVideoFullscreen(!immersive));
        discContainer.setOnClickListener(v -> { if (isMp3Mode && !isLyricsMode) setVideoFullscreen(!immersive); });

        lyricsHeaderRow = findViewById(R.id.lyricsHeaderRow);
        txtLyricsBadge = findViewById(R.id.txtLyricsBadge);
        txtLyricsSource = findViewById(R.id.txtLyricsSource);
        btnLyricsFollow = findViewById(R.id.btnLyricsFollow);
        recyclerLyrics = findViewById(R.id.recyclerLyrics);
        layoutLyricsLoading = findViewById(R.id.layoutLyricsLoading);
        layoutLyricsEmpty = findViewById(R.id.layoutLyricsEmpty);
        txtLyricsEmptyTitle = findViewById(R.id.txtLyricsEmptyTitle);
        txtLyricsEmptyDetail = findViewById(R.id.txtLyricsEmptyDetail);
        btnLyricsRetry = findViewById(R.id.btnLyricsRetry);
        setupLyricsView();

        txtTitle = findViewById(R.id.txtNowPlayingTitle);
        txtArtist = findViewById(R.id.txtNowPlayingArtist);
        txtCurrentTime = findViewById(R.id.txtCurrentTime);
        txtTotalTime = findViewById(R.id.txtTotalTime);
        seekBar = findViewById(R.id.seekBar);
        waveBinder = new cl.arichard.descargador.util.WaveBinder(this, (cl.arichard.descargador.util.WaveSeekBar) seekBar);

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

        PlayerManager openingPlayer = PlayerManager.getInstance();
        if (openingPlayer.hasVideo() && !openingPlayer.isOnlineTrack()) isMp3Mode = false;
        tabSelectorMp3.setOnClickListener(v -> { isLyricsMode = false; setMediaMode(true); });
        tabSelectorMp4.setOnClickListener(v -> { isLyricsMode = false; setMediaMode(false); });
        if (tabSelectorLyrics != null) {
            tabSelectorLyrics.setText(cl.arichard.descargador.util.Tr.t("Letra"));
            tabSelectorLyrics.setOnClickListener(v -> toggleLyricsMode());
        }

        btnPlayPauseCircle.setOnClickListener(v -> PlayerManager.getInstance().togglePlayPause());
        btnNext.setOnClickListener(v -> PlayerManager.getInstance().playNext());
        btnPrevious.setOnClickListener(v -> PlayerManager.getInstance().playPrevious());

        btnShuffle.setOnClickListener(v -> {
            boolean active = PlayerManager.getInstance().toggleShuffle();
            updateShuffleButton(active);
            cl.arichard.descargador.util.SafeTap.pop(btnShuffle, active);
        });

        btnRepeat.setOnClickListener(v -> {
            int mode = PlayerManager.getInstance().toggleRepeat();
            updateRepeatButton(mode);
            cl.arichard.descargador.util.SafeTap.pop(btnRepeat, mode != PlayerManager.REPEAT_OFF);
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
        PlayerManager player = PlayerManager.getInstance();
        mp3 = mp3 || !player.canShowVideo();
        isMp3Mode = mp3;
        if (mp3) {
            player.detachOnlineWebView();
            player.setVideoMode(false);
        } else {
            // La superficie se crea antes de pedir el flujo con imagen, para que el video aparezca al empezar.
            player.attachOnlineWebView(videoHostLayout);
            player.setVideoMode(true);
        }
        int activeText = AccentColors.getColor(this, R.color.chip_active_text);
        int inactiveText = AccentColors.getColor(this, R.color.text_secondary);

        if (isLyricsMode) {
            tabSelectorMp3.setBackground(null);
            tabSelectorMp3.setTextColor(inactiveText);
            tabSelectorMp4.setBackground(null);
            tabSelectorMp4.setTextColor(inactiveText);
            if (tabSelectorLyrics != null) {
                tabSelectorLyrics.setBackgroundResource(R.drawable.bg_chip_active);
                tabSelectorLyrics.setTextColor(activeText);
                tabSelectorLyrics.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            containerCoverArt.setVisibility(View.GONE);
            containerVideoPlayer.setVisibility(View.INVISIBLE);
            if (containerLyrics != null) containerLyrics.setVisibility(View.VISIBLE);
            setImmersiveNow(false);
        } else if (isMp3Mode) {
            tabSelectorMp3.setBackgroundResource(R.drawable.bg_chip_active);
            tabSelectorMp3.setTextColor(activeText);
            tabSelectorMp4.setBackground(null);
            tabSelectorMp4.setTextColor(inactiveText);
            if (tabSelectorLyrics != null) {
                tabSelectorLyrics.setBackground(null);
                tabSelectorLyrics.setTextColor(inactiveText);
                tabSelectorLyrics.setTypeface(null, android.graphics.Typeface.NORMAL);
            }

            containerCoverArt.setVisibility(View.VISIBLE);
            containerVideoPlayer.setVisibility(View.INVISIBLE); // Keep measured and rendering
            if (containerLyrics != null) containerLyrics.setVisibility(View.GONE);
            setImmersiveNow(savedZoom(true));
        } else {
            tabSelectorMp4.setBackgroundResource(R.drawable.bg_chip_active);
            tabSelectorMp4.setTextColor(activeText);
            tabSelectorMp3.setBackground(null);
            tabSelectorMp3.setTextColor(inactiveText);
            if (tabSelectorLyrics != null) {
                tabSelectorLyrics.setBackground(null);
                tabSelectorLyrics.setTextColor(inactiveText);
                tabSelectorLyrics.setTypeface(null, android.graphics.Typeface.NORMAL);
            }

            containerCoverArt.setVisibility(View.GONE);
            containerVideoPlayer.setVisibility(View.VISIBLE);
            if (containerLyrics != null) containerLyrics.setVisibility(View.GONE);
            if (immersive != savedZoom(false)) setImmersiveNow(savedZoom(false));
            artworkArea.post(this::layoutArtwork);
        }
        updateAnimatedArtwork(player.getCurrentSong());
    }

    private void updateAnimatedArtwork(Song song) {
        if (screenResumed && isMp3Mode && !isLyricsMode) {
            animatedArtwork.show(song);
        } else {
            animatedArtwork.clear();
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Portada en un marco cuadrado y video con su proporción real, según el espacio libre. */
    private void layoutArtwork() {
        int width = artworkArea.getWidth(), height = artworkArea.getHeight();
        if (width <= 0 || height <= 0) return;
        applyVideoFrame(immersiveProgress);
    }

    /**
     * Coloca el recuadro del video entre dos estados: [progress] 0 = proporción real del video a todo el ancho, centrado;
     * 1 = pantalla completa, desde el borde superior hasta el título, con sombra arriba y fundido abajo.
     */
    private void applyVideoFrame(float progress) {
        int areaWidth = artworkArea.getWidth(), areaHeight = artworkArea.getHeight();
        if (areaWidth <= 0 || areaHeight <= 0 || txtTitle.getHeight() <= 0) return;
        PlayerManager player = PlayerManager.getInstance();
        float aspect = isMp3Mode ? 1f : player.getVideoAspect();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int pad = dp(20);
        // Reposo: a todo el ancho de la pantalla (el video se pega a los bordes), sin pasarse del alto libre.
        int width0 = isMp3Mode ? Math.min(areaWidth, areaHeight) : Math.min(areaWidth + 2 * pad, Math.round(areaHeight * aspect));
        int height0 = Math.round(width0 / aspect);
        int left0 = (areaWidth - width0) / 2, top0 = (areaHeight - height0) / 2;
        // Pantalla completa: se mide en pantalla y se pasa a coordenadas del contenedor.
        int[] container = new int[2], title = new int[2];
        (isMp3Mode ? containerCoverArt : containerVideoPlayer).getLocationInWindow(container);
        txtTitle.getLocationInWindow(title);
        int left1 = -container[0], top1 = -container[1];
        int width1 = screenWidth, height1 = Math.max(areaHeight, title[1]);
        float t = Math.max(0f, Math.min(1f, progress));
        androidx.cardview.widget.CardView frame = isMp3Mode ? (androidx.cardview.widget.CardView) discContainer : videoCardView;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.leftMargin = Math.round(left0 + (left1 - left0) * t);
        lp.topMargin = Math.round(top0 + (top1 - top0) * t);
        lp.width = Math.round(width0 + (width1 - width0) * t);
        lp.height = Math.round(height0 + (height1 - height0) * t);
        frame.setLayoutParams(lp);
        // Un video que no llena el ancho (p. ej. cuadrado) conserva las esquinas redondeadas en reposo.
        float radius0 = isMp3Mode ? dp(26) : width0 >= areaWidth + 2 * pad - 2 ? 0f : dp(20);
        frame.setRadius(radius0 * (1f - t));
        if (isMp3Mode) {
            // El recuadro mide siempre lo ancho de la pantalla; el lado corto de la imagen (el cuadrado central de una portada, aunque
            // venga dentro de una miniatura 16:9) se ajusta a ese ancho y queda centrado: no se recorta el arte al ampliar.
            android.graphics.drawable.Drawable art = imgNowPlayingCover.getDrawable();
            int imageHeight = lp.height; // sin imagen conocida la portada llena el marco
            if (art != null && art.getIntrinsicWidth() > 0 && art.getIntrinsicHeight() > 0) {
                float dw = art.getIntrinsicWidth(), dh = art.getIntrinsicHeight();
                float scale = lp.width / Math.min(dw, dh);
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.setScale(scale, scale);
                matrix.postTranslate((lp.width - dw * scale) / 2f, (lp.height - dh * scale) / 2f);
                imgNowPlayingCover.setScaleType(ImageView.ScaleType.MATRIX);
                imgNowPlayingCover.setImageMatrix(matrix);
                imageHeight = Math.round(dh * scale);
            } else imgNowPlayingCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int shade = Math.round(0x1C * (1f - t));
            frame.setCardBackgroundColor(0xFF000000 | (shade << 16) | (shade << 8) | shade);
            animatedArtwork.fit(t > .001f, lp.width);
            // Los degradados se apoyan en los bordes de la imagen, no en los del marco (más alto): así el arte se funde con el
            // fondo en vez de quedar cortado de golpe sobre una barra negra.
            int[] shown = cl.arichard.descargador.util.CoverFadeBounds.visible(lp.height, imageHeight);
            placeCoverFade(coverFadeTop, shown[0]);
            placeCoverFade(coverFadeBottom, cl.arichard.descargador.util.CoverFadeBounds.bottomFadeTop(
                    shown[0], shown[1], coverFadeBottom.getLayoutParams().height));
        }
        (isMp3Mode ? coverFadeTop : videoFadeTop).setAlpha(t);
        (isMp3Mode ? coverFadeBottom : videoFadeBottom).setAlpha(t);
        playingFromBlock.setAlpha(1f - t);
        // La barra superior queda por encima del video cuando este sube hasta el borde.
        playerTopBar.setTranslationZ(t > 0f ? dp(24) : 0f);
    }

    /** Coloca un degradado de la portada a [top] píxeles del borde superior del marco. */
    private void placeCoverFade(View fade, int top) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) fade.getLayoutParams();
        if (params.gravity == android.view.Gravity.TOP && params.topMargin == top) return;
        params.gravity = android.view.Gravity.TOP;
        params.topMargin = top;
        fade.setLayoutParams(params);
    }

    private boolean savedZoom(boolean mp3) {
        return getSharedPreferences("player", MODE_PRIVATE).getBoolean(mp3 ? "zoom_mp3" : "zoom_mp4", false);
    }

    private void setVideoFullscreen(boolean on) {
        if (on == immersive || isLyricsMode) return;
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean(isMp3Mode ? "zoom_mp3" : "zoom_mp4", on).apply();
        immersive = on;
        if (immersiveAnimator != null) immersiveAnimator.cancel();
        PlayerManager player = PlayerManager.getInstance();
        if (on && !isMp3Mode) player.setVideoFill(true);
        immersiveAnimator = android.animation.ValueAnimator.ofFloat(immersiveProgress, on ? 1f : 0f);
        immersiveAnimator.setDuration(320);
        immersiveAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        immersiveAnimator.addUpdateListener(a -> { immersiveProgress = (float) a.getAnimatedValue(); applyVideoFrame(immersiveProgress); });
        immersiveAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled && !immersive) PlayerManager.getInstance().setVideoFill(false);
            }
        });
        immersiveAnimator.start();
    }

    private View addCoverFade(int background, int height, int gravity) {
        View fade = new View(this);
        fade.setBackgroundResource(background);
        fade.setAlpha(0f);
        ((FrameLayout) discContainer).addView(fade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(height), gravity));
        return fade;
    }

    /** Sale de la pantalla completa sin animar (al volver a MP3 o al cerrar). */
    private void setImmersiveNow(boolean on) {
        if (immersiveAnimator != null) immersiveAnimator.cancel();
        immersive = on;
        immersiveProgress = on ? 1f : 0f;
        PlayerManager.getInstance().setVideoFill(on && !isMp3Mode);
        if (artworkArea != null) applyVideoFrame(immersiveProgress);
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
    private androidx.core.graphics.Insets systemBars = androidx.core.graphics.Insets.NONE;

    private int queuePeek() { return Math.round(94 * getResources().getDisplayMetrics().density); }
    private int[] playerPadding;

    /**
     * El reproductor deja sitio a las barras del sistema y la cola llega hasta el borde inferior, asomando entera
     * sobre la barra de gestos. Se aplica a cada vista y no al contenedor: la hoja mide su posición desde él.
     */
    private void applyQueueInset() {
        View player = findViewById(R.id.nowPlayingRoot);
        View sheet = findViewById(R.id.queueSheet);
        if (player != null) {
            if (playerPadding == null) playerPadding = new int[] {player.getPaddingLeft(), player.getPaddingTop(),
                    player.getPaddingRight(), player.getPaddingBottom()};
            player.setPadding(playerPadding[0] + systemBars.left, playerPadding[1] + systemBars.top,
                    playerPadding[2] + systemBars.right, playerPadding[3] + systemBars.bottom);
        }
        if (sheet != null) sheet.setPadding(systemBars.left, sheet.getPaddingTop(), systemBars.right, systemBars.bottom);
        if (queueBehavior != null) {
            queueBehavior.setPeekHeight(queuePeek() + systemBars.bottom);
            queueBehavior.setExpandedOffset(Math.round(88 * getResources().getDisplayMetrics().density) + systemBars.top);
        }
    }

    private void setupQueueSheet() {
        android.widget.FrameLayout sheet = findViewById(R.id.queueSheet);
        float density = getResources().getDisplayMetrics().density;
        queueBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
        applyQueueInset();
        queueBehavior.setHideable(false);
        // A translated match-parent sheet would extend below the screen by its expanded offset.
        queueBehavior.setFitToContents(false);
        sheet.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            android.view.View parent = (android.view.View) sheet.getParent();
            int available = parent.getHeight() - queueBehavior.getExpandedOffset();
            if (available > 0 && sheet.getLayoutParams().height != available) {
                android.view.ViewGroup.LayoutParams params = sheet.getLayoutParams();
                params.height = available;
                sheet.setLayoutParams(params);
            }
        });
        queueBehavior.setExpandedOffset(Math.round(88 * density) + systemBars.top);
        queueBehavior.setHalfExpandedRatio(.5f);
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

    private boolean outsideQueueGesture, outsideQueueTap;
    private float outsideQueueX, outsideQueueY;

    /**
     * With the queue pulled up, a tap on the dimmed player above it collapses the sheet with its own settle
     * animation. The whole gesture is consumed so nothing underneath (buttons, swipe to dismiss) reacts to it.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        android.view.View queue = findViewById(R.id.queueSheet);
        // También se puede cerrar mientras se está asentando o quedó a media altura.
        boolean expanded = queueBehavior != null && queue != null
                && queue.getTop() < nowPlayingCoordinator.getHeight() - queueBehavior.getPeekHeight() - 1;
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                if (expanded) {
                    int[] at = new int[2];
                    queue.getLocationOnScreen(at);
                    outsideQueueGesture = event.getRawY() < at[1];
                    outsideQueueTap = outsideQueueGesture;
                    outsideQueueX = event.getRawX(); outsideQueueY = event.getRawY();
                } else {
                    outsideQueueGesture = false;
                }
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (outsideQueueGesture && Math.hypot(event.getRawX() - outsideQueueX, event.getRawY() - outsideQueueY)
                        > android.view.ViewConfiguration.get(this).getScaledTouchSlop()) outsideQueueTap = false;
                break;
            case android.view.MotionEvent.ACTION_UP:
                if (outsideQueueGesture) {
                    if (outsideQueueTap && expanded) queueBehavior.setState(
                            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED);
                    outsideQueueGesture = false; outsideQueueTap = false;
                    return true;
                }
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                if (outsideQueueGesture) { outsideQueueGesture = false; outsideQueueTap = false; return true; }
                break;
            default:
                break;
        }
        if (outsideQueueGesture) return true;
        return super.dispatchTouchEvent(event);
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
        screenResumed = true;
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
        lyricsRequestToken++;
        if (lyricsRequest != null) lyricsRequest.cancel(true);
        if (recyclerLyrics != null) recyclerLyrics.removeCallbacks(resumeLyricsFollowRunnable);
        if (queuePanel != null) queuePanel.detach();
        // Al salir de la pantalla las próximas canciones vuelven a pedir solo audio (se ahorran datos).
        if (isFinishing()) PlayerManager.getInstance().leaveVideoMode();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        screenResumed = false;
        animatedArtwork.clear();
        recyclerLyrics.removeCallbacks(lyricsTick);
        PlayerManager.getInstance().savePosition();
        super.onPause();
        PlayerManager.getInstance().removeListener(this);
        PlayerManager.getInstance().detachOnlineWebView();
    }

    private void updateSongInfo(Song song) {
        if (song == null) { finish(); return; }
        onlineVideoId = song.getVideoId();
        onlineUrl = song.isOnline() ? "https://www.youtube.com/watch?v=" + onlineVideoId : null;
        PlayerManager.getInstance().checkVideoAvailability();
        updateVideoAvailability();
        txtTitle.setText(song.getTitle());
        txtArtist.setText(song.getArtist());
        if (!followingOutgoing) waveBinder.bind(song);
        updatePlayingFrom();

        updateFavoriteButton(song);

        if (imgNowPlayingCover != null) {
            CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), imgNowPlayingCover);
            // La portada llega de forma asíncrona: al llegar se vuelve a decidir si se ve entera o recortada.
            imgNowPlayingCover.postDelayed(() -> applyVideoFrame(immersiveProgress), 350);
            imgNowPlayingCover.postDelayed(() -> applyVideoFrame(immersiveProgress), 1200);
        }
        updateAnimatedArtwork(song);

        loadLyricsForSong(song, false);
    }

    private static String songKey(Song song) {
        if (song == null) return "";
        return (song.getVideoId() == null ? "" : song.getVideoId()) + "|"
                + (song.getPath() == null ? "" : song.getPath()) + "|"
                + (song.getTitle() == null ? "" : song.getTitle());
    }

    private void setupLyricsView() {
        if (recyclerLyrics == null) return;
        lyricsAdapter = new LyricsAdapter(line -> {
            if (line.timeMs >= 0) {
                userScrollingLyrics = false;
                if (btnLyricsFollow != null) btnLyricsFollow.setVisibility(View.GONE);
                PlayerManager.getInstance().seekTo((int) line.timeMs);
            }
        });
        recyclerLyrics.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        recyclerLyrics.setItemAnimator(null);
        recyclerLyrics.setAdapter(lyricsAdapter);
        recyclerLyrics.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(androidx.recyclerview.widget.RecyclerView rv, int newState) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING
                        && currentLyrics != null && currentLyrics.synced) {
                    userScrollingLyrics = true;
                    if (btnLyricsFollow != null) btnLyricsFollow.setVisibility(View.VISIBLE);
                    rv.removeCallbacks(resumeLyricsFollowRunnable);
                    rv.postDelayed(resumeLyricsFollowRunnable, 4500L);
                }
            }
        });
        if (btnLyricsFollow != null) {
            btnLyricsFollow.setText(cl.arichard.descargador.util.Tr.t("Seguir"));
            btnLyricsFollow.setOnClickListener(v -> {
                recyclerLyrics.removeCallbacks(resumeLyricsFollowRunnable);
                userScrollingLyrics = false;
                btnLyricsFollow.setVisibility(View.GONE);
                scrollToActiveLyric(true);
            });
        }
        if (btnLyricsRetry != null) {
            btnLyricsRetry.setText(cl.arichard.descargador.util.Tr.t("Reintentar"));
            btnLyricsRetry.setOnClickListener(v -> loadLyricsForSong(PlayerManager.getInstance().getCurrentSong(), true));
        }
    }

    private void toggleLyricsMode() {
        if (!isLyricsMode && currentLyrics.lines.isEmpty()) return;
        isLyricsMode = !isLyricsMode;
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("lyrics_shown",isLyricsMode).apply();
        setMediaMode(isMp3Mode);
        syncLyrics();
        if (isLyricsMode) recyclerLyrics.post(() -> scrollToActiveLyric(false));
    }

    private void loadLyricsForSong(Song song, boolean force) {
        if (song == null || recyclerLyrics == null) return;
        final String key = songKey(song);
        int playbackDuration = PlayerManager.getInstance().getDuration();
        int duration = (int) Math.round((playbackDuration > 0 ? playbackDuration : song.getDuration()) / 1000.0);
        if (!force && key.equals(lyricsSongKey) && Math.abs(duration - lyricsDurationSeconds) <= 1
                && (duration > 0) == (lyricsDurationSeconds > 0)) return;
        if (force) cl.arichard.descargador.util.LyricsFetcher.clearCache();
        final int token = ++lyricsRequestToken;
        lyricsSongKey = key;
        lyricsDurationSeconds = duration;
        currentLyrics = cl.arichard.descargador.util.LyricsFetcher.Result.empty();
        activeLyricsIndex = -1;
        recyclerLyrics.removeCallbacks(lyricsTick);
        recyclerLyrics.removeCallbacks(resumeLyricsFollowRunnable);
        lyricsAdapter.setData(java.util.Collections.emptyList(), false, -1);
        updateModeSelectors();
        userScrollingLyrics = false;
        if (btnLyricsFollow != null) btnLyricsFollow.setVisibility(View.GONE);
        if (txtLyricsBadge != null) txtLyricsBadge.setVisibility(View.GONE);
        if (txtLyricsSource != null) txtLyricsSource.setText("");
        recyclerLyrics.setVisibility(View.GONE);
        layoutLyricsEmpty.setVisibility(View.GONE);
        layoutLyricsLoading.setVisibility(View.VISIBLE);

        if (lyricsRequest != null) lyricsRequest.cancel(true);
        lyricsRequest = LYRICS_IO.submit(() -> {
            cl.arichard.descargador.util.LyricsFetcher.Result result = cl.arichard.descargador.util.LyricsFetcher.find(
                    song.getTitle(), song.getArtist(), song.getAlbum(), duration, song.getVideoId(), song.getPath());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || token != lyricsRequestToken
                        || !key.equals(songKey(PlayerManager.getInstance().getCurrentSong()))) return;
                currentLyrics = result;
                applyLyricsResult(currentLyrics);
                updateModeSelectors();
                if (isLyricsMode && currentLyrics.lines.isEmpty()) {
                    isLyricsMode = false;
                    setMediaMode(isMp3Mode);
                }
                syncLyrics();
            });
        });
    }

    private void applyLyricsResult(cl.arichard.descargador.util.LyricsFetcher.Result result) {
        if (recyclerLyrics == null) return;
        layoutLyricsLoading.setVisibility(View.GONE);
        if (result == null || (result.lines.isEmpty() && !result.instrumental)) {
            recyclerLyrics.setVisibility(View.GONE);
            layoutLyricsEmpty.setVisibility(View.VISIBLE);
            txtLyricsEmptyTitle.setText(cl.arichard.descargador.util.Tr.t("Sin letra disponible"));
            txtLyricsEmptyDetail.setText(cl.arichard.descargador.util.Tr.t("No encontramos la letra de esta canción."));
            btnLyricsRetry.setVisibility(View.VISIBLE);
            txtLyricsBadge.setVisibility(View.GONE);
            txtLyricsSource.setText("");
            return;
        }
        if (result.instrumental && result.lines.isEmpty()) {
            recyclerLyrics.setVisibility(View.GONE);
            layoutLyricsEmpty.setVisibility(View.VISIBLE);
            txtLyricsEmptyTitle.setText(cl.arichard.descargador.util.Tr.t("Pista instrumental"));
            txtLyricsEmptyDetail.setText(cl.arichard.descargador.util.Tr.t("Esta canción no tiene letra cantada."));
            btnLyricsRetry.setVisibility(View.GONE);
            txtLyricsBadge.setVisibility(View.GONE);
            txtLyricsSource.setText(cl.arichard.descargador.util.Tr.t(result.source));
            return;
        }
        layoutLyricsEmpty.setVisibility(View.GONE);
        recyclerLyrics.setVisibility(View.VISIBLE);
        txtLyricsBadge.setVisibility(View.VISIBLE);
        txtLyricsBadge.setText(cl.arichard.descargador.util.Tr.t(result.synced ? "Sincronizada" : "Texto"));
        txtLyricsSource.setText(cl.arichard.descargador.util.Tr.t(result.source));
        int pos = PlayerManager.getInstance().getCurrentPosition();
        activeLyricsIndex = result.synced ? cl.arichard.descargador.util.LyricsFetcher.activeLineIndex(result.lines, pos) : -1;
        lyricsAdapter.setData(result.lines, result.synced, activeLyricsIndex);
        if (result.synced && activeLyricsIndex >= 0) {
            scrollToActiveLyric(false);
        } else {
            recyclerLyrics.scrollToPosition(0);
        }
    }

    private void updateActiveLyricsLine(int posMs) {
        if (currentLyrics == null || !currentLyrics.synced || currentLyrics.lines.isEmpty() || lyricsAdapter == null) return;
        int next = cl.arichard.descargador.util.LyricsFetcher.activeLineIndex(currentLyrics.lines, posMs);
        if (next != activeLyricsIndex) {
            int prev = activeLyricsIndex;
            activeLyricsIndex = next;
            lyricsAdapter.setActiveIndex(next, prev);
            if (isLyricsMode && !userScrollingLyrics && next >= 0) {
                scrollToActiveLyric(true);
            }
        }
    }

    private void syncLyrics() {
        recyclerLyrics.removeCallbacks(lyricsTick);
        if (!screenResumed || !isLyricsMode || !currentLyrics.synced) return;
        PlayerManager player = PlayerManager.getInstance();
        int position = player.getCurrentPosition();
        updateActiveLyricsLine(position);
        int nextLine = activeLyricsIndex + 1;
        if (player.isPlaying() && nextLine < currentLyrics.lines.size()) {
            long untilNext = currentLyrics.lines.get(nextLine).timeMs - position;
            recyclerLyrics.postDelayed(lyricsTick, Math.max(16L, Math.min(100L, untilNext)));
        }
    }

    private void scrollToActiveLyric(boolean smooth) {
        if (recyclerLyrics == null || activeLyricsIndex < 0) return;
        androidx.recyclerview.widget.RecyclerView.LayoutManager lm = recyclerLyrics.getLayoutManager();
        if (!(lm instanceof androidx.recyclerview.widget.LinearLayoutManager)) return;
        androidx.recyclerview.widget.LinearLayoutManager linear = (androidx.recyclerview.widget.LinearLayoutManager) lm;
        int offset = Math.max(0, recyclerLyrics.getHeight() / 3);
        if (!smooth) {
            linear.scrollToPositionWithOffset(activeLyricsIndex, offset);
            return;
        }
        androidx.recyclerview.widget.LinearSmoothScroller scroller =
                new androidx.recyclerview.widget.LinearSmoothScroller(this) {
                    @Override
                    public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                        return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
                    }

                    @Override
                    protected float calculateSpeedPerPixel(android.util.DisplayMetrics displayMetrics) {
                        return 75f / displayMetrics.densityDpi;
                    }
                };
        scroller.setTargetPosition(activeLyricsIndex);
        linear.startSmoothScroll(scroller);
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

    /** Confirma el cambio de crossfade con un aviso breve sobre el reproductor, ya que el menú se cierra al tocar. */
    private void announceCrossfade(int seconds) {
        TextView note = new TextView(this);
        note.setText(cl.arichard.descargador.util.Tr.t(seconds > 0 ? "Crossfade activado" : "Crossfade desactivado") + (seconds > 0 ? " · " + seconds + " s" : ""));
        note.setTextColor(AccentColors.getColor(this, R.color.chip_active_text));
        note.setTextSize(14);
        note.setTypeface(null, android.graphics.Typeface.BOLD);
        note.setBackgroundResource(R.drawable.bg_chip_active);
        note.setPadding(dp(18), dp(10), dp(18), dp(10));
        note.setElevation(dp(12));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(96);
        android.view.ViewGroup content = (android.view.ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
        content.addView(note, lp);
        boolean still = android.provider.Settings.Global.getFloat(getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        note.setAlpha(still ? 1f : 0f); note.setTranslationY(still ? 0f : -dp(12));
        note.animate().alpha(1f).translationY(0).setDuration(still ? 0 : 200).withEndAction(() ->
                note.animate().alpha(0f).setStartDelay(1400).setDuration(still ? 0 : 260).withEndAction(() -> content.removeView(note)).start()).start();
        note.setContentDescription(note.getText());
        note.announceForAccessibility(note.getText());
    }

    private void showPlayerMenu(View anchor) {
        Song song = PlayerManager.getInstance().getCurrentSong();
        if (song == null) return;
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        PlayerManager player = PlayerManager.getInstance();
        menu.getMenu().add("Crossfade").setIcon(R.drawable.ic_queue).setCheckable(true)
                .setChecked(player.getCrossfadeSeconds() > 0).setOnMenuItemClickListener(item -> {
                    player.setCrossfadeEnabled(player.getCrossfadeSeconds() == 0);
                    int seconds = player.getCrossfadeSeconds();
                    announceCrossfade(seconds);
                    waveBinder.reset();
                    updateCrossfadeZone(player.getDuration());
                    waveBinder.bind(player.getCurrentSong());
                    return true;
                });
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Agregar a playlist")).setIcon(R.drawable.ic_playlist).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.addToPlaylist(this, song, () -> updateSongInfo(PlayerManager.getInstance().getCurrentSong())); return true;
        });
        if (song.isOnline()) menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Descargar")).setIcon(R.drawable.ic_download).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.download(this, song, () -> {}); return true;
        });
        if (!song.isOnline()) menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Eliminar del dispositivo")).setIcon(R.drawable.ic_delete).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.delete(this, song, deletePrompt, this::finish); return true;
        });
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Ver alternativas")).setIcon(R.drawable.ic_search).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongAlternativesSheet.show(this, song, () -> updateSongInfo(PlayerManager.getInstance().getCurrentSong())); return true;
        });
        if (!currentLyrics.lines.isEmpty()) menu.getMenu().add(cl.arichard.descargador.util.Tr.t(isLyricsMode ? "Ocultar letra" : "Ver letra")).setIcon(R.drawable.ic_info).setOnMenuItemClickListener(item -> {
            toggleLyricsMode(); return true;
        });
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Información de la canción")).setIcon(R.drawable.ic_info).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongInfo.show(this, song); return true;
        });
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Cola de reproducci\u00f3n")).setIcon(R.drawable.ic_queue).setOnMenuItemClickListener(item -> {
            queueBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED); return true;
        });
        for (int i = 0; i < menu.getMenu().size(); i++) {
            android.graphics.drawable.Drawable icon = menu.getMenu().getItem(i).getIcon();
            if (icon != null) icon.mutate().setTint(AccentColors.getColor(this, R.color.text_primary));
        }
        menu.show();
    }
    private void updateVideoAvailability() {
        PlayerManager player = PlayerManager.getInstance();
        boolean available = player.canShowVideo();
        updateModeSelectors();
        tabSelectorMp3.removeCallbacks(leaveVideoRunnable);
        if (!available && !isMp3Mode && !player.isVideoMode()) tabSelectorMp3.postDelayed(leaveVideoRunnable, CHIP_HIDE_DELAY_MS);
    }
    private final Runnable leaveVideoRunnable = () -> {
        PlayerManager player = PlayerManager.getInstance();
        if (player.canShowVideo() || isMp3Mode || player.isVideoMode()) return;
        setMediaMode(true);
        Toast.makeText(this, cl.arichard.descargador.util.Tr.t("Esta canción no tiene video disponible"), Toast.LENGTH_SHORT).show();
    };

    private static final long CHIP_HIDE_DELAY_MS = 1800;
    private final Runnable hideChipsRunnable = this::applyModeSelectors;

    /** Los chips aparecen al instante con un fundido, pero solo se ocultan si la ausencia se mantiene: así no parpadean al cambiar de canción. */
    private void updateModeSelectors() {
        boolean video = PlayerManager.getInstance().canShowVideo();
        boolean lyrics = !currentLyrics.lines.isEmpty();
        boolean growing = (video && tabSelectorMp4.getVisibility() != View.VISIBLE)
                || (lyrics && tabSelectorLyrics.getVisibility() != View.VISIBLE);
        boolean shrinking = (!video && tabSelectorMp4.getVisibility() == View.VISIBLE)
                || (!lyrics && tabSelectorLyrics.getVisibility() == View.VISIBLE);
        tabSelectorMp4.removeCallbacks(hideChipsRunnable);
        if (shrinking && !growing) tabSelectorMp4.postDelayed(hideChipsRunnable, CHIP_HIDE_DELAY_MS);
        else applyModeSelectors();
    }

    private void applyModeSelectors() {
        boolean video = PlayerManager.getInstance().canShowVideo();
        boolean lyrics = !currentLyrics.lines.isEmpty();
        View pill = findViewById(R.id.playerSelectorPill);
        boolean reveal = (video || lyrics) && pill.getVisibility() != View.VISIBLE;
        revealChip(tabSelectorMp4, video);
        revealChip(tabSelectorLyrics, lyrics);
        pill.setVisibility(video || lyrics ? View.VISIBLE : View.GONE);
        if (reveal) fadeIn(pill);
    }

    private void revealChip(View chip, boolean show) {
        boolean wasVisible = chip.getVisibility() == View.VISIBLE;
        if (show == wasVisible) return;
        chip.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) fadeIn(chip);
    }

    private void fadeIn(View view) {
        view.animate().cancel();
        boolean still = android.provider.Settings.Global.getFloat(getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        if (still) { view.setAlpha(1f); view.setScaleX(1f); view.setScaleY(1f); return; }
        view.setAlpha(0f); view.setScaleX(.92f); view.setScaleY(.92f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }
    @Override public void onVideoAvailabilityChanged(boolean available) { updateVideoAvailability(); layoutArtwork(); }

    private void updatePlaybackState(boolean isPlaying) {
        ((cl.arichard.descargador.util.WaveSeekBar) seekBar).setPlaying(isPlaying);
        btnPlayPauseIcon.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        btnPlayPauseIcon.animate().cancel();
        btnPlayPauseIcon.setScaleX(.82f); btnPlayPauseIcon.setScaleY(.82f);
        // Cancelar la animación también corta un desvanecimiento a medias: la opacidad final se fija aquí.
        btnPlayPauseIcon.animate().scaleX(1f).scaleY(1f).alpha(playbackLoading ? 0f : 1f).setDuration(210)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f)).start();
        btnPlayPauseCircle.setContentDescription(cl.arichard.descargador.util.Tr.t(isPlaying ? "Pausar" : "Reproducir"));
    }

    // Durante un crossfade la barra y los tiempos siguen a la canción que sale; título y portada cambian a mitad de la mezcla.
    private Song pendingSong;
    private boolean followingOutgoing;
    private final Runnable outgoingTick = new Runnable() {
        @Override public void run() {
            PlayerManager player = PlayerManager.getInstance();
            int position = player.getOutgoingPosition(), duration = player.getOutgoingDuration();
            if (!player.isCrossfading() || position < 0 || duration <= 0) { finishFollowingOutgoing(); return; }
            long total = player.crossfadeTotalMs(), remaining = player.crossfadeRemainingMs();
            if (pendingSong != null && total > 0 && remaining <= total / 2) {
                Song next = pendingSong; pendingSong = null;
                updateSongInfo(next);
            }
            if (!isUserSeeking) updateProgress(position, duration);
            seekBar.postOnAnimation(this);
        }
    };

    private void beginFollowingOutgoing(Song next) {
        pendingSong = next;
        if (followingOutgoing) return;
        followingOutgoing = true;
        seekBar.postOnAnimation(outgoingTick);
    }

    private void finishFollowingOutgoing() {
        seekBar.removeCallbacks(outgoingTick);
        followingOutgoing = false;
        Song next = pendingSong;
        pendingSong = null;
        PlayerManager player = PlayerManager.getInstance();
        followingOutgoing = false;
        if (next != null) updateSongInfo(next);
        waveBinder.bind(player.getCurrentSong());
        updateProgress(player.getCurrentPosition(), player.getDuration());
    }

    private cl.arichard.descargador.util.WaveBinder waveBinder;

    private void updateCrossfadeZone(int dur) { waveBinder.update(dur); }

    private void updateProgress(int pos, int dur) {
        if (dur > 0) {
            seekBar.setMax(dur);
            seekBar.setProgress(pos);
            updateCrossfadeZone(dur);
            txtCurrentTime.setText(formatTime(pos));
            txtTotalTime.setText(formatTime(dur));
        } else {
            seekBar.setProgress(0);
            txtCurrentTime.setText("00:00");
            txtTotalTime.setText("00:00");
        }
        if (dur > 0) loadLyricsForSong(PlayerManager.getInstance().getCurrentSong(), false);
        syncLyrics();
    }

    private void updateShuffleButton(boolean active) {
        btnShuffle.setSelected(active);
        btnShuffle.setAlpha(active ? 1.0f : 0.45f);
        btnShuffle.setColorFilter(active ? AccentColors.getColor(this, R.color.accent) : AccentColors.getColor(this, R.color.text_primary));
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
            btnRepeat.setColorFilter(AccentColors.getColor(this, R.color.text_primary));
        } else if (mode == PlayerManager.REPEAT_ALL) {
            btnRepeat.setAlpha(1.0f);
            btnRepeat.setImageResource(R.drawable.ic_repeat);
            btnRepeat.setColorFilter(AccentColors.getColor(this, R.color.accent));
        } else {
            btnRepeat.setAlpha(1.0f);
            btnRepeat.setImageResource(R.drawable.ic_repeat_one);
            btnRepeat.setColorFilter(AccentColors.getColor(this, R.color.accent));
        }
    }

    private String formatTime(int ms) {
        int totalSec = ms / 1000;
        int m = totalSec / 60;
        int s = totalSec % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    @Override
    public void onTrackChanged(Song song) {
        runOnUiThread(() -> {
            if (PlayerManager.getInstance().isCrossfading() && !followingOutgoing) beginFollowingOutgoing(song);
            else if (followingOutgoing) pendingSong = song;
            else updateSongInfo(song);
        });
    }

    @Override
    public void onPlaybackLoading(boolean loading) {
        if (loading) txtCurrentTime.setText(cl.arichard.descargador.util.Tr.t("Cargando…"));
        runOnUiThread(() -> {
            cl.arichard.descargador.util.LoadingRingView ring = findViewById(R.id.playLoadingRing);
            if (ring == null) return;
            playbackLoading = loading;
            ring.setActive(loading);
            btnPlayPauseIcon.animate().cancel();
            btnPlayPauseIcon.animate().alpha(loading ? 0f : 1f).scaleX(1f).scaleY(1f).setDuration(160).start();
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
        runOnUiThread(() -> {
            updatePlaybackState(isPlaying);
            syncLyrics();
        });
    }

    @Override
    public void onProgressUpdate(int currentPos, int duration) {
        if (!isUserSeeking) {
            runOnUiThread(() -> { if (!followingOutgoing) updateProgress(currentPos, duration); });
        }
    }

    private interface OnLyricClickListener {
        void onClick(cl.arichard.descargador.util.LyricsFetcher.Line line);
    }

    private final class LyricsAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<LyricsAdapter.Holder> {
        private java.util.List<cl.arichard.descargador.util.LyricsFetcher.Line> lines = java.util.Collections.emptyList();
        private boolean synced;
        private int activeIndex = -1;
        private final OnLyricClickListener listener;

        LyricsAdapter(OnLyricClickListener listener) {
            this.listener = listener;
        }

        void setData(java.util.List<cl.arichard.descargador.util.LyricsFetcher.Line> nextLines, boolean isSynced, int active) {
            this.lines = nextLines == null ? java.util.Collections.emptyList() : nextLines;
            this.synced = isSynced;
            this.activeIndex = active;
            notifyDataSetChanged();
        }

        void setActiveIndex(int next, int prev) {
            this.activeIndex = next;
            if (prev >= 0 && prev < lines.size()) notifyItemChanged(prev);
            if (next >= 0 && next < lines.size()) notifyItemChanged(next);
        }

        @Override
        public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            float density = parent.getResources().getDisplayMetrics().density;
            TextView tv = new TextView(parent.getContext());
            androidx.recyclerview.widget.RecyclerView.LayoutParams lp =
                    new androidx.recyclerview.widget.RecyclerView.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(6 * density);
            tv.setLayoutParams(lp);
            int padH = Math.round(14 * density);
            int padV = Math.round(10 * density);
            tv.setPadding(padH, padV, padH, padV);
            tv.setLineSpacing(Math.round(3 * density), 1.0f);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            final cl.arichard.descargador.util.LyricsFetcher.Line line = lines.get(position);
            TextView tv = holder.textView;
            if (line.text.isEmpty()) {
                tv.setText("");
                tv.setBackground(null);
                tv.setMinHeight(Math.round(14 * tv.getResources().getDisplayMetrics().density));
                tv.setOnClickListener(null);
                tv.setClickable(false);
                return;
            }
            tv.setMinHeight(0);
            tv.setText(line.text);
            int primary = AccentColors.getColor(NowPlayingActivity.this, R.color.text_primary);
            int secondary = AccentColors.getColor(NowPlayingActivity.this, R.color.text_secondary);
            if (!synced) {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
                tv.setTextColor(primary);
                tv.setAlpha(0.90f);
                tv.setBackground(null);
                tv.setOnClickListener(null);
                tv.setClickable(false);
            } else {
                boolean isCurrent = (position == activeIndex);
                boolean isPast = (activeIndex >= 0 && position < activeIndex);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isCurrent ? 19.5f : 16f);
                tv.setTypeface(null, isCurrent ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                tv.setTextColor(isCurrent ? primary : secondary);
                tv.setAlpha(isCurrent ? 1.0f : (isPast ? 0.48f : 0.78f));
                if (isCurrent) {
                    tv.setBackgroundResource(R.drawable.bg_nav_island);
                } else {
                    tv.setBackground(null);
                }
                if (line.timeMs >= 0) {
                    tv.setOnClickListener(v -> listener.onClick(line));
                } else {
                    tv.setOnClickListener(null);
                    tv.setClickable(false);
                }
            }
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        final class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final TextView textView;
            Holder(TextView itemView) {
                super(itemView);
                this.textView = itemView;
            }
        }
    }
}
