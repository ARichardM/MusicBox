package cl.arichard.descargador;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import cl.arichard.descargador.download.DownloadHistoryDb;
import cl.arichard.descargador.download.DownloadJob;
import cl.arichard.descargador.download.DownloadManager;
import cl.arichard.descargador.download.DownloadOptions;
import cl.arichard.descargador.download.FileNames;
import cl.arichard.descargador.download.LinkParser;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.AppNotice;
import cl.arichard.descargador.util.SafeTap;
import cl.arichard.descargador.util.Tr;

/**
 * El descargador completo: enlaces o playlists, MP3 o MP4 con calidad, cookies, cola con avance en vivo,
 * historial paginado y registro. Se abre desde el perfil. Todo lo que ve viene de {@link DownloadManager},
 * que sigue trabajando aunque se cierre la pantalla.
 */
public class DownloaderActivity extends AppCompatActivity {
    private static final String PREFS = "downloader";
    private static final int PAGE = DownloadHistoryDb.PAGE_SIZE;

    private SharedPreferences prefs;
    private DownloadManager manager;
    private DownloadHistoryDb history;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable observer = () -> main.post(this::renderState);

    private boolean mp3 = true, playlistMode, manualMode;
    private int tab;
    private int historyOffset, historyTotal, lastReadyCount = -1;
    private String historySearch = "";

    private EditText inputLinks, inputRange, inputHistorySearch;
    private TextView txtLinkCount, txtLinkError, txtDestination, txtCookies, btnCookies, btnCancelAll;
    private TextView txtHistoryStatus, txtHistoryPage, txtHistoryEmpty, txtLogEmpty;
    private View cardJobs, cardReady, pageDownload, pageHistory, pageLog;
    private LinearLayout layoutJobs, layoutReady, layoutQuality;
    private RecyclerView recyclerHistory, recyclerLog;
    private final HistoryAdapter historyAdapter = new HistoryAdapter();
    private final LogAdapter logAdapter = new LogAdapter();

    private final Map<String, JobRow> jobRows = new HashMap<>();
    private String jobsSignature = "", readySignature = "";

