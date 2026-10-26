package cl.arichard.descargador;

import cl.arichard.descargador.util.StorageUtil;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.AnimationUtils;
import cl.arichard.descargador.util.CoverArtLoader;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import cl.arichard.descargador.adapter.FolderAdapter;
import cl.arichard.descargador.adapter.PlaylistAdapter;
import cl.arichard.descargador.adapter.SearchResultAdapter;
import cl.arichard.descargador.adapter.SongAdapter;
import cl.arichard.descargador.data.SearchHistoryManager;
import androidx.core.widget.NestedScrollView;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.data.CloudSyncManager;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;

public class MainActivity extends AppCompatActivity implements PlayerManager.PlaybackListener {

    private CloudSyncManager cloudSync;

    private ImageView imgMiniCover;
    private boolean currentTabIsSearch = true;
    private boolean tabDragging;
    private int tabMotionGeneration;
    private View playerPreview, playerPreviewScrim;
    private boolean playerPreviewCommitted;

    private static final Pattern YOUTUBE = Pattern.compile("(?:youtu\\.be/|youtube(?:\\.com)?/.*?[?&]v=|youtube\\.com/(?:shorts|embed)/)([A-Za-z0-9_-]{11})");
    private static final String PREFS_NAME = "musicbox_prefs";
    private static final String KEY_DARK_MODE = "dark_mode";

    // Navigation Island
    private LinearLayout navTabSearch, navTabLibrary;
    private ImageView navIconSearch, navIconLibrary;
    private TextView navLabelSearch, navLabelLibrary;
    private View tabSearch, tabLibrary;

    // Search Tab Views
    private ImageView btnAccount, btnPaste, btnClearSearch;
    private EditText inputSearch;
    private TextView txtSearchStatus, txtSearchHint;
    // Discovery Views
    private NestedScrollView containerSearchDiscovery;
    private View sectionRecentQueries, sectionTopCollections, sectionRecentPlays, sectionTopLibrary;
    private LinearLayout layoutRecentChips, layoutTopCollections, layoutRecentPlaysCards, layoutTopLibraryList;
    private TextView btnClearSearchHistory;

    private View searchLoading;
    private RecyclerView recyclerSearchResults;
    private SearchResultAdapter searchResultAdapter;
    private LinearLayout cardProgress;
    private TextView txtStatus, txtEta;
    private ProgressBar progressBar;
    private boolean isMp3 = true;
    private String lastRecentQueries, lastTopCollections, lastRecentPlays, lastTopLibrary;
    private MainUiState uiState;
    private List<SearchResult> renderedSearchResults;
    private int renderedCompletedDownloads;
    private ArrayDeque<DownloadJob> downloadQueue;
    private Map<String, List<SearchResult>> searchCache;

    static class DownloadJob {
        final String url, title, videoId;
        DownloadJob(String url, String title, String videoId) { this.url = url; this.title = title; this.videoId = videoId; }
    }

    // Library Tab Views
    private ImageView btnBackLibrary, btnShuffleAll, btnNewPlaylist;
    private View layoutAutoDownloadPill;
    private androidx.appcompat.widget.SwitchCompat switchLibraryAutoDownload;
    private TextView txtLibraryTitle, txtSongCount;
    private EditText inputSearchLibrary;
    private ImageView btnClearLibraryFilter;

    private TextView chipSongs, chipFolders, chipPlaylists;
    private RecyclerView recyclerLibrary;
    private View libraryLoading;
    private SongAdapter songAdapter;
    private FolderAdapter folderAdapter;
    private PlaylistAdapter playlistAdapter;
    private View selectionBar, btnCloseSelection;
    private TextView txtSelectionCount, btnSelectAll, btnAddToPlaylistBulk;
    private final List<Song> allSongs = new ArrayList<>();
    private final Set<String> activeDownloadingVideoIds = new HashSet<>();
    /** Videos already attempted by auto-download in this session, so a failure is not retried in a loop. */
    private final Set<String> autoAttempted = new HashSet<>();
    private int currentLibraryMode = 0; // 0=Songs, 1=Favorites, 2=Folders, 3=Playlists
    /** Songs un-hearted while Favoritos is open stay listed (to undo a stray tap) until the user leaves the list. */
    private final java.util.Map<String, Object[]> heldFavorites = new java.util.LinkedHashMap<>();
    private boolean isViewingSubList = false;
    private String currentSubListName = "";
    private boolean libraryReady, changingLibrary;
    private int scanGeneration;

    private SharedPreferences libraryPrefs() { return getSharedPreferences("library_navigation", MODE_PRIVATE); }
    private String libraryPositionKey() {
        return currentLibraryMode + ":" + currentSubListName + ":" + inputSearchLibrary.getText();
    }
    private void saveLibraryPosition() {
        if (!libraryReady || changingLibrary) return;
        LinearLayoutManager layout = (LinearLayoutManager) recyclerLibrary.getLayoutManager();
        int position = layout.findFirstVisibleItemPosition();
        if (position < 0) return;
        View first = layout.findViewByPosition(position);
        String key = libraryPositionKey();
        SharedPreferences.Editor edit = libraryPrefs().edit().putInt(key + ":position", position)
                .putInt(key + ":offset", first == null ? 0 : first.getTop() - recyclerLibrary.getPaddingTop());
        if ((currentLibraryMode == 0 || isViewingSubList) && position < songAdapter.getItemCount())
            edit.putString(key + ":anchor", songAdapter.getDisplayedList().get(position).getPath());
        edit.apply();
    }
    private void restoreLibraryPosition() {
        recyclerLibrary.post(() -> {
            String key = libraryPositionKey();
            int position = libraryPrefs().getInt(key + ":position", 0);
            String anchor = libraryPrefs().getString(key + ":anchor", "");
            if ((currentLibraryMode == 0 || isViewingSubList) && !anchor.isEmpty()) {
                List<Song> songs = songAdapter.getDisplayedList();
                for (int i = 0; i < songs.size(); i++) if (anchor.equals(songs.get(i).getPath())) { position = i; break; }
            }
            RecyclerView.Adapter<?> adapter = recyclerLibrary.getAdapter();
            int count = adapter == null ? 0 : adapter.getItemCount();
            ((LinearLayoutManager) recyclerLibrary.getLayoutManager()).scrollToPositionWithOffset(
                    Math.min(position, Math.max(0, count - 1)), libraryPrefs().getInt(key + ":offset", 0));
        });
    }
    private void saveLibraryNavigation() {
        if (!libraryReady) return;
        saveLibraryPosition();
        libraryPrefs().edit().putInt("mode", currentLibraryMode).remove("filter")
                .putString("sublist", currentSubListName).putString("query", inputSearchLibrary.getText().toString())
                .putBoolean("search_tab", currentTabIsSearch).apply();
    }
    private void restoreLibraryNavigation() {
        SharedPreferences prefs = libraryPrefs();
        int mode = prefs.getInt("mode", 0);
        String name = prefs.getString("sublist", ""), query = prefs.getString("query", "");
        if (mode == 1) { mode = 3; name = "Favoritos"; }
        boolean search = prefs.getBoolean("search_tab", true);
        setLibraryMode(mode);
        if (!name.isEmpty() && (mode == 3 || mode == 2) && PlaylistManager.getInstance(this).getPlaylistNames().contains(name)) showPlaylistSongs(name);
        else if (!name.isEmpty() && mode == 2) {
            List<Song> songs = MusicScanner.groupByFolder(allSongs).get(name);
            if (songs != null) showFolderSongs(name, songs);
        }
        inputSearchLibrary.setText(query);
        restoreLibraryPosition();
        if (!search) switchTab(false);
    }

    // Mini Player
    private LinearLayout miniPlayerLayout;
    private View miniTrackContent;
    private int miniMotionGeneration;
    private boolean miniMotionRunning;
    private TextView txtMiniTitle, txtMiniArtist;
    private ImageView btnMiniPlayPause, btnMiniNext;
    private cl.arichard.descargador.util.LoadingRingView miniLoadingRing;
    private ProgressBar miniProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply saved theme preference before inflating view
        // Theme is already initialized in DescargadorApp.onCreate()

