package cl.arichard.descargador;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import cl.arichard.descargador.util.ImageLoader;
import cl.arichard.descargador.util.StorageUtil;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_VIDEO_ID = "videoId";
    public static final String EXTRA_CHANNEL = "extra_channel";
    public static final String EXTRA_THUMBNAIL = "extra_thumbnail";

    private static final Pattern YOUTUBE_ID = Pattern.compile("(?:youtu\\.be/|youtube(?:\\.com)?/.*?[?&]v=|youtube\\.com/(?:shorts|embed)/)([A-Za-z0-9_-]{11})");

    private TextView tabSelectorMp3, tabSelectorMp4;
    private View containerMp3Cover, containerMp4Video;
    private ImageView imgPlayerCoverArt;
    private WebView webView;
    private TextView txtTitle, txtChannel, txtDownloadButtonLabel, txtDownloadStatus, txtDownloadEta;
    private ProgressBar downloadProgress;
    private LinearLayout layoutDownloadStatus;
    private FrameLayout btnPlayerDownload;

    private String targetUrl;
    private String songTitle;
    private String videoId;
    private String thumbnailUrl;
    private boolean isMp3Mode = true;
    private volatile boolean isDownloading = false;

    private static boolean isSafeYouTubeUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("youtube.com") || host.endsWith(".youtube.com")
                    || host.equals("youtu.be") || host.equals("youtube-nocookie.com")
                    || host.endsWith(".youtube-nocookie.com");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_player);

        ImageView btnClose = findViewById(R.id.btnClosePlayer);
        tabSelectorMp3 = findViewById(R.id.tabSelectorMp3);
        tabSelectorMp4 = findViewById(R.id.tabSelectorMp4);
        containerMp3Cover = findViewById(R.id.containerMp3Cover);
        containerMp4Video = findViewById(R.id.containerMp4Video);
        imgPlayerCoverArt = findViewById(R.id.imgPlayerCoverArt);
        webView = findViewById(R.id.webView);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        txtTitle = findViewById(R.id.txtPlayerSongTitle);
        txtChannel = findViewById(R.id.txtPlayerChannel);
        btnPlayerDownload = findViewById(R.id.btnPlayerDownload);
        txtDownloadButtonLabel = findViewById(R.id.txtPlayerDownloadButtonLabel);
        layoutDownloadStatus = findViewById(R.id.playerDownloadStatus);
        txtDownloadStatus = findViewById(R.id.txtPlayerDownloadStatus);
        txtDownloadEta = findViewById(R.id.txtPlayerDownloadEta);
        downloadProgress = findViewById(R.id.playerDownloadProgress);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        Intent intent = getIntent();
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        targetUrl = intent.getStringExtra(EXTRA_URL);
        songTitle = intent.getStringExtra(EXTRA_TITLE);
        String channel = intent.getStringExtra(EXTRA_CHANNEL);
        thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL);

        if (videoId != null && !videoId.matches("[A-Za-z0-9_-]{11}")) videoId = null;
        if (!isSafeYouTubeUrl(targetUrl)) targetUrl = null;

        if ((videoId == null || videoId.isEmpty()) && targetUrl != null) {
            Matcher m = YOUTUBE_ID.matcher(targetUrl);
            if (m.find()) {
                videoId = m.group(1);
            }
        }

        if (thumbnailUrl == null || thumbnailUrl.isEmpty()) {
            if (videoId != null && !videoId.isEmpty()) {
                thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            }
        }

        txtTitle.setText(songTitle != null ? songTitle : "YouTube");
        txtChannel.setText(channel != null ? channel : "YouTube");

        // Load cover art
        if (thumbnailUrl != null) {
            ImageLoader.getInstance().load(thumbnailUrl, imgPlayerCoverArt);
        }

        // Configure WebView strictly for in-app embedding without external redirects
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request == null || !isSafeYouTubeUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isSafeYouTubeUrl(url);
            }
        });

        // Load embedded video
        if (videoId != null && !videoId.isEmpty()) {
            String embedHtml = "<!doctype html>" +
                    "<html><head>" +
                    "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>" +
                    "<meta name='referrer' content='strict-origin-when-cross-origin'>" +
                    "<style>" +
                    "* { margin: 0; padding: 0; box-sizing: border-box; background: #000000; }" +
                    "html, body { width: 100%; height: 100%; overflow: hidden; }" +
                    "iframe { width: 100%; height: 100%; border: 0; display: block; }" +
                    "</style>" +
                    "</head><body>" +
                    "<iframe src='https://www.youtube-nocookie.com/embed/" + videoId + "?autoplay=1&playsinline=1&modestbranding=1&rel=0&origin=https%3A%2F%2Fmusicbox.local' " +
                    "allow='accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture' allowfullscreen></iframe>" +
                    "</body></html>";

            webView.loadDataWithBaseURL("https://musicbox.local/", embedHtml, "text/html", "UTF-8", null);
        } else if (targetUrl != null && !targetUrl.isEmpty()) {
            webView.loadUrl(targetUrl);
        }

        // Setup top selector tabs
        tabSelectorMp3.setOnClickListener(v -> setMode(true));
        tabSelectorMp4.setOnClickListener(v -> setMode(false));

        setMode(true); // Default to MP3 (music with cover art)

        btnPlayerDownload.setOnClickListener(v -> startDownload());
    }

    private void setMode(boolean mp3) {
        isMp3Mode = mp3;
        int activeText = ContextCompat.getColor(this, R.color.chip_active_text);
        int inactiveText = ContextCompat.getColor(this, R.color.text_secondary);

        if (isMp3Mode) {
            tabSelectorMp3.setBackgroundResource(R.drawable.bg_chip_active);
            tabSelectorMp3.setTextColor(activeText);
            tabSelectorMp4.setBackground(null);
            tabSelectorMp4.setTextColor(inactiveText);

            containerMp3Cover.setVisibility(View.VISIBLE);
            containerMp4Video.setVisibility(View.GONE);
            txtDownloadButtonLabel.setText(cl.arichard.descargador.util.Tr.t("Descargar Canción MP3"));
        } else {
            tabSelectorMp4.setBackgroundResource(R.drawable.bg_chip_active);
            tabSelectorMp4.setTextColor(activeText);
            tabSelectorMp3.setBackground(null);
            tabSelectorMp3.setTextColor(inactiveText);

            containerMp3Cover.setVisibility(View.GONE);
            containerMp4Video.setVisibility(View.VISIBLE);
            txtDownloadButtonLabel.setText(cl.arichard.descargador.util.Tr.t("Descargar Video MP4"));
        }
    }

    private void startDownload() {
        String downloadUrl = targetUrl;
        if ((downloadUrl == null || downloadUrl.isEmpty()) && videoId != null)
            downloadUrl = "https://www.youtube.com/watch?v=" + videoId;
        if (downloadUrl == null || downloadUrl.isEmpty()) return;
        cl.arichard.descargador.download.DownloadManager manager = cl.arichard.descargador.download.DownloadManager.get(this);
        manager.enqueue(java.util.Collections.singletonList(downloadUrl),
                new cl.arichard.descargador.download.DownloadOptions(isMp3Mode, isMp3Mode ? "192 kbps" : "1080p",
                        false, "", cl.arichard.descargador.download.DownloadManager.cookiesPath(this),
                        StorageUtil.getDownloadDir(this).getAbsolutePath()));
        renderDownload();
    }

    private final Runnable downloadObserver = () -> runOnUiThread(this::renderDownload);

    private void renderDownload() {
        if (isDestroyed() || txtDownloadStatus == null) return;
        cl.arichard.descargador.download.DownloadJob latest = null;
        for (cl.arichard.descargador.download.DownloadJob job :
                cl.arichard.descargador.download.DownloadManager.get(this).jobs()) {
            if ((job.url.equals(targetUrl) || (videoId != null && job.url.contains(videoId))) && job.options.mp3 == isMp3Mode)
                latest = job;
        }
        if (latest == null) { layoutDownloadStatus.setVisibility(View.GONE); return; }
        layoutDownloadStatus.setVisibility(View.VISIBLE);
        downloadProgress.setProgress(Math.round(latest.progress * 100));
        String state = latest.status == cl.arichard.descargador.download.DownloadJob.Status.DONE
                ? "¡Descarga completada con éxito!" : latest.status == cl.arichard.descargador.download.DownloadJob.Status.WAITING
                ? "Descarga en cola" : latest.status == cl.arichard.descargador.download.DownloadJob.Status.CANCELLED
                ? "Descarga cancelada" : latest.status.isFinished() ? "Error en la descarga" : "Descarga en curso";
        txtDownloadStatus.setText(cl.arichard.descargador.util.Tr.t(state));
        txtDownloadEta.setText(!latest.error.isEmpty() ? latest.error : latest.etaSeconds >= 0
                ? cl.arichard.descargador.util.Tr.f("Faltan aprox. %d s", latest.etaSeconds)
                : latest.status == cl.arichard.descargador.download.DownloadJob.Status.DONE
                ? cl.arichard.descargador.util.Tr.t("Guardado en tu biblioteca") : "");
    }

    @Override protected void onStart() {
        super.onStart();
        cl.arichard.descargador.download.DownloadManager.get(this).addObserver(downloadObserver);
        renderDownload();
    }

    @Override protected void onStop() {
        cl.arichard.descargador.download.DownloadManager.get(this).removeObserver(downloadObserver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}