    private final ActivityResultLauncher<String[]> pickCookies =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importCookies);
    private final ActivityResultLauncher<Uri> pickImportFolder =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { /* el permiso de esta sesión basta para leer */ }
                launchFolderImport(uri);
            });
    private final ActivityResultLauncher<Intent> importList =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                int linked = result.getData().getIntExtra(ImporterActivity.EXTRA_LINKED, -1);
                if (linked >= 0) {
                    AppNotice.info(findViewById(R.id.downloaderRoot), Tr.f("%d canciones enlazadas a «%s»", linked,
                            result.getData().getStringExtra(ImporterActivity.EXTRA_PLAYLIST)));
                    return;
                }
                ArrayList<String> urls = result.getData().getStringArrayListExtra(ImporterActivity.EXTRA_URLS);
                if (urls != null && !urls.isEmpty()) addUrls(urls);
            });
    private final ActivityResultLauncher<String> askNotifications =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_downloader);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        manager = DownloadManager.get(this);
        history = DownloadHistoryDb.get(this);
        mp3 = prefs.getBoolean("mp3", true);
        playlistMode = prefs.getBoolean("playlist", false);

        findViewById(R.id.btnDownloaderBack).setOnClickListener(v -> finish());
        inputLinks = findViewById(R.id.inputLinks);
        inputRange = findViewById(R.id.inputRange);
        inputHistorySearch = findViewById(R.id.inputHistorySearch);
        txtLinkCount = findViewById(R.id.txtLinkCount);
        txtLinkError = findViewById(R.id.txtLinkError);
        txtDestination = findViewById(R.id.txtDestination);
        txtCookies = findViewById(R.id.txtCookies);
        btnCookies = findViewById(R.id.btnCookies);
        btnCancelAll = findViewById(R.id.btnCancelAll);
        txtHistoryStatus = findViewById(R.id.txtHistoryStatus);
        txtHistoryPage = findViewById(R.id.txtHistoryPage);
        txtHistoryEmpty = findViewById(R.id.txtHistoryEmpty);
        txtLogEmpty = findViewById(R.id.txtLogEmpty);
        cardJobs = findViewById(R.id.cardJobs);
        cardReady = findViewById(R.id.cardReady);
        pageDownload = findViewById(R.id.pageDownload);
        pageHistory = findViewById(R.id.pageHistory);
        pageLog = findViewById(R.id.pageLog);
        layoutJobs = findViewById(R.id.layoutJobs);
        layoutReady = findViewById(R.id.layoutReady);
        layoutQuality = findViewById(R.id.layoutQuality);
        recyclerHistory = findViewById(R.id.recyclerHistory);
        recyclerLog = findViewById(R.id.recyclerLog);

        recyclerHistory.setLayoutManager(new LinearLayoutManager(this));
        recyclerHistory.setAdapter(historyAdapter);
        recyclerLog.setLayoutManager(new LinearLayoutManager(this));
        recyclerLog.setAdapter(logAdapter);

        setupTabs();
        setupFormat();
        setupMode();
        setupLinks();
        setupActions();
        setupHistory();
        inputRange.setText(prefs.getString("range", ""));
        if (state == null) inputLinks.setText(prefs.getString("links", ""));
        if (state == null) receiveSharedText(getIntent());
        txtDestination.setText(DownloadManager.defaultDestination(this).getAbsolutePath());
        renderCookies();
        selectTab(0, false);
        renderState();
        if (state == null) {
            animateIn(findViewById(R.id.cardLinks), 0);
            animateIn(findViewById(R.id.cardFormat), 60);
            animateIn(findViewById(R.id.cardMode), 120);
            animateIn(findViewById(R.id.cardOptions), 180);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        manager.addObserver(observer);
        renderState();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveSharedText(intent);
    }

    private void receiveSharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null || shared.toString().trim().isEmpty()) return;
        // El usuario revisa formato y enlaces antes de iniciar la descarga.
        inputLinks.setText(shared.toString().trim());
        inputLinks.setSelection(inputLinks.length());
        selectTab(0, false);
    }

    @Override protected void onStop() {
        manager.removeObserver(observer);
        prefs.edit().putString("links", inputLinks.getText().toString()).putString("range", inputRange.getText().toString())
                .putBoolean("mp3", mp3).putBoolean("playlist", playlistMode).apply();
        super.onStop();
    }

    private void animateIn(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    // --- Pestañas ---

    private void setupTabs() {
        int[] ids = {R.id.tabDownload, R.id.tabHistory, R.id.tabLog};
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            View tabView = findViewById(ids[i]);
            tabView.setOnClickListener(v -> selectTab(index, true));
            SafeTap.install(tabView);
        }
    }

    private void selectTab(int index, boolean animate) {
        int[] ids = {R.id.tabDownload, R.id.tabHistory, R.id.tabLog};
        View[] pages = {pageDownload, pageHistory, pageLog};
        for (int i = 0; i < ids.length; i++) {
            TextView label = findViewById(ids[i]);
            boolean selected = i == index;
            label.setBackgroundResource(selected ? R.drawable.bg_chip_active : 0);
            label.setTextColor(ContextCompat.getColor(this, selected ? R.color.chip_active_text : R.color.text_secondary));
            View page = pages[i];
            if (selected) {
                page.setVisibility(View.VISIBLE);
                if (animate && tab != index) {
                    page.setAlpha(0f); page.setTranslationY(dp(10));
                    page.animate().alpha(1f).translationY(0f).setDuration(220).setStartDelay(0)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else { page.setAlpha(1f); page.setTranslationY(0f); }
            } else {
                page.animate().cancel();
                page.setVisibility(View.GONE);
            }
        }
        tab = index;
        if (index == 1) loadHistory();
        if (index == 2) renderLog();
    }

    // --- Formato, calidad y modo ---

    private String quality() {
        return prefs.getString(mp3 ? "quality_mp3" : "quality_mp4",
                mp3 ? DownloadOptions.AUDIO_QUALITY.get(0) : DownloadOptions.VIDEO_QUALITY.get(0));
    }

    private void setupFormat() {
        findViewById(R.id.tileMp3).setOnClickListener(v -> { mp3 = true; renderFormat(); });
        findViewById(R.id.tileMp4).setOnClickListener(v -> { mp3 = false; renderFormat(); });
        SafeTap.installRowTap(findViewById(R.id.tileMp3));
        SafeTap.installRowTap(findViewById(R.id.tileMp4));
        renderFormat();
    }

    private void style(View tile, TextView title, TextView sub, boolean selected) {
        tile.setBackgroundResource(selected ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
        title.setTextColor(ContextCompat.getColor(this, selected ? R.color.chip_active_text : R.color.text_primary));
        sub.setTextColor(ContextCompat.getColor(this, selected ? R.color.chip_active_text : R.color.text_secondary));
    }

    private void renderFormat() {
        style(findViewById(R.id.tileMp3), findViewById(R.id.txtMp3), findViewById(R.id.txtMp3Sub), mp3);
        style(findViewById(R.id.tileMp4), findViewById(R.id.txtMp4), findViewById(R.id.txtMp4Sub), !mp3);
        layoutQuality.removeAllViews();
        String current = quality();
        for (String option : mp3 ? DownloadOptions.AUDIO_QUALITY : DownloadOptions.VIDEO_QUALITY) {
            TextView chip = new TextView(this);
            boolean selected = option.equals(current);
            chip.setText(option.equals("Mejor disponible") ? Tr.t("Mejor disponible") : option);
            chip.setTextSize(13f);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setBackgroundResource(selected ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chip.setTextColor(ContextCompat.getColor(this, selected ? R.color.chip_active_text : R.color.text_primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            lp.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                prefs.edit().putString(mp3 ? "quality_mp3" : "quality_mp4", option).apply();
                renderFormat();
            });
            SafeTap.install(chip);
            layoutQuality.addView(chip);
        }
    }

    private void setupMode() {
        findViewById(R.id.chipSingle).setOnClickListener(v -> { manualMode = true; playlistMode = false; renderMode(); });
        findViewById(R.id.chipPlaylist).setOnClickListener(v -> { manualMode = true; playlistMode = true; renderMode(); });
        SafeTap.install(findViewById(R.id.chipSingle));
        SafeTap.install(findViewById(R.id.chipPlaylist));
        renderMode();
    }

    private void renderMode() {
        TextView single = findViewById(R.id.chipSingle), playlist = findViewById(R.id.chipPlaylist);
        single.setBackgroundResource(!playlistMode ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
        playlist.setBackgroundResource(playlistMode ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
        single.setTextColor(ContextCompat.getColor(this, !playlistMode ? R.color.chip_active_text : R.color.text_primary));
        playlist.setTextColor(ContextCompat.getColor(this, playlistMode ? R.color.chip_active_text : R.color.text_primary));
        inputRange.setVisibility(playlistMode ? View.VISIBLE : View.GONE);
    }

    // --- Enlaces y acciones ---

    private void setupLinks() {
        inputLinks.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                List<String> lines = LinkParser.uniqueLines(s.toString());
                txtLinkCount.setText(lines.size() == 1 ? Tr.t("1 enlace en cola") : Tr.f("%d enlaces en cola", lines.size()));
                txtLinkError.setVisibility(View.GONE);
                if (!manualMode) {
                    boolean detected = LinkParser.detectPlaylistMode(lines);
                    if (detected != playlistMode) { playlistMode = detected; renderMode(); }
                }
            }
        });
        txtLinkCount.setText(Tr.f("%d enlaces en cola", 0));
    }

    private void setupActions() {
        View paste = findViewById(R.id.btnPasteLinks), clear = findViewById(R.id.btnClearLinks);
        paste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence text = data != null && data.getItemCount() > 0 ? data.getItemAt(0).coerceToText(this) : null;
            if (text == null || TextUtils.isEmpty(text.toString().trim())) {
                AppNotice.info(findViewById(R.id.downloaderRoot), Tr.t("El portapapeles está vacío"));
                return;
            }
            String current = inputLinks.getText().toString();
            inputLinks.setText(current.isEmpty() ? text.toString().trim() : current.trim() + "\n" + text.toString().trim());
            inputLinks.setSelection(inputLinks.getText().length());
        });
        clear.setOnClickListener(v -> { inputLinks.setText(""); manualMode = false; });
        SafeTap.install(paste); SafeTap.install(clear);
        View searchCovers = findViewById(R.id.btnSearchCovers);
        searchCovers.setOnClickListener(v -> VideoSearchSheet.show(this, this::addUrls));
        SafeTap.install(searchCovers);
        TextView importButton = findViewById(R.id.btnImportList);
        importButton.setText(Tr.t("Importar lista"));
        importButton.setOnClickListener(v -> importList.launch(new Intent(this, ImporterActivity.class)));
        SafeTap.install(importButton);
        TextView importFolder = findViewById(R.id.btnImportFolder);
        importFolder.setText(Tr.t("Importar carpeta"));
        importFolder.setOnClickListener(v -> pickImportFolder.launch(null));
        SafeTap.install(importFolder);
        View download = findViewById(R.id.btnDownload);
        download.setOnClickListener(v -> startDownload());
        SafeTap.install(download);
        btnCancelAll.setOnClickListener(v -> manager.cancelAll());
        SafeTap.install(btnCancelAll);
        findViewById(R.id.btnClearJobs).setOnClickListener(v -> manager.clearFinished());
        findViewById(R.id.btnLogClear).setOnClickListener(v -> manager.clearLog());
        btnCookies.setOnClickListener(v -> {
            if (!DownloadManager.cookiesPath(this).isEmpty()) {
                new File(getFilesDir(), "cookies.txt").delete();
                renderCookies();
            } else {
                pickCookies.launch(new String[]{"text/plain", "application/octet-stream", "*/*"});
            }
        });
    }

    private void launchFolderImport(Uri tree) {
        importList.launch(new Intent(this, ImporterActivity.class).putExtra(ImporterActivity.EXTRA_TREE, tree.toString()));
    }

    /** Pone los enlaces elegidos en la lista, sin repetir los que ya estaban; no empieza a descargar. */
    private void addUrls(List<String> urls) {
        List<String> existing = LinkParser.uniqueLines(inputLinks.getText().toString());
        StringBuilder text = new StringBuilder(inputLinks.getText().toString().trim());
        int fresh = 0;
        for (String url : urls) {
            if (existing.contains(url)) continue;
            if (text.length() > 0) text.append('\n');
            text.append(url); fresh++;
        }
        inputLinks.setText(text.toString());
        inputLinks.setSelection(inputLinks.getText().length());
        AppNotice.info(findViewById(R.id.downloaderRoot), fresh == urls.size()
                ? (fresh == 1 ? Tr.t("1 enlace añadido a la cola") : Tr.f("%d enlaces añadidos a la cola", fresh))
                : Tr.f("%d añadidos a la cola · %d ya estaban", fresh, urls.size() - fresh));
    }

    private void startDownload() {
        try {
            List<String> urls = LinkParser.parseUrls(inputLinks.getText().toString(), playlistMode);
            String items = playlistMode ? LinkParser.validateItems(inputRange.getText().toString()) : "";
            DownloadOptions options = new DownloadOptions(mp3, quality(), playlistMode, items,
                    DownloadManager.cookiesPath(this), DownloadManager.defaultDestination(this).getAbsolutePath());
            int added = manager.enqueue(urls, options);
            if (added == 0) {
                AppNotice.info(findViewById(R.id.downloaderRoot), Tr.t("Esos enlaces ya están en la cola"));
            } else {
                inputLinks.setText("");
                manualMode = false;
                AppNotice.info(findViewById(R.id.downloaderRoot), added == 1 ? Tr.t("1 enlace añadido a la cola")
                        : Tr.f("%d enlaces añadidos a la cola", added));
            }
        } catch (LinkParser.LinkError e) {
            String message = Tr.t(e.getMessage()) + (e.getUrl().isEmpty() ? "" : "\n" + e.getUrl());
            txtLinkError.setText(message);
            txtLinkError.setVisibility(View.VISIBLE);
            txtLinkError.setAlpha(0f);
            txtLinkError.animate().alpha(1f).setDuration(180).start();
        }
    }

    // --- Cookies ---

    private void renderCookies() {
        boolean has = !DownloadManager.cookiesPath(this).isEmpty();
        txtCookies.setText(has ? Tr.t("Cargadas") : Tr.t("Sin configurar · ayuda con videos que piden iniciar sesión"));
        btnCookies.setText(has ? Tr.t("Quitar") : Tr.t("Elegir"));
    }

    private void importCookies(Uri uri) {
        if (uri == null) return;
        File target = new File(getFilesDir(), "cookies.txt");
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) throw new java.io.IOException("sin lectura");
            byte[] buffer = new byte[8192]; int n; long total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > 2_000_000) throw new java.io.IOException("demasiado grande");
                out.write(buffer, 0, n);
            }
        } catch (Exception e) {
            target.delete();
            AppNotice.error(findViewById(R.id.downloaderRoot), Tr.t("No se pudo leer ese archivo de cookies."));
            return;
        }
        // Un cookies.txt de Netscape tiene líneas con siete campos separados por tabulación.
        boolean valid = false;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(target))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && line.split("\t").length >= 7) { valid = true; break; }
            }
        } catch (Exception ignored) {}
        if (!valid) {
            target.delete();
            AppNotice.error(findViewById(R.id.downloaderRoot), Tr.t("Ese archivo no parece un cookies.txt de Netscape."));
        } else {
            AppNotice.info(findViewById(R.id.downloaderRoot), Tr.t("Cookies cargadas"));
        }
        renderCookies();
    }

    // --- Estado en vivo ---

    private void renderState() {
        if (isFinishing() || isDestroyed()) return;
        List<DownloadJob> jobs = manager.jobs();
        boolean busy = manager.isBusy();
        btnCancelAll.setVisibility(busy ? View.VISIBLE : View.GONE);
        renderJobs(jobs);
        List<DownloadManager.ReadyFile> ready = manager.readyFiles();
        renderReady(ready);
        if (ready.size() != lastReadyCount) {
            boolean first = lastReadyCount < 0;
            lastReadyCount = ready.size();
            if (!first && tab == 1) loadHistory();
        }
        if (tab == 2) renderLog();
    }

    private static final class JobRow {
        View root; TextView title, badge, detail, cancel; ProgressBar bar;
    }

    private String jobsKey(List<DownloadJob> jobs) {
        StringBuilder key = new StringBuilder();
        for (DownloadJob job : jobs) key.append(job.id).append(',');
        return key.toString();
    }

    private JobRow buildJobRow() {
        JobRow row = new JobRow();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(14), 0, 0);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        row.title = new TextView(this);
        row.title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        row.title.setTextSize(14f); row.title.setTypeface(null, Typeface.BOLD);
        row.title.setSingleLine(true); row.title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(row.title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.badge = new TextView(this);
        row.badge.setTextSize(10.5f); row.badge.setTypeface(null, Typeface.BOLD);
        row.badge.setGravity(Gravity.CENTER); row.badge.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        badgeLp.setMarginStart(dp(8));
        head.addView(row.badge, badgeLp);
        row.cancel = new TextView(this);
        row.cancel.setText("✕"); row.cancel.setTextSize(15f); row.cancel.setGravity(Gravity.CENTER);
        row.cancel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        head.addView(row.cancel, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(head);
        row.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        row.bar.setMax(1000);
        row.bar.setProgressDrawable(ContextCompat.getDrawable(this, R.drawable.bg_download_progress));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barLp.topMargin = dp(8);
        root.addView(row.bar, barLp);
        row.detail = new TextView(this);
        row.detail.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        row.detail.setTextSize(12f);
        row.detail.setPadding(0, dp(6), 0, 0);
        root.addView(row.detail);
        row.root = root;
        return row;
    }

    private static String clock(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void bindJob(JobRow row, DownloadJob job) {
        String name = job.title.isEmpty() ? job.url.replaceFirst("^https?://(www\\.)?", "") : job.title;
        row.title.setText(name);
        int color; String label;
        switch (job.status) {
            case WAITING: label = Tr.t("EN ESPERA"); color = R.color.text_secondary; break;
            case PREPARING: label = Tr.t("PREPARANDO"); color = R.color.accent; break;
            case DOWNLOADING: label = Tr.t("DESCARGANDO"); color = R.color.accent; break;
            case PROCESSING: label = Tr.t("PROCESANDO"); color = R.color.accent; break;
            case CANCELLING: label = Tr.t("CANCELANDO"); color = R.color.text_secondary; break;
            case CANCELLED: label = Tr.t("CANCELADO"); color = R.color.text_secondary; break;
            case ERROR: label = Tr.t("ERROR"); color = R.color.heart_red; break;
            case PARTIAL: label = Tr.t("CON ERRORES"); color = R.color.heart_red; break;
            default: label = Tr.t("COMPLETADO"); color = R.color.success; break;
        }
        int tint = ContextCompat.getColor(this, color);
        row.badge.setText(label);
        row.badge.setTextColor(tint);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(12));
        pill.setColor((tint & 0x00FFFFFF) | 0x26000000);
        row.badge.setBackground(pill);
        int target = Math.round(job.progress * 1000);
        boolean indeterminate = job.status == DownloadJob.Status.PREPARING || job.status == DownloadJob.Status.WAITING;
        row.bar.setVisibility(job.status.isFinished() && job.status != DownloadJob.Status.DONE ? View.GONE : View.VISIBLE);
        row.bar.setProgress(indeterminate ? 0 : target, true);
        row.cancel.setVisibility(job.status.isFinished() ? View.GONE : View.VISIBLE);
        row.cancel.setOnClickListener(v -> manager.cancel(job.id));
        List<String> parts = new ArrayList<>();
        if (job.status.isActive() && job.status != DownloadJob.Status.PREPARING) {
            if (!job.size.isEmpty()) parts.add(job.size);
            if (!job.speed.isEmpty()) parts.add(job.speed);
            if (job.etaSeconds >= 0) parts.add(Tr.f("%s restantes", clock(job.etaSeconds)));
            if (job.itemCount > 1) parts.add(Tr.f("Archivo %d de %d", job.itemIndex, job.itemCount));
        } else if (job.status == DownloadJob.Status.PREPARING) {
            parts.add(Tr.t("Analizando enlace…"));
        } else if (job.status == DownloadJob.Status.DONE || job.status == DownloadJob.Status.PARTIAL) {
            if (job.filesDone > 0) parts.add(job.filesDone == 1 ? Tr.t("1 archivo") : Tr.f("%d archivos", job.filesDone));
            if (job.filesExisting > 0) parts.add(job.filesExisting == 1 ? Tr.t("1 ya existía") : Tr.f("%d ya existían", job.filesExisting));
        }
        if (job.status == DownloadJob.Status.ERROR || job.status == DownloadJob.Status.PARTIAL) {
            if (!job.error.isEmpty()) parts.add(job.error);
        }
        row.detail.setText(TextUtils.join(" · ", parts));
        row.detail.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderJobs(List<DownloadJob> jobs) {
        cardJobs.setVisibility(jobs.isEmpty() ? View.GONE : View.VISIBLE);
        String key = jobsKey(jobs);
        if (!key.equals(jobsSignature)) {
            jobsSignature = key;
            layoutJobs.removeAllViews();
            jobRows.clear();
            for (DownloadJob job : jobs) {
                JobRow row = buildJobRow();
                jobRows.put(job.id, row);
                layoutJobs.addView(row.root);
            }
        }
        for (DownloadJob job : jobs) {
            JobRow row = jobRows.get(job.id);
            if (row != null) bindJob(row, job);
        }
    }

    private void renderReady(List<DownloadManager.ReadyFile> files) {
        cardReady.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
        StringBuilder key = new StringBuilder();
        for (DownloadManager.ReadyFile file : files) key.append(file.path).append('|');
        if (key.toString().equals(readySignature)) return;
        readySignature = key.toString();
        layoutReady.removeAllViews();
        for (int i = files.size() - 1; i >= 0; i--) {
            DownloadManager.ReadyFile file = files.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), 0, dp(2));
            TextView title = new TextView(this);
            title.setText(file.title);
            title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            title.setTextSize(14f); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView badge = new TextView(this);
            badge.setText(file.existing ? Tr.t("Existente") : file.mp3 ? "MP3" : "MP4");
            badge.setTextSize(10.5f); badge.setTypeface(null, Typeface.BOLD); badge.setGravity(Gravity.CENTER);
            badge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            badge.setBackgroundResource(R.drawable.bg_chip_inactive);
            badge.setPadding(dp(10), 0, dp(10), 0);
            row.addView(badge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
            row.setOnClickListener(v -> playFile(file.path, file.title, ""));
            row.setOnLongClickListener(v -> { shareFile(file.path); return true; });
            SafeTap.installRowTap(row);
            layoutReady.addView(row);
        }
    }

    private void playFile(String path, String title, String artist) {
        File file = new File(path);
        if (!file.isFile()) {
            AppNotice.error(findViewById(R.id.downloaderRoot), Tr.t("El archivo ya no existe en esa ubicación."));
            return;
        }
        Song song = new Song(0, title, artist, "", 0, path, file.getParent(), file.lastModified() / 1000);
        PlayerManager.getInstance().setPlaybackSource("songs", "");
        PlayerManager.getInstance().playSong(song, Collections.singletonList(song));
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    private void shareFile(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            AppNotice.error(findViewById(R.id.downloaderRoot), Tr.t("El archivo ya no existe en esa ubicación."));
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType(path.toLowerCase(Locale.ROOT).endsWith(".mp4") ? "video/mp4" : "audio/mpeg")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, Tr.t("Compartir")));
        } catch (Exception e) {
            AppNotice.error(findViewById(R.id.downloaderRoot), Tr.t("No se pudo compartir este archivo."));
        }
    }

    // --- Historial ---

    private void setupHistory() {
        inputHistorySearch.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                historySearch = inputHistorySearch.getText().toString().trim();
                historyOffset = 0;
                loadHistory();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnHistoryAll).setOnClickListener(v -> {
            inputHistorySearch.setText(""); historySearch = ""; historyOffset = 0; loadHistory();
        });
        findViewById(R.id.btnHistoryPrev).setOnClickListener(v -> {
            if (historyOffset > 0) { historyOffset = Math.max(0, historyOffset - PAGE); loadHistory(); }
        });
        findViewById(R.id.btnHistoryNext).setOnClickListener(v -> {
            if (historyOffset + PAGE < historyTotal) { historyOffset += PAGE; loadHistory(); }
        });
        SafeTap.install(findViewById(R.id.btnHistoryPrev));
        SafeTap.install(findViewById(R.id.btnHistoryNext));
    }

    private int historyGeneration;

    private void loadHistory() {
        final int generation = ++historyGeneration;
        final String search = historySearch;
        final int offset = historyOffset;
        new Thread(() -> {
            int total = history.count(search);
            List<DownloadHistoryDb.Entry> entries = history.page(search, offset, PAGE);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != historyGeneration) return;
                historyTotal = total;
                historyAdapter.set(entries);
                txtHistoryEmpty.setText(search.isEmpty() ? Tr.t("Aún no hay descargas en el historial.")
                        : Tr.t("Ninguna descarga coincide con tu búsqueda."));
                txtHistoryEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                txtHistoryStatus.setText(total == 0 ? "" : total == 1 ? Tr.t("1 registro") : Tr.f("%d registros", total));
                int from = total == 0 ? 0 : offset + 1, to = Math.min(total, offset + PAGE);
                txtHistoryPage.setText(total == 0 ? "" : Tr.f("%d–%d de %d", from, to, total));
                findViewById(R.id.btnHistoryPrev).setAlpha(offset > 0 ? 1f : .35f);
                findViewById(R.id.btnHistoryNext).setAlpha(offset + PAGE < total ? 1f : .35f);
            });
        }).start();
    }

    private String formatDate(String iso) {
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = parser.parse(iso);
            return date == null ? iso : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date);
        } catch (ParseException e) { return iso; }
    }

    private final class HistoryAdapter extends RecyclerView.Adapter<HistoryHolder> {
        private List<DownloadHistoryDb.Entry> entries = new ArrayList<>();
        void set(List<DownloadHistoryDb.Entry> value) { entries = value; notifyDataSetChanged(); }
        @Override public HistoryHolder onCreateViewHolder(ViewGroup parent, int type) {
            return new HistoryHolder(getLayoutInflater().inflate(R.layout.item_download_history, parent, false));
        }
        @Override public void onBindViewHolder(HistoryHolder h, int position) {
            DownloadHistoryDb.Entry e = entries.get(position);
            boolean exists = e.filename != null && new File(e.filename).isFile();
            h.title.setText(e.title == null || e.title.isEmpty() ? FileNames.title(e.filename) : e.title);
            h.sub.setText((e.artist == null || e.artist.isEmpty() ? "" : e.artist + " · ") + formatDate(e.createdAt));
            h.badge.setText(e.format + " · " + e.quality + " · " + (e.existing() ? Tr.t("Existente") : Tr.t("Guardado")));
            h.itemView.setAlpha(exists ? 1f : .55f);
            h.open.setOnClickListener(v -> playFile(e.filename, h.title.getText().toString(), e.artist == null ? "" : e.artist));
            h.share.setOnClickListener(v -> shareFile(e.filename));
            h.queue.setVisibility(e.sourceUrl == null || e.sourceUrl.isEmpty() ? View.GONE : View.VISIBLE);
            h.queue.setOnClickListener(v -> {
                List<String> one = Collections.singletonList(e.sourceUrl);
                boolean wantsMp3 = "MP3".equals(e.format);
                DownloadOptions options = new DownloadOptions(wantsMp3, e.quality, false, "", DownloadManager.cookiesPath(DownloaderActivity.this),
                        DownloadManager.defaultDestination(DownloaderActivity.this).getAbsolutePath());
                int added = manager.enqueue(one, options);
                AppNotice.info(findViewById(R.id.downloaderRoot), added == 0 ? Tr.t("Esos enlaces ya están en la cola") : Tr.t("1 enlace añadido a la cola"));
            });
            SafeTap.install(h.open); SafeTap.install(h.share); SafeTap.install(h.queue);
        }
        @Override public int getItemCount() { return entries.size(); }
    }

    private static final class HistoryHolder extends RecyclerView.ViewHolder {
        final TextView title, sub, badge, open, share, queue;
        HistoryHolder(View view) {
            super(view);
            title = view.findViewById(R.id.txtHistoryTitle); sub = view.findViewById(R.id.txtHistorySub);
            badge = view.findViewById(R.id.txtHistoryBadge); open = view.findViewById(R.id.btnHistoryOpen);
            share = view.findViewById(R.id.btnHistoryShare); queue = view.findViewById(R.id.btnHistoryQueue);
        }
    }

    // --- Registro ---

    private int renderedLog = -1;

    private void renderLog() {
        List<DownloadManager.LogLine> lines = manager.log();
        txtLogEmpty.setVisibility(lines.isEmpty() ? View.VISIBLE : View.GONE);
        if (lines.size() == renderedLog) return;
        LinearLayoutManager layout = (LinearLayoutManager) recyclerLog.getLayoutManager();
        boolean atBottom = renderedLog <= 0 || layout == null || layout.findLastVisibleItemPosition() >= renderedLog - 2;
        renderedLog = lines.size();
        logAdapter.set(lines);
        if (atBottom && !lines.isEmpty()) recyclerLog.scrollToPosition(lines.size() - 1);
    }

    private final class LogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<DownloadManager.LogLine> lines = new ArrayList<>();
        void set(List<DownloadManager.LogLine> value) { lines = value; notifyDataSetChanged(); }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            TextView text = new TextView(parent.getContext());
            text.setTextSize(12.5f);
            text.setTypeface(Typeface.MONOSPACE);
            text.setPadding(0, dp(3), 0, dp(3));
            text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(text) {};
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            DownloadManager.LogLine line = lines.get(position);
            TextView text = (TextView) holder.itemView;
            int color;
            switch (line.level) {
                case "error": color = ContextCompat.getColor(DownloaderActivity.this, R.color.heart_red); break;
                case "warn": color = Color.parseColor("#FF9F0A"); break;
                case "ready": color = ContextCompat.getColor(DownloaderActivity.this, R.color.success); break;
                default: color = ContextCompat.getColor(DownloaderActivity.this, R.color.text_secondary);
            }
            text.setTextColor(color);
            text.setText(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(line.time)) + "  " + line.text);
        }
        @Override public int getItemCount() { return lines.size(); }
    }
}