        super.onCreate(savedInstanceState);
        boolean testRuntime = "robolectric".equals(android.os.Build.FINGERPRINT);
        if (!testRuntime && FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish(); return;
        }
        uiState = new androidx.lifecycle.ViewModelProvider(this).get(MainUiState.class);
        if (!testRuntime) cl.arichard.descargador.util.UpdateChecker.check(this);
        downloadQueue = uiState.downloads; searchCache = uiState.cache; isMp3 = uiState.isMp3;
        allSongs.addAll(uiState.library);
        setContentView(R.layout.activity_main);
        cl.arichard.descargador.util.AnimatedSplashView splash =
                new cl.arichard.descargador.util.AnimatedSplashView(this);
        addContentView(splash, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        splash.start();
        PlayerManager.getInstance().setMainActivityHost(findViewById(R.id.globalWebVideoContainer));

        initViews();
        setupNavigation();
        setupSearchTab();
        setupLibraryTab();
        PlaylistManager.getInstance(this).setAutoDownloadListener((playlist, track) ->
                runOnUiThread(() -> startDownload(track.getUrl(), track.getTitle(), track.getId())));
        setupMiniPlayer();
        if (!testRuntime) {
            cloudSync = new CloudSyncManager(this, () -> runOnUiThread(() -> {
                if (!isFinishing()) refreshMusicLibrary();
            }));
            cloudSync.start();
        }
        cl.arichard.descargador.util.SafeTap.installButtons(findViewById(R.id.mainGestureRoot));
        if (uiState.libraryLoaded) { restoreLibraryNavigation(); libraryReady = true; }
        applyScreenState();
        uiState.revision.observe(this, revision -> applyScreenState());
        checkPermissionsAndScan();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (songAdapter != null && songAdapter.isSelectionMode()) {
                    songAdapter.clearSelection();
                    return;
                }
                if (isViewingSubList) {
                    setLibraryMode(currentLibraryMode);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        if (savedInstanceState == null) handleIntent(getIntent());
        containerSearchDiscovery.post(() -> containerSearchDiscovery.scrollTo(0, uiState.discoveryScroll));
        recyclerSearchResults.post(() -> recyclerSearchResults.scrollToPosition(uiState.resultPosition));
    }

    @Override protected void onDestroy() {
        if (cloudSync != null) cloudSync.stop();
        PlaylistManager.getInstance(this).setAutoDownloadListener(null);
        super.onDestroy();
    }

    private void initViews() {
        // Nav Island
        navTabSearch = findViewById(R.id.navTabSearch);
        navTabLibrary = findViewById(R.id.navTabLibrary);
        navIconSearch = findViewById(R.id.navIconSearch);
        navIconLibrary = findViewById(R.id.navIconLibrary);
        navLabelSearch = findViewById(R.id.navLabelSearch);
        navLabelLibrary = findViewById(R.id.navLabelLibrary);
        tabSearch = findViewById(R.id.tabSearch);
        tabLibrary = findViewById(R.id.tabLibrary);

        // Search Tab
        btnAccount = findViewById(R.id.btnAccount);
        inputSearch = findViewById(R.id.inputSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        inputSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                uiState.searchQuery = text.toString();
                btnClearSearch.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
                scheduleLiveSearch(text.toString().trim());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        btnClearSearch.setOnClickListener(v -> clearSearch());
        containerSearchDiscovery = findViewById(R.id.containerSearchDiscovery);
        sectionRecentQueries = findViewById(R.id.sectionRecentQueries);
        sectionTopCollections = findViewById(R.id.sectionTopCollections);
        sectionRecentPlays = findViewById(R.id.sectionRecentPlays);
        sectionTopLibrary = findViewById(R.id.sectionTopLibrary);
        layoutRecentChips = findViewById(R.id.layoutRecentChips);
        layoutTopCollections = findViewById(R.id.layoutTopCollections);
        layoutRecentPlaysCards = findViewById(R.id.layoutRecentPlaysCards);
        layoutTopLibraryList = findViewById(R.id.layoutTopLibraryList);
        btnClearSearchHistory = findViewById(R.id.btnClearSearchHistory);
        if (btnClearSearchHistory != null) {
            btnClearSearchHistory.setOnClickListener(v -> {
                SearchHistoryManager.getInstance(this).clearRecentQueries();
                populateRecentQueries();
            });
        }

        btnPaste = findViewById(R.id.btnPaste);

        txtSearchStatus = findViewById(R.id.txtSearchStatus);
        txtSearchHint = findViewById(R.id.txtSearchHint);
        searchLoading = findViewById(R.id.searchLoading);
        recyclerSearchResults = findViewById(R.id.recyclerSearchResults);
        cardProgress = findViewById(R.id.cardProgress);
        txtStatus = findViewById(R.id.txtStatus);
        txtEta = findViewById(R.id.txtEta);
        progressBar = findViewById(R.id.progressBar);

        // Library Tab
        btnBackLibrary = findViewById(R.id.btnBackLibrary);
        txtLibraryTitle = findViewById(R.id.txtLibraryTitle);
        btnShuffleAll = findViewById(R.id.btnShuffleAll);
        inputSearchLibrary = findViewById(R.id.inputSearchLibrary);
        chipSongs = findViewById(R.id.chipSongs);
        chipFolders = findViewById(R.id.chipFolders);
        chipPlaylists = findViewById(R.id.chipPlaylists);
        txtSongCount = findViewById(R.id.txtSongCount);
        recyclerLibrary = findViewById(R.id.recyclerLibrary);
        libraryLoading = findViewById(R.id.libraryLoading);
        btnNewPlaylist = findViewById(R.id.btnNewPlaylist);
        layoutAutoDownloadPill = findViewById(R.id.layoutAutoDownloadPill);
        switchLibraryAutoDownload = findViewById(R.id.switchLibraryAutoDownload);
        if (switchLibraryAutoDownload != null) {
            switchLibraryAutoDownload.setOnClickListener(v -> {
                boolean checked = switchLibraryAutoDownload.isChecked();
                String targetKey = currentSubListName;
                if (!checked) {
                    switchLibraryAutoDownload.setChecked(true);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(cl.arichard.descargador.util.Tr.t("¿Desactivar descarga automática?"))
                            .setMessage(cl.arichard.descargador.util.Tr.t("Si desactivas la auto-descarga, las canciones nuevas no se descargarán automáticamente al agregarse.\nLas ya descargadas se mantendrán."))
                            .setPositiveButton(cl.arichard.descargador.util.Tr.t("Desactivar"), (dialog, which) -> {
                                switchLibraryAutoDownload.setChecked(false);
                                PlaylistManager.getInstance(this).setAutoDownloadEnabled(targetKey, false);
                            })
                            .setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null)
                            .show();
                } else {
                    enableAutoDownload(targetKey, null);
                }
            });
        }
        btnClearLibraryFilter = findViewById(R.id.btnClearLibraryFilter);
        if (btnClearLibraryFilter != null) {
            btnClearLibraryFilter.setOnClickListener(v -> {
                inputSearchLibrary.setText("");
                applyLibraryFilter("");
            });
        }

        // Floating Selection Bar
        selectionBar = findViewById(R.id.selectionBar);
        txtSelectionCount = findViewById(R.id.txtSelectionCount);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnAddToPlaylistBulk = findViewById(R.id.btnAddToPlaylistBulk);
        btnCloseSelection = findViewById(R.id.btnCloseSelection);

        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> {
                if (songAdapter != null) {
                    if (songAdapter.getSelectionCount() == songAdapter.getDisplayedList().size() && songAdapter.getSelectionCount() > 0) {
                        songAdapter.clearSelection();
                    } else {
                        songAdapter.selectAll();
                    }
                }
            });
        }
        if (btnAddToPlaylistBulk != null) {
            btnAddToPlaylistBulk.setOnClickListener(v -> {
                if (songAdapter != null) {
                    List<Song> selected = songAdapter.getSelectedSongs();
                    if (!selected.isEmpty()) {
                        cl.arichard.descargador.util.SongActions.addMultipleToPlaylist(this, selected, () -> {
                            songAdapter.clearSelection();
                            refreshPlaylistsView();
                        });
                    }
                }
            });
        }
        if (btnCloseSelection != null) {
            btnCloseSelection.setOnClickListener(v -> {
                if (songAdapter != null) songAdapter.clearSelection();
            });
        }


        // Mini Player
        miniPlayerLayout = findViewById(R.id.miniPlayerLayout);
        miniTrackContent = findViewById(R.id.miniTrackContent);
        txtMiniTitle = findViewById(R.id.txtMiniTitle);
        txtMiniArtist = findViewById(R.id.txtMiniArtist);
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause);
        miniLoadingRing = findViewById(R.id.miniLoadingRing);
        btnMiniNext = findViewById(R.id.btnMiniNext);
        miniProgress = findViewById(R.id.miniProgress);
        imgMiniCover = findViewById(R.id.imgMiniCover);
    }

    private final android.os.Handler liveSearchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingLiveSearch;

    /** Searches while typing: waits for a short pause so each letter doesn't launch a yt-dlp process. */
    private void scheduleLiveSearch(String query) {
        if (pendingLiveSearch != null) liveSearchHandler.removeCallbacks(pendingLiveSearch);
        pendingLiveSearch = null;
        // Only react to the user typing; programmatic setText (state restore, clear) has no focus
        if (!inputSearch.hasFocus()) return;
        if (query.isEmpty()) {
            ++uiState.searchGeneration;
            uiState.results = new ArrayList<>();
            uiState.resultsVisible = false;
            uiState.searchLoading = false;
            uiState.searchStatus = "";
            uiState.changed();
            return;
        }
        if (query.length() < 2 || YOUTUBE.matcher(query).find()
                || query.startsWith("http://") || query.startsWith("https://")) return;
        pendingLiveSearch = () -> {
            if (!query.equals(inputSearch.getText().toString().trim())) return;
            if (containerSearchDiscovery != null) containerSearchDiscovery.setVisibility(View.GONE);
            performYoutubeSearch(query);
        };
        liveSearchHandler.postDelayed(pendingLiveSearch, 450);
    }

    private void clearSearch() {
        ++uiState.searchGeneration;
        uiState.searchQuery = "";
        uiState.results = new ArrayList<>();
        uiState.resultsVisible = false;
        uiState.searchLoading = false;
        uiState.searchStatus = "";
        inputSearch.setText("");
        inputSearch.requestFocus();
        uiState.changed();
    }

    private void setupNavigation() {
        ((cl.arichard.descargador.util.TabSwipeLayout) findViewById(R.id.mainGestureRoot))
                .setTabActions(() -> switchTab(false), () -> switchTab(true));
        navTabSearch.setOnClickListener(v -> switchTab(true));
        navTabLibrary.setOnClickListener(v -> switchTab(false));

        navTabSearch.setBackground(null); navTabLibrary.setBackground(null);
        navTabSearch.setSelected(true); navTabLibrary.setSelected(false);
        ((cl.arichard.descargador.util.DragTabBarLayout) findViewById(R.id.navIsland)).setListener(
                new cl.arichard.descargador.util.DragTabBarLayout.Listener() {
                    @Override public void onProgress(float progress) { previewTabs(progress); }
                    @Override public void onSelected(boolean search) { switchTab(search); }
                });
        ((cl.arichard.descargador.util.TabSwipeLayout) findViewById(R.id.mainGestureRoot)).setDragListener(
                new cl.arichard.descargador.util.TabSwipeLayout.DragListener() {
                    @Override public void onDrag(float dx) {
                        float width = Math.max(1, tabSearch.getWidth());
                        previewTabs((currentTabIsSearch ? 0f : 1f) - dx / width);
                    }
                    @Override public void onCancel() { settleTabs(); }
                });

        btnAccount.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void showAccountDialog() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String name = user != null && user.getDisplayName() != null ? user.getDisplayName() : cl.arichard.descargador.util.Tr.t("Cuenta de Google");
        String email = user != null && user.getEmail() != null ? user.getEmail() : "";
        new MaterialAlertDialogBuilder(this).setTitle(name).setMessage(email)
                .setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null)
                .setPositiveButton(cl.arichard.descargador.util.Tr.t("Cerrar sesión"), (dialog, which) -> {
                    FirebaseAuth.getInstance().signOut();
                    startActivity(new Intent(this, LoginActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                }).show();
    }

    private void previewTabs(float progress) {
        if (!tabDragging) {
            ++tabMotionGeneration; tabDragging = true;
            tabSearch.clearAnimation(); tabLibrary.clearAnimation();
            tabSearch.animate().cancel(); tabLibrary.animate().cancel();
            tabSearch.setVisibility(View.VISIBLE); tabLibrary.setVisibility(View.VISIBLE);
        }
        float value = Math.max(0, Math.min(1, progress));
        float width = Math.max(1, findViewById(R.id.mainGestureRoot).getWidth());
        tabSearch.setTranslationX(-value * width); tabLibrary.setTranslationX((1f - value) * width);
        ((cl.arichard.descargador.util.DragTabBarLayout) findViewById(R.id.navIsland)).setProgress(value);
    }

    private void settleTabs() {
        tabDragging = false; final int generation = ++tabMotionGeneration;
        float width = Math.max(1, findViewById(R.id.mainGestureRoot).getWidth());
        tabSearch.animate().cancel(); tabLibrary.animate().cancel();
        if (tabSearch.getVisibility() != View.VISIBLE) tabSearch.setTranslationX(-width);
        if (tabLibrary.getVisibility() != View.VISIBLE) tabLibrary.setTranslationX(width);
        tabSearch.setVisibility(View.VISIBLE); tabLibrary.setVisibility(View.VISIBLE);
        tabSearch.animate().translationX(currentTabIsSearch ? 0 : -width).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> { if (generation == tabMotionGeneration && !currentTabIsSearch) tabSearch.setVisibility(View.GONE); }).start();
        tabLibrary.animate().translationX(currentTabIsSearch ? width : 0).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> { if (generation == tabMotionGeneration && currentTabIsSearch) tabLibrary.setVisibility(View.GONE); }).start();
        ((cl.arichard.descargador.util.DragTabBarLayout) findViewById(R.id.navIsland)).select(currentTabIsSearch, true);
    }

    private void switchTab(boolean isSearch) {
        boolean changed = currentTabIsSearch != isSearch;
        if (changed) saveLibraryNavigation();
        currentTabIsSearch = isSearch;
        int primary = ContextCompat.getColor(this, R.color.text_primary);
        int muted = ContextCompat.getColor(this, R.color.text_secondary);
        navTabSearch.setSelected(isSearch); navTabLibrary.setSelected(!isSearch);
        navIconSearch.setColorFilter(isSearch ? primary : muted); navLabelSearch.setTextColor(isSearch ? primary : muted);
        navIconLibrary.setColorFilter(isSearch ? muted : primary); navLabelLibrary.setTextColor(isSearch ? muted : primary);
        settleTabs();
        if (changed) { if (isSearch) refreshDiscoveryContent(); else refreshMusicLibrary(); }
    }

    private void setupSearchTab() {


        btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                CharSequence text = item.getText();
                if (text != null && !TextUtils.isEmpty(text)) {
                    inputSearch.setText(text.toString().trim());
                    inputSearch.setSelection(inputSearch.getText().length());
                    triggerSearchOrDownload(text.toString().trim());
                }
            }
        });

        searchResultAdapter = new SearchResultAdapter(this, new SearchResultAdapter.OnResultClickListener() {
            @Override
            public void onAddClick(SearchResult result, View anchor) {
                PopupMenu menu = new PopupMenu(MainActivity.this, anchor);
                menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Agregar a playlist")).setOnMenuItemClickListener(item -> {
                    showAddOnlineToPlaylistDialog(result); return true;
                });
                Song song = new Song(0, result.getTitle(), result.getChannel(), "YouTube", result.getDurationSeconds() * 1000L,
                        "youtube:" + result.getId(), "YouTube", 0);
                menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Reproducir a continuación")).setOnMenuItemClickListener(item -> {
                    PlayerManager.getInstance().enqueue(song, true); Toast.makeText(MainActivity.this, cl.arichard.descargador.util.Tr.t("Agregada a continuación"), Toast.LENGTH_SHORT).show(); return true;
                });
                menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Agregar a la cola")).setOnMenuItemClickListener(item -> {
                    PlayerManager.getInstance().enqueue(song, false); Toast.makeText(MainActivity.this, cl.arichard.descargador.util.Tr.t("Agregada a la cola"), Toast.LENGTH_SHORT).show(); return true;
                });
                menu.show();
            }

            @Override
            public void onPlayClick(SearchResult result) {
                playOnlineResult(result, false);
            }

            @Override
            public void onFavoriteChanged(SearchResult result, boolean favorite) {
            }

            @Override
            public void onItemClick(SearchResult result) {
                playOnlineResult(result, true);
            }
        });

        refreshDownloadedBadges();

        recyclerSearchResults.setLayoutManager(new LinearLayoutManager(this));
        recyclerSearchResults.setAdapter(searchResultAdapter);

        inputSearch.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter = (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                hideKeyboard();
                if (pendingLiveSearch != null) liveSearchHandler.removeCallbacks(pendingLiveSearch);
                String query = inputSearch.getText().toString().trim();
                if (!query.isEmpty()) triggerSearchOrDownload(query);
                return true;
            }
            return false;
        });
    }

    /** Remembers which list the song being played belongs to, for the "Reproduciendo desde" label. */
    private void rememberPlaybackSource() {
        PlayerManager player = PlayerManager.getInstance();
        if (isViewingSubList && currentLibraryMode == 3) player.setPlaybackSource("playlist", currentSubListName);
        else if (isViewingSubList && currentLibraryMode == 2) player.setPlaybackSource("folder", currentSubListName);
        else player.setPlaybackSource("songs", "");
    }

    private void playOnlineResult(SearchResult result, boolean openPlayer) {
        PlayerManager.getInstance().setPlaybackSource("search", "");
                PlayerManager.getInstance().playOnline(
                    result.getId(),
                    result.getTitle(),
                    result.getChannel(),
                    result.getThumbnailUrl(),
                    result.getUrl()
                );
        if (openPlayer) {
                Intent intent = new Intent(MainActivity.this, NowPlayingActivity.class);
                intent.putExtra(NowPlayingActivity.EXTRA_VIDEO_ID, result.getId());
                intent.putExtra(NowPlayingActivity.EXTRA_URL, result.getUrl());
                intent.putExtra(NowPlayingActivity.EXTRA_TITLE, result.getTitle());
                intent.putExtra(NowPlayingActivity.EXTRA_CHANNEL, result.getChannel());
                intent.putExtra(NowPlayingActivity.EXTRA_THUMBNAIL, result.getThumbnailUrl());
                startActivity(intent);
        }
    }




    private void refreshDiscoveryContent() {
        populateRecentQueries();
        populateTopCollections();
        populateRecentPlays();
        populateTopLibrary();
    }

    private void populateRecentQueries() {
        if (layoutRecentChips == null || sectionRecentQueries == null) return;
        List<String> queries = SearchHistoryManager.getInstance(this).getRecentQueries();
        String signature = queries.toString(); if (signature.equals(lastRecentQueries)) return; lastRecentQueries = signature;
        if (queries.isEmpty()) {
            sectionRecentQueries.setVisibility(View.GONE);
            return;
        }
        sectionRecentQueries.setVisibility(View.VISIBLE);
        layoutRecentChips.removeAllViews();

        int chipBg = ContextCompat.getColor(this, R.color.bg_card);
        int textColor = ContextCompat.getColor(this, R.color.text_primary);

        for (String q : queries) {
            TextView chip = new TextView(this);
            chip.setText(q);
            chip.setTextColor(textColor);
            chip.setTextSize(13f);
            chip.setBackgroundResource(R.drawable.bg_chip_inactive);
            chip.setPadding(32, 16, 32, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, 16, 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                inputSearch.setText(q);
                inputSearch.setSelection(q.length());
                triggerSearchOrDownload(q);
            });
            cl.arichard.descargador.util.SafeTap.install(chip);
            layoutRecentChips.addView(chip);
        }
    }

    private static final class CollectionRank {
        final String name;
        final boolean playlist;
        final List<Song> songs;
        final int plays;
        CollectionRank(String name, boolean playlist, List<Song> songs, int plays) {
            this.name = name; this.playlist = playlist; this.songs = songs; this.plays = plays;
        }
    }

    private void populateTopCollections() {
        if (layoutTopCollections == null || sectionTopCollections == null || !libraryReady) return;
        SearchHistoryManager history = SearchHistoryManager.getInstance(this);
        List<CollectionRank> ranked = new ArrayList<>();
        for (Map.Entry<String, List<Song>> entry : MusicScanner.groupByFolder(allSongs).entrySet()) {
            int plays = cl.arichard.descargador.data.ClickStats.getInstance(this).count(entry.getValue());
            if (plays > 0) ranked.add(new CollectionRank(entry.getKey(), false, entry.getValue(), plays));
        }
        PlaylistManager playlists = PlaylistManager.getInstance(this);
        for (String name : playlists.getPlaylistNames()) {
            List<Song> songs = playlists.resolveSongs(name, allSongs);
            int plays = cl.arichard.descargador.data.ClickStats.getInstance(this).count(songs);
            if (plays > 0) ranked.add(new CollectionRank(name, true, songs, plays));
        }
        ranked.sort((a, b) -> {
            int byPlays = Integer.compare(b.plays, a.plays);
            return byPlays != 0 ? byPlays : a.name.compareToIgnoreCase(b.name);
        });
        if (ranked.size() > 6) ranked = new ArrayList<>(ranked.subList(0, 6));
        StringBuilder signature = new StringBuilder();
        for (CollectionRank item : ranked) signature.append(item.playlist).append(item.name).append(item.plays);
        if (signature.toString().equals(lastTopCollections)) return;
        lastTopCollections = signature.toString();
        layoutTopCollections.removeAllViews();
        if (ranked.isEmpty()) { sectionTopCollections.setVisibility(View.GONE); return; }
        sectionTopCollections.setVisibility(View.VISIBLE);
        int width = Math.round(270 * getResources().getDisplayMetrics().density);
        int margin = Math.round(10 * getResources().getDisplayMetrics().density);
        for (CollectionRank item : ranked) {
            View card = getLayoutInflater().inflate(R.layout.item_folder, layoutTopCollections, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, margin, 0); card.setLayoutParams(params);
            ((cl.arichard.descargador.util.CoverCollageView) card.findViewById(R.id.folderCollage)).setSongs(item.songs);
            ((TextView) card.findViewById(R.id.txtFolderName)).setText(item.name);
            ((TextView) card.findViewById(R.id.txtFolderSongCount)).setText(
                    (item.playlist ? cl.arichard.descargador.util.Tr.t("Playlist") : cl.arichard.descargador.util.Tr.t("Carpeta")) + " • " + item.plays
                            + (item.plays == 1 ? cl.arichard.descargador.util.Tr.t(" reproducción") : cl.arichard.descargador.util.Tr.t(" reproducciones")));
            card.setOnClickListener(v -> {
                switchTab(false);
                setLibraryMode(item.playlist ? 2 : 1);
                if (item.playlist) showPlaylistSongs(item.name); else showFolderSongs(item.name, item.songs);
            });
            cl.arichard.descargador.util.SafeTap.installRowTap(card);
            layoutTopCollections.addView(card);
        }
    }

    private void populateRecentPlays() {
        if (layoutRecentPlaysCards == null || sectionRecentPlays == null) return;
        List<Song> recents = SearchHistoryManager.getInstance(this).getRecentPlayedSongs();
        StringBuilder signature = new StringBuilder();
        for (Song song : recents) signature.append(song.getPath()).append(song.getTitle());
        if (signature.toString().equals(lastRecentPlays)) return; lastRecentPlays = signature.toString();
        if (recents.isEmpty()) {
            sectionRecentPlays.setVisibility(View.GONE);
            return;
        }
        sectionRecentPlays.setVisibility(View.VISIBLE);
        layoutRecentPlaysCards.removeAllViews();

        int textColor = ContextCompat.getColor(this, R.color.text_primary);
        int subColor = ContextCompat.getColor(this, R.color.text_secondary);

        for (Song s : recents) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(260, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 24, 0);
            card.setLayoutParams(lp);

            androidx.cardview.widget.CardView cv = new androidx.cardview.widget.CardView(this);
            cv.setRadius(24f);
            cv.setCardElevation(4f);
            LinearLayout.LayoutParams cvLp = new LinearLayout.LayoutParams(240, 240);
            cv.setLayoutParams(cvLp);

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cv.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            CoverArtLoader.getInstance().loadSongCover(s.getCoverPath(), iv);

            TextView txtT = new TextView(this);
            txtT.setText(s.getTitle());
            txtT.setTextColor(textColor);
            txtT.setTextSize(13f);
            txtT.setTypeface(null, android.graphics.Typeface.BOLD);
            txtT.setSingleLine(true);
            txtT.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txtT.setPadding(4, 10, 4, 2);

            TextView txtA = new TextView(this);
            txtA.setText(s.getArtist());
            txtA.setTextColor(subColor);
            txtA.setTextSize(11f);
            txtA.setSingleLine(true);
            txtA.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txtA.setPadding(4, 0, 4, 0);

            card.addView(cv);
            card.addView(txtT);
            card.addView(txtA);

            card.setOnClickListener(v -> {
                PlayerManager.getInstance().setPlaybackSource("songs", "");
                PlayerManager.getInstance().playSong(s, Collections.singletonList(s));
                startActivity(new Intent(MainActivity.this, NowPlayingActivity.class));
            });

            cl.arichard.descargador.util.SafeTap.installRowTap(card);
            layoutRecentPlaysCards.addView(card);
        }
    }

    private void populateTopLibrary() {
        if (layoutTopLibraryList == null || sectionTopLibrary == null) return;
        List<Song> topSongs = SearchHistoryManager.getInstance(this).getTopPlayed(allSongs, 5);
        StringBuilder signature = new StringBuilder();
        for (Song song : topSongs) signature.append(song.getPath()).append(song.getTitle());
        if (signature.toString().equals(lastTopLibrary)) return; lastTopLibrary = signature.toString();
        if (topSongs.isEmpty()) {
            sectionTopLibrary.setVisibility(View.GONE);
            return;
        }
        sectionTopLibrary.setVisibility(View.VISIBLE);
        layoutTopLibraryList.removeAllViews();

        for (Song s : topSongs) {
            View itemView = getLayoutInflater().inflate(R.layout.item_song, layoutTopLibraryList, false);
            TextView title = itemView.findViewById(R.id.txtSongTitle);
            TextView sub = itemView.findViewById(R.id.txtSongSubtitle);
            ImageView cover = itemView.findViewById(R.id.imgSongCover);
            View btnFav = itemView.findViewById(R.id.btnFavorite);
            View btnMenu = itemView.findViewById(R.id.btnSongMenu);

            title.setText(s.getTitle());
            sub.setText(s.getArtist() + " • " + s.getFormattedDuration());
            CoverArtLoader.getInstance().loadSongCover(s.getCoverPath(), cover);

            if (btnFav != null) btnFav.setVisibility(View.GONE);
            if (btnMenu != null) btnMenu.setVisibility(View.GONE);
            itemView.findViewById(R.id.songActionZone).setVisibility(View.GONE);

            itemView.setOnClickListener(v -> {
                PlayerManager.getInstance().setPlaybackSource("songs", "");
                PlayerManager.getInstance().playSong(s, allSongs);
                startActivity(new Intent(MainActivity.this, NowPlayingActivity.class));
            });

            cl.arichard.descargador.util.SafeTap.installRowTap(itemView);
            layoutTopLibraryList.addView(itemView);
        }
    }

    private void triggerSearchOrDownload(String query) {
        SearchHistoryManager.getInstance(this).addSearchQuery(query);
        if (containerSearchDiscovery != null) containerSearchDiscovery.setVisibility(View.GONE);
        if (query == null || query.isEmpty()) return;

        Matcher matcher = YOUTUBE.matcher(query);
        if (matcher.find() || query.startsWith("http://") || query.startsWith("https://")) {
            // Direct URL download
            startDownload(query, null);
        } else {
            // Perform native YouTube search with thumbnail list
            performYoutubeSearch(query);
        }
    }

    private void performYoutubeSearch(String query) {
        final int generation = ++uiState.searchGeneration;
        List<SearchResult> cached = searchCache.get(query.toLowerCase(java.util.Locale.ROOT));
        uiState.results = cached == null ? new ArrayList<>() : new ArrayList<>(cached);
        uiState.resultsVisible = cached != null && !cached.isEmpty();
        uiState.searchLoading = cached == null;
        uiState.searchStatus = cached != null ? cached.size() + cl.arichard.descargador.util.Tr.t(" resultados") : cl.arichard.descargador.util.Tr.t("Buscando en YouTube...");
        uiState.changed();
        if (cached != null) return;
        MainUiState state = uiState;
        DescargadorApp.IO.execute(() -> {
            List<SearchResult> results = new ArrayList<>();
            try {
                try { results.addAll(searchInnerTube(query)); }
                catch (Exception fast) { android.util.Log.w("MusicBoxSearch", "InnerTube falló; usando yt-dlp", fast); }
                if (results.isEmpty()) {
                DescargadorApp app = (DescargadorApp) getApplication();
                if (!app.awaitReady(20)) throw new IllegalStateException(
                        app.getInitError() == null ? cl.arichard.descargador.util.Tr.t("El buscador todavía se está preparando") : app.getInitError());
                String out;
                try {
                    out = executeSearch(query, null);
                } catch (Exception firstAttempt) {
                    android.util.Log.w("MusicBoxSearch", "Búsqueda estándar falló; reintentando con clientes alternativos", firstAttempt);
                    out = executeSearch(query, "web,android");
                }
                if (out == null || out.trim().isEmpty()) out = executeSearch(query, "web,android");
                if (out != null) for (String line : out.split("\\r?\\n")) {
                    line = line.trim();
                    if (!line.startsWith("{")) continue;
                    try {
                        JSONObject json = new JSONObject(line); String id = json.optString("id", "");
                        if (!id.isEmpty()) results.add(new SearchResult(id, json.optString("title", cl.arichard.descargador.util.Tr.t("Sin t\u00edtulo")),
                                json.optString("uploader", json.optString("channel", "YouTube")), json.optLong("duration", 0),
                                "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", "https://www.youtube.com/watch?v=" + id));
                    } catch (Exception ignored) {}
                }
                }
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (state.searchGeneration != generation) return;
                    state.searchLoading = false; state.results = new ArrayList<>(results); state.resultsVisible = !results.isEmpty();
                    state.searchStatus = results.isEmpty() ? cl.arichard.descargador.util.Tr.t("No se encontraron resultados") : results.size() + cl.arichard.descargador.util.Tr.t(" resultados");
                    state.cache.put(query.toLowerCase(java.util.Locale.ROOT), new ArrayList<>(results)); state.changed();
                });
            } catch (Exception error) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (state.searchGeneration != generation) return;
                    state.searchLoading = false;
                    state.searchStatus = ((DescargadorApp) getApplication()).hasNetwork()
                            ? cl.arichard.descargador.util.Tr.t("YouTube no respondió. Toca buscar para reintentar")
                            : cl.arichard.descargador.util.Tr.t("Sin conexión a internet");
                    state.changed();
                });
            }
        });
    }

    /** Fast search through YouTube's internal web endpoint (~0.5 s vs several seconds for yt-dlp). */
    private List<SearchResult> searchInnerTube(String query) throws Exception {
        JSONObject client = new JSONObject().put("clientName", "WEB").put("clientVersion", "2.20250101.00.00")
                .put("hl", "es").put("gl", "CL");
        JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client))
                .put("query", query).put("params", "EgIQAQ%3D%3D");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
            conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
            if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            org.json.JSONArray sections = new JSONObject(buf.toString("UTF-8")).getJSONObject("contents")
                    .getJSONObject("twoColumnSearchResultsRenderer").getJSONObject("primaryContents")
                    .getJSONObject("sectionListRenderer").getJSONArray("contents");
            List<SearchResult> found = new ArrayList<>();
            for (int i = 0; i < sections.length(); i++) {
                org.json.JSONObject section = sections.getJSONObject(i).optJSONObject("itemSectionRenderer");
                if (section == null) continue;
                org.json.JSONArray items = section.optJSONArray("contents");
                if (items == null) continue;
                for (int j = 0; j < items.length() && found.size() < 8; j++) {
                    JSONObject v = items.getJSONObject(j).optJSONObject("videoRenderer");
                    if (v == null) continue;
                    String id = v.optString("videoId", "");
                    if (id.isEmpty()) continue;
                    String title = v.getJSONObject("title").getJSONArray("runs").getJSONObject(0).optString("text", "");
                    JSONObject owner = v.optJSONObject("ownerText");
                    String channel = owner == null ? "YouTube" : owner.getJSONArray("runs").getJSONObject(0).optString("text", "YouTube");
                    long seconds = 0;
                    JSONObject length = v.optJSONObject("lengthText");
                    if (length != null) for (String part : length.optString("simpleText", "0").split(":"))
                        seconds = seconds * 60 + Long.parseLong(part.trim());
                    found.add(new SearchResult(id, title.isEmpty() ? cl.arichard.descargador.util.Tr.t("Sin título") : title,
                            channel, seconds, "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg",
                            "https://www.youtube.com/watch?v=" + id));
                }
            }
            return found;
        } finally {
            conn.disconnect();
        }
    }

    private String executeSearch(String query, String clients) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest("ytsearch8:" + query);
        request.addOption("--flat-playlist");
        request.addOption("--dump-json");
        request.addOption("--socket-timeout", "12");
        request.addOption("--retries", "2");
        request.addOption("--extractor-retries", "2");
        if (clients != null) request.addOption("--extractor-args", "youtube:player_client=" + clients);
        return YoutubeDL.getInstance().execute(request).getOut();
    }

    private void rememberScreenState() {
        if (uiState == null || inputSearch == null) return;
        uiState.searchQuery = inputSearch.getText().toString(); uiState.isMp3 = isMp3;
        uiState.discoveryScroll = containerSearchDiscovery.getScrollY();
        if (recyclerSearchResults.getLayoutManager() instanceof LinearLayoutManager)
            uiState.resultPosition = Math.max(0, ((LinearLayoutManager) recyclerSearchResults.getLayoutManager()).findFirstVisibleItemPosition());
        if (libraryReady) { uiState.library.clear(); uiState.library.addAll(allSongs); uiState.libraryLoaded = true; }
    }
    private void applyScreenState() {
        if (!inputSearch.getText().toString().equals(uiState.searchQuery)) inputSearch.setText(uiState.searchQuery);
        boolean resultsChanged = renderedSearchResults != uiState.results;
        if (resultsChanged) { searchResultAdapter.setResults(uiState.results); renderedSearchResults = uiState.results; }
        recyclerSearchResults.setVisibility(uiState.resultsVisible ? View.VISIBLE : View.GONE);
        searchLoading.setVisibility(uiState.searchLoading ? View.VISIBLE : View.GONE);
        containerSearchDiscovery.setVisibility(uiState.resultsVisible || uiState.searchLoading ? View.GONE : View.VISIBLE);
        txtSearchStatus.setText(uiState.searchStatus);
        findViewById(R.id.searchStatusRow).setVisibility(uiState.searchStatus.isEmpty() ? View.GONE : View.VISIBLE);
        txtSearchHint.setVisibility(View.GONE);
        cardProgress.setVisibility(uiState.downloadVisible ? View.VISIBLE : View.GONE);
        progressBar.setProgress(uiState.downloadProgress); txtStatus.setText(uiState.downloadStatus); txtEta.setText(uiState.downloadEta);
        if (resultsChanged) refreshDownloadedBadges();
        if (renderedCompletedDownloads != uiState.completedDownloads) {
            renderedCompletedDownloads = uiState.completedDownloads;
            refreshMusicLibrary(); refreshDownloadedBadges();
        }
    }
    private void publishDownloadState() {
        uiState.downloadVisible = cardProgress.getVisibility() == View.VISIBLE;
        uiState.downloadProgress = progressBar.getProgress(); uiState.downloadStatus = txtStatus.getText().toString();
        uiState.downloadEta = txtEta.getText().toString(); uiState.changed();
    }

    private void startDownload(String targetUrl, String defaultTitle) {
        startDownload(targetUrl, defaultTitle, extractVideoId(targetUrl));
    }

    private synchronized void startDownload(String targetUrl, String defaultTitle, String videoId) {
        if (videoId != null && !videoId.isEmpty()) {
            activeDownloadingVideoIds.add(videoId);
            if (searchResultAdapter != null) searchResultAdapter.addDownloadingId(videoId);
            if (songAdapter != null) songAdapter.setDownloadStarted(videoId);
        }
        if (uiState.downloading) {
            downloadQueue.offer(new DownloadJob(targetUrl, defaultTitle, videoId));
            return;
        }

        File dir = StorageUtil.getDownloadDir(this);

        YoutubeDLRequest request = new YoutubeDLRequest(targetUrl);
        request.addOption("--no-playlist");
        request.addOption("--no-update");
        request.addOption("--extractor-args", "youtube:player_client=android,web");
        request.addOption("--no-mtime");
        request.addOption("--restrict-filenames");
        request.addOption("-o", new File(dir, "%(title).160s.%(ext)s").getAbsolutePath());

        if (isMp3) {
            request.addOption("-x");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", "192K");
            request.addOption("--embed-thumbnail");
            request.addOption("--add-metadata");
        } else {
            request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
            request.addOption("--merge-output-format", "mp4");
        }

        uiState.downloading = true;
        // Progress is shown by the spinner on each song; the old bottom card covered the list.
        progressBar.setProgress(0);
        txtStatus.setText(defaultTitle != null ? (cl.arichard.descargador.util.Tr.t("Descargando: ") + defaultTitle) : cl.arichard.descargador.util.Tr.t("Iniciando descarga..."));
        txtEta.setText(cl.arichard.descargador.util.Tr.t("Conectando...")); publishDownloadState();

        DescargadorApp.IO.execute(() -> {
            try {
                YoutubeDL.getInstance().execute(request, "mobile-dl", (percent, eta, line) -> {
                    runOnUiThread(() -> {
                        int p = Math.round(percent);
                        if (videoId != null && !videoId.isEmpty()) {
                            float fraction = p > 0 ? p / 100f : -1f;
                            if (searchResultAdapter != null) searchResultAdapter.setDownloadProgress(videoId, fraction);
                            if (songAdapter != null) songAdapter.setDownloadProgress(videoId, fraction);
                        }
                        progressBar.setProgress(p);
                        txtStatus.setText(cl.arichard.descargador.util.Tr.t("Descargando ") + p + "%");
                        txtEta.setText(cl.arichard.descargador.util.Tr.f("Faltan aprox. %d s", (long) eta)); publishDownloadState();
                    });
                    return kotlin.Unit.INSTANCE;
                });

                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    txtStatus.setText(cl.arichard.descargador.util.Tr.t("¡Descarga completada con éxito!"));
                    txtEta.setText(cl.arichard.descargador.util.Tr.t("Guardado en Descargas/Descargador"));
                    Toast.makeText(this, cl.arichard.descargador.util.Tr.t("Guardado en tu biblioteca"), Toast.LENGTH_SHORT).show();
                    StorageUtil.scanFile(this, dir);
                    if (videoId != null && !videoId.isEmpty()) {
                        getSharedPreferences("downloaded_videos", MODE_PRIVATE).edit().putBoolean(videoId, true).apply();
                        activeDownloadingVideoIds.remove(videoId);
                        if (searchResultAdapter != null) searchResultAdapter.markDownloadCompleted(videoId);
                        if (songAdapter != null) songAdapter.setDownloadFinished(videoId, true);
                    }
                    uiState.downloading = false; uiState.completedDownloads++; publishDownloadState();
                    startNextQueuedDownload();

                    cardProgress.postDelayed(() -> {
                        if (!uiState.downloading) { cardProgress.setVisibility(View.GONE); publishDownloadState(); }
                    }, 4000);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatus.setText(cl.arichard.descargador.util.Tr.t("Error al descargar"));
                    txtEta.setText(e.getLocalizedMessage() != null ? e.getLocalizedMessage() : cl.arichard.descargador.util.Tr.t("Fallo en yt-dlp"));
                    cl.arichard.descargador.util.AppNotice.error(findViewById(R.id.bottomBarArea),
                            cl.arichard.descargador.util.Tr.t("No pudimos guardar esta canción. Comprueba la conexión e inténtalo nuevamente."));
                    if (videoId != null && !videoId.isEmpty()) {
                        activeDownloadingVideoIds.remove(videoId);
                        if (searchResultAdapter != null) searchResultAdapter.removeDownloadingId(videoId);
                        if (songAdapter != null) songAdapter.setDownloadFinished(videoId, false);
                    }
                    uiState.downloading = false; publishDownloadState();
                    startNextQueuedDownload();
                });
            }
        });
    }

    private synchronized void startNextQueuedDownload() {
        DownloadJob next = downloadQueue.poll();
        if (next != null) startDownload(next.url, next.title, next.videoId);
    }

    private String extractVideoId(String value) {
        if (value == null) return null;
        Matcher m = YOUTUBE.matcher(value);
        return m.find() ? m.group(1) : (value.matches("[A-Za-z0-9_-]{11}") ? value : null);
    }

    private void refreshDownloadedBadges() {
        if (searchResultAdapter == null) return;
        Set<String> ids = new HashSet<>();
        for (String key : getSharedPreferences("downloaded_videos", MODE_PRIVATE).getAll().keySet()) ids.add(key);
        searchResultAdapter.setDownloadedIds(ids);
        Set<String> titles = new HashSet<>();
        for (Song song : allSongs) titles.add(titleKey(song.getTitle()));
        searchResultAdapter.setDownloadedTitles(titles);
    }

    private void showAddOnlineToPlaylistDialog(SearchResult result) {
        List<String> names = PlaylistManager.getInstance(this).getPlaylistNames();
        String[] arr = names.toArray(new String[0]);
        String[] shown = new String[arr.length];
        for (int i = 0; i < arr.length; i++) shown[i] = cl.arichard.descargador.util.Tr.name(arr[i]);
        new MaterialAlertDialogBuilder(this).setTitle(cl.arichard.descargador.util.Tr.t("Agregar a playlist")).setItems(shown, (d, which) -> {
            String selected = arr[which];
            PlaylistManager manager = PlaylistManager.getInstance(this);
            boolean added = manager.addOnlineTrack(selected, result);
            Toast.makeText(this, added ? cl.arichard.descargador.util.Tr.t("Agregada a ") + selected : cl.arichard.descargador.util.Tr.t("Ya estaba en ") + selected, Toast.LENGTH_SHORT).show();
            searchResultAdapter.notifyDataSetChanged();
        }).setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null).show();
    }

    private void showPlaylistSettingsMenu(String name, View anchor) {
        PlaylistManager manager = PlaylistManager.getInstance(this);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Editar nombre y descripción")).setOnMenuItemClickListener(item -> {
            showEditPlaylistDialog(name); return true;
        });
        boolean auto = manager.isAutoDownloadEnabled(name);
        menu.getMenu().add(auto ? cl.arichard.descargador.util.Tr.t("Desactivar descarga automática") : cl.arichard.descargador.util.Tr.t("Activar descarga automática"))
                .setOnMenuItemClickListener(item -> {
                    if (auto) { manager.setAutoDownloadEnabled(name, false); refreshPlaylistsView(); }
                    else enableAutoDownload(name, this::refreshPlaylistsView);
                    return true;
                });
        if (!"Favoritos".equals(name)) menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Eliminar playlist")).setOnMenuItemClickListener(item -> {
            new MaterialAlertDialogBuilder(this).setTitle(cl.arichard.descargador.util.Tr.t("Eliminar playlist"))
                    .setMessage(cl.arichard.descargador.util.Tr.t("¿Eliminar \"") + name + cl.arichard.descargador.util.Tr.t("\"? Las canciones descargadas se conservarán."))
                    .setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null)
                    .setPositiveButton(cl.arichard.descargador.util.Tr.t("Eliminar"), (dialog, which) -> {
                        manager.deletePlaylist(name); refreshPlaylistsView();
                    }).show();
            return true;
        });
        menu.show();
    }

    private void showEditPlaylistDialog(String oldName) {
        PlaylistManager manager = PlaylistManager.getInstance(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, 0, pad, 0);
        EditText name = new EditText(this);
        name.setHint(cl.arichard.descargador.util.Tr.t("Nombre")); name.setSingleLine(true); name.setText(oldName);
        name.setEnabled(!"Favoritos".equals(oldName));
        EditText description = new EditText(this);
        description.setHint(cl.arichard.descargador.util.Tr.t("Descripción opcional")); description.setMaxLines(3);
        description.setText(manager.getDescription(oldName));
        fields.addView(name); fields.addView(description);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(cl.arichard.descargador.util.Tr.t("Configurar playlist"))
                .setView(fields).setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null).setPositiveButton(cl.arichard.descargador.util.Tr.t("Guardar"), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String updated = name.getText().toString().trim();
            if (!manager.updatePlaylistDetails(oldName, updated, description.getText().toString())) {
                name.setError(cl.arichard.descargador.util.Tr.t("Elige un nombre nuevo y válido")); return;
            }
            refreshPlaylistsView(); dialog.dismiss();
        }));
        dialog.show();
    }

    private void setupLibraryTab() {
        recyclerLibrary.setLayoutManager(new LinearLayoutManager(this));
        recyclerLibrary.setHasFixedSize(true);
        recyclerLibrary.setItemViewCacheSize(24);
        ((cl.arichard.descargador.util.FastScrollView) findViewById(R.id.libraryFastScroll)).attach(recyclerLibrary);

        songAdapter = new SongAdapter(this, allSongs, new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(Song song, List<Song> queue, int position) {
                saveLibraryNavigation();
                rememberPlaybackSource();
                List<Song> local = preferLocalCopies(queue);
                int index = queue.indexOf(song);
                PlayerManager.getInstance().playSong(index >= 0 ? local.get(index) : song, local);
                Intent intent = new Intent(MainActivity.this, NowPlayingActivity.class);
                startActivity(intent);
            }

            @Override
            public void onFavoriteClick(Song song, int position) {
                // In Favoritos the row stays (with an empty heart, so it can be undone) until the list is rebuilt.
                if (isViewingSubList && currentLibraryMode == 3 && "Favoritos".equals(currentSubListName)) {
                    if (PlaylistManager.getInstance(MainActivity.this).isFavoriteSong(song)) heldFavorites.remove(song.getPath());
                    else heldFavorites.put(song.getPath(), new Object[]{song, position});
                    return;
                }
                saveLibraryPosition();
                rebuildLibraryContent(); restoreLibraryPosition();
            }

            @Override
            public void onMenuClick(Song song, View anchor, int position) {
                showSongMenu(song, anchor);
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionBar(count);
            }
        });

        folderAdapter = new FolderAdapter(this, null, this::showFolderSongs);

        playlistAdapter = new PlaylistAdapter(this, null, new PlaylistAdapter.OnPlaylistClickListener() {
            @Override
            public void onPlaylistClick(String playlistName) {
                showPlaylistSongs(playlistName);
            }

            @Override
            public void onDeleteClick(String playlistName, View anchor) {
                if (anchor != null) { showPlaylistSettingsMenu(playlistName, anchor); return; }
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(cl.arichard.descargador.util.Tr.t("Eliminar Playlist"))
                        .setMessage(cl.arichard.descargador.util.Tr.t("¿Deseas eliminar '") + playlistName + "'?")
                        .setPositiveButton(cl.arichard.descargador.util.Tr.t("Eliminar"), (d, w) -> {
                            PlaylistManager.getInstance(MainActivity.this).deletePlaylist(playlistName);
                            refreshPlaylistsView();
                        })
                        .setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null)
                        .show();
            }

            @Override
            public void onAutoDownloadChanged(String playlistName, boolean enabled) {
                if (enabled) enableAutoDownload(playlistName, null);
            }
        });

        recyclerLibrary.setAdapter(songAdapter);

        chipSongs.setOnClickListener(v -> setLibraryMode(0));
        chipFolders.setOnClickListener(v -> setLibraryMode(2));
        chipPlaylists.setOnClickListener(v -> setLibraryMode(3));

        findViewById(R.id.btnLocatePlaying).setOnClickListener(v -> locatePlayingSong());
        recyclerLibrary.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) saveLibraryPosition();
            }
        });
        btnBackLibrary.setOnClickListener(v -> setLibraryMode(currentLibraryMode));

        btnShuffleAll.setOnClickListener(v -> {
            List<Song> collection = (currentLibraryMode == 0 || isViewingSubList) ? songAdapter.getDisplayedList() : allSongs;
            if (!collection.isEmpty()) {
                List<Song> shuffled = new ArrayList<>(collection);
                Collections.shuffle(shuffled);
                rememberPlaybackSource();
                PlayerManager.getInstance().playSong(shuffled.get(0), shuffled);
            } else {
                Toast.makeText(this, cl.arichard.descargador.util.Tr.t("No hay canciones para reproducir"), Toast.LENGTH_SHORT).show();
            }
        });

        inputSearchLibrary.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { saveLibraryPosition(); }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyLibraryFilter(s != null ? s.toString() : "");
                restoreLibraryPosition();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnNewPlaylist.setOnClickListener(v -> showCreatePlaylistDialog());
    }


    private void applyLibraryFilter(String query) {
        if (btnClearLibraryFilter != null) {
            btnClearLibraryFilter.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        }

        if (currentLibraryMode == 0 || isViewingSubList) {
            if (songAdapter != null) {
                songAdapter.filter(query);
                updateLibraryCounter();
            }
        } else if (currentLibraryMode == 2) {
            if (folderAdapter != null) {
                folderAdapter.filter(query);
                int count = folderAdapter.getDisplayedCount();
                txtSongCount.setText(count + (count == 1 ? cl.arichard.descargador.util.Tr.t(" carpeta") : cl.arichard.descargador.util.Tr.t(" carpetas")));
            }
        } else if (currentLibraryMode == 3) {
            if (playlistAdapter != null) {
                playlistAdapter.filter(query);
                int count = playlistAdapter.getDisplayedCount();
                txtSongCount.setText(count + (count == 1 ? cl.arichard.descargador.util.Tr.t(" playlist") : " playlists"));
            }
        }
    }

    private void updateLibraryCounter() {
        if (songAdapter != null && txtSongCount != null) {
            int count = songAdapter.getItemCount();
            txtSongCount.setText(count + (count == 1 ? cl.arichard.descargador.util.Tr.t(" canción") : cl.arichard.descargador.util.Tr.t(" canciones")));
        }
    }

    private void setLibraryMode(int mode) {
        saveLibraryPosition(); changingLibrary = true;
        currentLibraryMode = mode == 1 ? 3 : Math.max(0, Math.min(3, mode));
        currentSubListName = ""; isViewingSubList = false; heldFavorites.clear();
        inputSearchLibrary.setText("");
        updateLibraryHeader(); rebuildLibraryContent(); changingLibrary = false; restoreLibraryPosition();
    }

    private void updateLibraryHeader() {
        btnBackLibrary.setVisibility(isViewingSubList ? View.VISIBLE : View.GONE);
        String headerTitle = cl.arichard.descargador.util.Tr.t("Mi Música");
        if (isViewingSubList) {
            headerTitle = cl.arichard.descargador.util.Tr.name(currentSubListName);
        }
        txtLibraryTitle.setText(headerTitle);
        btnNewPlaylist.setVisibility(currentLibraryMode == 3 && !isViewingSubList ? View.VISIBLE : View.GONE);
        if (switchLibraryAutoDownload != null) {
            boolean canAutoDl = isViewingSubList && currentLibraryMode == 3;
            if (layoutAutoDownloadPill != null) {
                layoutAutoDownloadPill.setVisibility(canAutoDl ? View.VISIBLE : View.GONE);
            } else {
                switchLibraryAutoDownload.setVisibility(canAutoDl ? View.VISIBLE : View.GONE);
            }
            if (canAutoDl) {
                String targetKey = currentSubListName;
                switchLibraryAutoDownload.setChecked(PlaylistManager.getInstance(this).isAutoDownloadEnabled(targetKey));
            }
        }
        TextView[] chips = {chipSongs, chipPlaylists, chipFolders};
        int[] modes = {0, 3, 2};
        for (int i = 0; i < chips.length; i++) {
            boolean selected = modes[i] == currentLibraryMode;
            chips[i].setBackgroundResource(selected ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chips[i].setTextColor(ContextCompat.getColor(this, selected ? R.color.chip_active_text : R.color.text_secondary));
        }
    }

    private void rebuildLibraryContent() {
        playlistAdapter.setLibrary(allSongs);
        if (currentLibraryMode == 0 || isViewingSubList) {
            List<Song> songs = allSongs;
            if (isViewingSubList && currentLibraryMode == 3) {
                songs = PlaylistManager.getInstance(this).resolveSongs(currentSubListName, allSongs);
                if (!heldFavorites.isEmpty() && "Favoritos".equals(currentSubListName)) {
                    songs = new ArrayList<>(songs);
                    for (Object[] held : heldFavorites.values()) {
                        Song heldSong = (Song) held[0];
                        boolean present = false;
                        for (Song candidate : songs) if (candidate.getPath().equals(heldSong.getPath())) { present = true; break; }
                        if (!present) songs.add(Math.min((Integer) held[1], songs.size()), heldSong);
                    }
                }
            }
            else if (isViewingSubList) {
                songs = MusicScanner.groupByFolder(allSongs).get(currentSubListName);
                if (songs == null) songs = Collections.emptyList();
            }
            songAdapter.updateSongs(songs);
            if (recyclerLibrary.getAdapter() != songAdapter) recyclerLibrary.setAdapter(songAdapter);
        } else if (currentLibraryMode == 2) {
            folderAdapter.updateFolders(MusicScanner.groupByFolder(allSongs));
            if (recyclerLibrary.getAdapter() != folderAdapter) recyclerLibrary.setAdapter(folderAdapter);
        } else {
            playlistAdapter.updatePlaylists(PlaylistManager.getInstance(this).getPlaylistNames());
            if (recyclerLibrary.getAdapter() != playlistAdapter) recyclerLibrary.setAdapter(playlistAdapter);
        }
        applyLibraryFilter(inputSearchLibrary.getText().toString());
    }

    private void refreshPlaylistsView() {
        saveLibraryPosition(); rebuildLibraryContent(); restoreLibraryPosition();
    }

    private void showPlaylistSongs(String playlistName) {
        saveLibraryPosition(); changingLibrary = true;
        currentSubListName = playlistName; isViewingSubList = true; heldFavorites.clear();
        inputSearchLibrary.setText("");
        updateLibraryHeader(); rebuildLibraryContent(); changingLibrary = false; restoreLibraryPosition();
    }

    private void showFolderSongs(String name, List<Song> songs) {
        saveLibraryPosition(); changingLibrary = true;
        currentSubListName = name; isViewingSubList = true; heldFavorites.clear();
        inputSearchLibrary.setText("");
        updateLibraryHeader(); rebuildLibraryContent(); changingLibrary = false; restoreLibraryPosition();
    }

    private void showCreatePlaylistDialog() {
        EditText input = new EditText(this);
        input.setHint(cl.arichard.descargador.util.Tr.t("Nombre de la playlist"));
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad, pad, pad);
        container.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(cl.arichard.descargador.util.Tr.t("Nueva Playlist"))
                .setView(container)
                .setPositiveButton(cl.arichard.descargador.util.Tr.t("Crear"), (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        PlaylistManager.getInstance(this).createPlaylist(name);
                        refreshPlaylistsView();
                    }
                })
                .setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null)
                .show();
    }

    private void showSongMenu(Song song, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Reproducir a continuación")).setOnMenuItemClickListener(item -> {
            PlayerManager.getInstance().enqueue(song, true); return true;
        });
        popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Agregar a la cola")).setOnMenuItemClickListener(item -> {
            PlayerManager.getInstance().enqueue(song, false); return true;
        });
        popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Agregar a playlist")).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.addToPlaylist(this, song, this::refreshPlaylistsView); return true;
        });
        if (song.isOnline()) popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Descargar")).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.download(this, song, this::refreshMusicLibrary); return true;
        });
        if (currentLibraryMode == 3 && isViewingSubList) popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Quitar de esta playlist")).setOnMenuItemClickListener(item -> {
            PlaylistManager.getInstance(this).removeSongFromPlaylist(currentSubListName, song.getPath());
            refreshPlaylistsView(); return true;
        });
        if (!song.isOnline()) popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Compartir archivo")).setOnMenuItemClickListener(item -> {
            shareSong(song); return true;
        });
        if (!song.isOnline()) popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Eliminar del dispositivo")).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.delete(this, song, this::refreshMusicLibrary); return true;
        });
        popup.getMenu().add(cl.arichard.descargador.util.Tr.t("Seleccionar canciones")).setOnMenuItemClickListener(item -> {
            if (songAdapter != null) songAdapter.activateSelectionMode(song);
            return true;
        });
        popup.show();
    }

    private void updateSelectionBar(int count) {
        if (selectionBar == null) return;
        if (count > 0) {
            selectionBar.setVisibility(View.VISIBLE);
            if (txtSelectionCount != null) {
                txtSelectionCount.setText(cl.arichard.descargador.util.Tr.f(count > 1 ? "%d seleccionadas" : "%d seleccionada", count));
            }
            if (btnSelectAll != null && songAdapter != null) {
                boolean all = count == songAdapter.getDisplayedList().size() && count > 0;
                btnSelectAll.setText(all ? cl.arichard.descargador.util.Tr.t("Deseleccionar") : cl.arichard.descargador.util.Tr.t("Todas"));
            }
        } else {
            selectionBar.setVisibility(View.GONE);
        }
    }

    private void shareSong(Song song) {
        try {
            File f = new File(song.getPath());
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("audio/*");
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, cl.arichard.descargador.util.Tr.t("Compartir música")));
        } catch (Exception e) {
            Toast.makeText(this, cl.arichard.descargador.util.Tr.t("No se pudo compartir"), Toast.LENGTH_SHORT).show();
        }
    }

    private void locatePlayingSong() {
        Song current = PlayerManager.getInstance().getCurrentSong();
        if (current == null) { Toast.makeText(this, cl.arichard.descargador.util.Tr.t("Elige una canción primero"), Toast.LENGTH_SHORT).show(); return; }
        int index = -1;
        if (currentLibraryMode == 0 || isViewingSubList) {
            List<Song> visible = songAdapter.getDisplayedList();
            for (int i = 0; i < visible.size(); i++) if (current.getPath().equals(visible.get(i).getPath())) { index = i; break; }
        }
        if (index < 0) {
            inputSearchLibrary.setText("");
            if (current.isOnline()) {
                String containing = null;
                for (String name : PlaylistManager.getInstance(this).getPlaylistNames())
                    if (PlaylistManager.getInstance(this).isInPlaylist(name, current)) { containing = name; break; }
                if (containing == null) {
                    cl.arichard.descargador.player.QueueSheet.show(this); return;
                }
                setLibraryMode(3); showPlaylistSongs(containing);
            } else setLibraryMode(0);
            List<Song> visible = songAdapter.getDisplayedList();
            for (int i = 0; i < visible.size(); i++) if (current.getPath().equals(visible.get(i).getPath())) { index = i; break; }
        }
        if (index >= 0) {
            final int target = index;
            recyclerLibrary.post(() -> recyclerLibrary.smoothScrollToPosition(target));
        } else Toast.makeText(this, cl.arichard.descargador.util.Tr.t("La canción no está en tu biblioteca"), Toast.LENGTH_SHORT).show();
    }

    private void setupMiniPlayer() {
        findViewById(R.id.btnMiniQueue).setOnClickListener(v -> cl.arichard.descargador.player.QueueSheet.show(this));
        miniPlayerLayout.setOnClickListener(v -> {
            if (PlayerManager.getInstance().getCurrentSong() != null) {
                Intent intent = new Intent(MainActivity.this, NowPlayingActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.slide_up, R.anim.fade_out);
            }
        });

        ((cl.arichard.descargador.util.SwipeActionsLayout) miniPlayerLayout).setSwipeActions(
                () -> animateMiniTrackChange(-1, () -> PlayerManager.getInstance().playNext()),
                () -> animateMiniTrackChange(1, () -> PlayerManager.getInstance().skipPrevious()),
                () -> miniPlayerLayout.performClick());

        ((cl.arichard.descargador.util.SwipeActionsLayout) miniPlayerLayout).setVerticalDragListener(
                new cl.arichard.descargador.util.SwipeActionsLayout.VerticalDragListener() {
                    @Override public void onDrag(float distance) { previewPlayer(distance); }
                    @Override public void onEnd(float distance, boolean cancelled) { releasePlayerPreview(distance, cancelled); }
                });
        btnMiniPlayPause.setOnClickListener(v -> PlayerManager.getInstance().togglePlayPause());
        ((cl.arichard.descargador.util.SwipeActionsLayout) miniPlayerLayout).setHorizontalDragListener(
                new cl.arichard.descargador.util.SwipeActionsLayout.HorizontalDragListener() {
                    @Override public void onDrag(float distance) {
                        if (miniMotionRunning) return;
                        miniTrackContent.animate().cancel();
                        miniTrackContent.setTranslationX(distance * .65f);
                        miniTrackContent.setAlpha(Math.max(.35f, 1f - Math.abs(distance) / Math.max(1, miniTrackContent.getWidth())));
                    }
                    @Override public void onEnd(boolean committed) {
                        if (!committed && !miniMotionRunning) miniTrackContent.animate().translationX(0).alpha(1).setDuration(200).start();
                    }
                });
        btnMiniNext.setOnClickListener(v -> animateMiniTrackChange(-1, () -> PlayerManager.getInstance().playNext()));
    }

    private void previewPlayer(float distance) {
        if (PlayerManager.getInstance().getCurrentSong() == null) return;
        android.view.ViewGroup scene = findViewById(R.id.mainGestureRoot);
        if (playerPreview == null) {
            playerPreviewCommitted = false;
            playerPreviewScrim = new View(this); playerPreviewScrim.setBackgroundColor(0xFF000000);
            playerPreviewScrim.setAlpha(0); scene.addView(playerPreviewScrim, new android.widget.RelativeLayout.LayoutParams(-1, -1));
            playerPreview = getLayoutInflater().inflate(R.layout.activity_now_playing, scene, false);
            scene.addView(playerPreview, new android.widget.RelativeLayout.LayoutParams(-1, -1));
            // The queue sheet only makes sense in the real screen, where it has its drag behavior.
            playerPreview.findViewById(R.id.queueSheet).setVisibility(View.GONE);
            ((TextView) playerPreview.findViewById(R.id.txtPlayingFromName)).setText(PlayerManager.getInstance().playingFromLabel());
            Song song = PlayerManager.getInstance().getCurrentSong();
            ((TextView) playerPreview.findViewById(R.id.txtNowPlayingTitle)).setText(song.getTitle());
            ((TextView) playerPreview.findViewById(R.id.txtNowPlayingArtist)).setText(song.getArtist());
            CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), playerPreview.findViewById(R.id.imgNowPlayingCover));
            ((ImageView) playerPreview.findViewById(R.id.btnPlayPauseIcon)).setImageResource(PlayerManager.getInstance().isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            android.widget.SeekBar seek = playerPreview.findViewById(R.id.seekBar);
            seek.setMax(Math.max(1, PlayerManager.getInstance().getDuration())); seek.setProgress(PlayerManager.getInstance().getCurrentPosition());
            ((TextView) playerPreview.findViewById(R.id.txtCurrentTime)).setText(playerTime(PlayerManager.getInstance().getCurrentPosition()));
            ((TextView) playerPreview.findViewById(R.id.txtTotalTime)).setText(playerTime(PlayerManager.getInstance().getDuration()));
            playerPreview.findViewById(R.id.tabSelectorMp4).setVisibility(PlayerManager.getInstance().hasVideo() ? View.VISIBLE : View.GONE);
            int repeat = PlayerManager.getInstance().getRepeatMode();
            ImageView repeatIcon = playerPreview.findViewById(R.id.btnRepeat);
            repeatIcon.setImageResource(repeat == PlayerManager.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
            repeatIcon.setAlpha(repeat == PlayerManager.REPEAT_OFF ? .45f : 1f);
            repeatIcon.setColorFilter(ContextCompat.getColor(this, repeat == PlayerManager.REPEAT_OFF ? R.color.text_primary : R.color.accent));
            ImageView shuffleIcon = playerPreview.findViewById(R.id.btnShuffle);
            boolean shuffle = PlayerManager.getInstance().isShuffle();
            shuffleIcon.setAlpha(shuffle ? 1f : .45f);
            shuffleIcon.setColorFilter(ContextCompat.getColor(this, shuffle ? R.color.accent : R.color.text_primary));
            ImageView favoriteIcon = playerPreview.findViewById(R.id.btnNowPlayingFavorite);
            boolean favorite = song.isOnline()
                    ? PlaylistManager.getInstance(this).isOnlineTrackInPlaylist("Favoritos", song.getVideoId())
                    : PlaylistManager.getInstance(this).isFavorite(song.getPath());
            favoriteIcon.setImageResource(favorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
            favoriteIcon.clearColorFilter();
            ((TextView) playerPreview.findViewById(R.id.txtRepeatMode)).setText(repeat == PlayerManager.REPEAT_ONE ? cl.arichard.descargador.util.Tr.t("Repetir canci\u00f3n") : repeat == PlayerManager.REPEAT_ALL ? cl.arichard.descargador.util.Tr.t("Repetir lista") : cl.arichard.descargador.util.Tr.t("Bucle desactivado"));
        }
        playerPreview.animate().cancel();
        float height = Math.max(1, scene.getHeight());
        playerPreview.setTranslationY(Math.max(0, Math.min(height, height + distance)));
        playerPreviewScrim.setAlpha(Math.min(.35f, Math.max(0, -distance / height) * .35f));
    }
    private String playerTime(int ms) { return String.format(java.util.Locale.ROOT, "%02d:%02d", ms / 60000, (ms / 1000) % 60); }
    private void releasePlayerPreview(float distance, boolean cancelled) {
        if (playerPreview == null) return;
        float height = Math.max(1, findViewById(R.id.mainGestureRoot).getHeight());
        boolean open = !cancelled && -distance > Math.min(height * .22f, 160 * getResources().getDisplayMetrics().density);
        playerPreviewCommitted = open;
        playerPreview.animate().translationY(open ? 0 : height).setDuration(230)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(() -> {
                    if (open) {
                        startActivity(new Intent(this, NowPlayingActivity.class)); overridePendingTransition(0, 0);
                    } else clearPlayerPreview();
                }).start();
        playerPreviewScrim.animate().alpha(open ? .35f : 0).setDuration(230).start();
    }
    private void clearPlayerPreview() {
        android.view.ViewGroup scene = findViewById(R.id.mainGestureRoot);
        if (playerPreview != null) { playerPreview.animate().cancel(); scene.removeView(playerPreview); playerPreview = null; }
        if (playerPreviewScrim != null) { scene.removeView(playerPreviewScrim); playerPreviewScrim = null; }
        playerPreviewCommitted = false;
    }

    private void animateMiniTrackChange(int direction, Runnable change) {
        if (miniMotionRunning) return;
        miniMotionRunning = true;
        final int generation = ++miniMotionGeneration;
        miniTrackContent.animate().cancel();
        Song before = PlayerManager.getInstance().getCurrentSong();
        float width = Math.max(1, miniTrackContent.getWidth());
        miniTrackContent.animate().translationX(direction * width).alpha(0).setDuration(140)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (generation != miniMotionGeneration) return;
                    change.run();
                    Song after = PlayerManager.getInstance().getCurrentSong();
                    boolean changed = before != null && after != null && !before.getPath().equals(after.getPath());
                    miniTrackContent.setTranslationX(changed ? -direction * width * .7f : 0);
                    miniTrackContent.animate().translationX(0).alpha(1).setDuration(240)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(() -> miniMotionRunning = false).start();
                }).start();
    }

    private void setMiniPlayerVisible(boolean visible) {
        int target = visible ? View.VISIBLE : View.GONE;
        if (miniPlayerLayout.getVisibility() == target) return;
        android.view.ViewGroup scene = findViewById(R.id.mainGestureRoot);
        if (scene.isLaidOut()) {
            android.transition.TransitionSet transition = new android.transition.TransitionSet();
            android.transition.ChangeBounds bounds = new android.transition.ChangeBounds();
            bounds.addTarget(R.id.bottomBarArea).addTarget(R.id.navIsland);
            android.transition.Fade fade = new android.transition.Fade(); fade.addTarget(R.id.miniPlayerLayout);
            transition.addTransition(bounds).addTransition(fade).setDuration(320)
                    .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
            android.transition.TransitionManager.beginDelayedTransition(scene, transition);
        }
        ((LinearLayout.LayoutParams) findViewById(R.id.navIsland).getLayoutParams()).bottomMargin =
                Math.round((visible ? 8 : 20) * getResources().getDisplayMetrics().density);
        miniPlayerLayout.setVisibility(target);
    }

    private void checkPermissionsAndScan() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean alreadyAsked = prefs.getBoolean("all_permissions_requested_v1", false);

        if (!alreadyAsked) {
            prefs.edit().putBoolean("all_permissions_requested_v1", true).apply();
            List<String> permissions = new ArrayList<>();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }

            List<String> needed = new ArrayList<>();
            for (String p : permissions) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(p);
                }
            }

            if (!needed.isEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 101);
            }

        }
        refreshMusicLibrary();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshMusicLibrary();
        }
    }

    private void refreshMusicLibrary() {
        final int request = ++scanGeneration;
        if (allSongs.isEmpty() && libraryLoading != null) {
            libraryLoading.setVisibility(View.VISIBLE);
            recyclerLibrary.setVisibility(View.GONE);
        }
        DescargadorApp.IO.execute(() -> {
            List<Song> songs = MusicScanner.scanAllMusic(this);
            runOnUiThread(() -> {
                if (request != scanGeneration || isFinishing() || isDestroyed()) return;
                if (libraryLoading != null) libraryLoading.setVisibility(View.GONE);
                recyclerLibrary.setVisibility(View.VISIBLE);
                saveLibraryPosition();
                allSongs.clear(); allSongs.addAll(songs);
                if (!libraryReady) {
                    restoreLibraryNavigation(); libraryReady = true;
                } else { rebuildLibraryContent(); restoreLibraryPosition(); }
                uiState.library.clear(); uiState.library.addAll(allSongs); uiState.libraryLoaded = true;
                refreshDownloadedBadges(); refreshDiscoveryContent();
                PlaylistManager.getInstance(this).linkLocalSongs(allSongs);
                cl.arichard.descargador.data.ClickStats.getInstance(this).migrateLegacy(SearchHistoryManager.getInstance(this), allSongs);
                reconcileAutoDownloads();
            });
        });
    }

    /** YouTube entries that are already saved on this device play from the file: instant, and no streaming needed. */
    private List<Song> preferLocalCopies(List<Song> queue) {
        java.util.Map<String, Song> byTitle = new java.util.HashMap<>();
        PlaylistManager playlistManager = PlaylistManager.getInstance(this);
        for (Song candidate : allSongs)
            if (!candidate.isOnline() && candidate.getPath() != null && new File(candidate.getPath()).exists()) {
                byTitle.putIfAbsent(titleKey(candidate.getTitle()), candidate);
                String id = playlistManager.videoIdFor(candidate.getPath());
                if (id != null) byTitle.putIfAbsent("id:" + id, candidate);
            }
        List<Song> result = new ArrayList<>(queue.size());
        for (Song entry : queue) {
            Song local = entry.isOnline() ? byTitle.getOrDefault("id:" + entry.getVideoId(), byTitle.get(titleKey(entry.getTitle()))) : null;
            result.add(local != null ? local : entry);
        }
        return result;
    }

    private static String titleKey(String title) {
        return java.text.Normalizer.normalize(title == null ? "" : title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    /** Tracks that are neither saved on this device nor being fetched right now. */
    private List<SearchResult> missingDownloads(Iterable<SearchResult> candidates) {
        SharedPreferences saved = getSharedPreferences("downloaded_videos", MODE_PRIVATE);
        Set<String> localTitles = new HashSet<>();
        for (Song song : allSongs) localTitles.add(titleKey(song.getTitle()));
        List<SearchResult> missing = new ArrayList<>();
        for (SearchResult track : candidates) {
            if (saved.getBoolean(track.getId(), false) || PlaylistManager.getInstance(this).hasLocalCopy(track.getId())) continue;
            if (localTitles.contains(titleKey(track.getTitle()))) {
                // The file is already here (for example from an older version): remember it instead of downloading it again.
                saved.edit().putBoolean(track.getId(), true).apply();
                continue;
            }
            if (!activeDownloadingVideoIds.contains(track.getId())) missing.add(track);
        }
        return missing;
    }

    /**
     * Auto-download is level-triggered: whatever is missing from an auto-download playlist gets fetched, whether it
     * was added here, synced from another device, or was already in the playlist when auto-download was switched on.
     */
    private void reconcileAutoDownloads() {
        if (!libraryReady) return;
        Set<String> skipped = getSharedPreferences("auto_download", MODE_PRIVATE).getStringSet("skipped", java.util.Collections.emptySet());
        int queued = 0;
        for (SearchResult track : missingDownloads(PlaylistManager.getInstance(this).autoDownloadTracks())) {
            if (skipped.contains(track.getId()) || !autoAttempted.add(track.getId())) continue;
            startDownload(track.getUrl(), track.getTitle(), track.getId());
            queued++;
        }
    }

    /** Turns auto-download on and offers to fetch what the playlist already holds. */
    private void enableAutoDownload(String playlist, Runnable done) {
        PlaylistManager manager = PlaylistManager.getInstance(this);
        manager.setAutoDownloadEnabled(playlist, true);
        List<SearchResult> tracks = new ArrayList<>();
        for (String entry : manager.getSongPaths(playlist)) {
            SearchResult track = manager.getOnlineTrack(entry);
            if (track != null) tracks.add(track);
        }
        List<SearchResult> pending = missingDownloads(tracks);
        if (pending.isEmpty()) { if (done != null) done.run(); return; }
        new MaterialAlertDialogBuilder(this)
                .setTitle(cl.arichard.descargador.util.Tr.t("Descargar las canciones pendientes"))
                .setMessage(cl.arichard.descargador.util.Tr.f("Esta playlist tiene %d canciones sin descargar. ¿Descargarlas ahora?", pending.size()))
                .setPositiveButton(cl.arichard.descargador.util.Tr.t("Descargar"), (dialog, which) -> {
                    for (SearchResult track : pending) {
                        autoAttempted.add(track.getId());
                        startDownload(track.getUrl(), track.getTitle(), track.getId());
                    }
                })
                .setNegativeButton(cl.arichard.descargador.util.Tr.t("Solo las nuevas"), (dialog, which) -> {
                    SharedPreferences prefs = getSharedPreferences("auto_download", MODE_PRIVATE);
                    Set<String> skipped = new HashSet<>(prefs.getStringSet("skipped", java.util.Collections.emptySet()));
                    for (SearchResult track : pending) skipped.add(track.getId());
                    prefs.edit().putStringSet("skipped", skipped).apply();
                })
                .setOnDismissListener(dialog -> { if (done != null) done.run(); })
                .show();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.trim().isEmpty()) {
                switchTab(true);
                inputSearch.setText(sharedText.trim());
                triggerSearchOrDownload(sharedText.trim());
            }
        }
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playerPreview != null && playerPreviewCommitted) clearPlayerPreview();
        refreshDiscoveryContent();
        PlayerManager.getInstance().addListener(this);
        updateMiniPlayer();
        if (libraryReady) refreshMusicLibrary();
        if (songAdapter != null) songAdapter.notifyDataSetChanged();
    }

    private void updateMiniPlayer() {
        if (miniPlayerLayout == null) return;
        Song current = PlayerManager.getInstance().getCurrentSong();
        if (current != null) {
            setMiniPlayerVisible(true);
            if (txtMiniTitle != null) txtMiniTitle.setText(current.getTitle());
            if (txtMiniArtist != null) txtMiniArtist.setText(current.getArtist());
            if (imgMiniCover != null) {
                CoverArtLoader.getInstance().loadSongCover(current.getCoverPath(), imgMiniCover);
            }
            if (btnMiniPlayPause != null) {
                btnMiniPlayPause.setImageResource(PlayerManager.getInstance().isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            }
            showMiniLoading(PlayerManager.getInstance().isLoading());
            int pos = PlayerManager.getInstance().getCurrentPosition();
            int dur = PlayerManager.getInstance().getDuration();
            if (miniProgress != null) {
                miniProgress.setMax(dur > 0 ? dur : 100);
                miniProgress.setProgress(pos);
            }
        } else {
            setMiniPlayerVisible(false);
        }
    }

    @Override
    protected void onPause() {
        PlayerManager.getInstance().savePosition();
        rememberScreenState();
        saveLibraryNavigation();
        super.onPause();
        PlayerManager.getInstance().removeListener(this);
        if (playerPreview != null && playerPreviewCommitted) findViewById(R.id.mainGestureRoot).postDelayed(this::clearPlayerPreview, 180);
    }

    @Override
    public void onTrackChanged(Song song) {
        runOnUiThread(() -> {
            updateMiniPlayer();
            if (songAdapter != null) songAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onPlaybackLoading(boolean loading) {
        runOnUiThread(() -> showMiniLoading(loading));
    }

    /** Swaps the mini player's play icon for the loading ring while a song is being prepared. */
    private void showMiniLoading(boolean loading) {
        if (miniLoadingRing == null || btnMiniPlayPause == null) return;
        miniLoadingRing.setActive(loading);
        btnMiniPlayPause.animate().alpha(loading ? 0f : 1f).setDuration(160).start();
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            if (songAdapter != null) songAdapter.notifyDataSetChanged();
            if (folderAdapter != null) folderAdapter.notifyDataSetChanged();
            if (playlistAdapter != null) playlistAdapter.notifyDataSetChanged();
            if (btnMiniPlayPause != null) {
                btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            }
        });
    }

    @Override
    public void onPlaybackError(String message) {
        runOnUiThread(() -> cl.arichard.descargador.util.AppNotice.error(
                findViewById(R.id.bottomBarArea), message, "REINTENTAR",
                () -> PlayerManager.getInstance().play()));
    }

    @Override
    public void onProgressUpdate(int currentPos, int duration) {
        runOnUiThread(() -> {
            miniProgress.setMax(duration > 0 ? duration : 100);
            miniProgress.setProgress(currentPos);
        });
    }
}
