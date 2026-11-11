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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.MenuItem;
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
import cl.arichard.descargador.adapter.ArtistFilterAdapter;
import cl.arichard.descargador.adapter.PlaylistAdapter;
import cl.arichard.descargador.adapter.SearchResultAdapter;
import cl.arichard.descargador.adapter.SongAdapter;
import cl.arichard.descargador.data.SearchHistoryManager;
import androidx.core.widget.NestedScrollView;
import androidx.cardview.widget.CardView;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.data.RecommendationEngine;
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
import java.util.LinkedHashMap;

public class MainActivity extends AppCompatActivity implements PlayerManager.PlaybackListener {

    /** Verification is deliberately account-free; preview and release keep the real sign-in flow. */
    static boolean isVerificationPackage(String packageName) {
        return packageName != null && packageName.endsWith(".verification");
    }
    protected boolean requiresSignIn() { return !isVerificationPackage(getPackageName()); }
    protected boolean loadsRecommendationsOnStart() { return true; }

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
    private androidx.appcompat.widget.AppCompatAutoCompleteTextView inputSearch;
    private TextView txtSearchStatus, txtSearchHint;
    private View chipSearchAll, chipSearchSongs, chipSearchVideos, chipSearchArtists;
    private LinearLayout layoutSearchArtists;
    private final List<cl.arichard.descargador.util.YtMusicApi.Artist> searchArtists = new ArrayList<>();
    private boolean loadingSearchPage;
    // Discovery Views
    private NestedScrollView containerSearchDiscovery;
    private View sectionRecentQueries, sectionTopCollections, sectionRecentPlays, sectionTopLibrary;
    private LinearLayout layoutRecentChips, layoutTopCollections, layoutRecentPlaysCards, layoutTopLibraryList;
    private TextView btnClearSearchHistory;
    // Para ti
    private View sectionForYou, sectionForYouArtists, forYouScroll, btnForYouRefresh;
    private LinearLayout layoutForYouCards, layoutForYouArtists;
    private TextView txtForYouSubtitle, txtForYouEmpty;
    private RecommendationEngine recommender;
    private String lastForYou;

    private View searchLoading;
    private RecyclerView recyclerSearchResults;
    private SearchResultAdapter searchResultAdapter;
    private LinearLayout cardProgress;
    private TextView txtStatus, txtEta;
    private ProgressBar progressBar;
    private boolean isMp3 = true;
    private String lastRecentQueries, lastTopCollections, lastRecentPlays, lastTopLibrary;
    private MainUiState uiState;
    private boolean restoringSearchText;
    private List<SearchResult> renderedSearchResults;
    private int renderedCompletedDownloads;
    private final Set<String> finishedDownloadJobs = new HashSet<>();
    private final Runnable downloadObserver = () -> runOnUiThread(this::renderManagedDownloads);
    private Map<String, List<SearchResult>> searchCache;

    // Library Tab Views
    private ImageView btnBackLibrary, btnShuffleAll, btnNewPlaylist;
    private View layoutAutoDownloadPill;
    private androidx.appcompat.widget.SwitchCompat switchLibraryAutoDownload;
    private TextView txtLibraryTitle, txtLibraryContext, txtSongCount;
    private TextView btnPlaylistArtist;
    private EditText inputSearchLibrary;
    private ImageView btnClearLibraryFilter;

    private TextView chipSongs, chipFolders, chipPlaylists;
    private RecyclerView recyclerLibrary;
    private View libraryHeader;
    private cl.arichard.descargador.util.CollectionSwipeLayout libraryContentSwipe;
    private cl.arichard.descargador.util.PlaylistSuggestionsView playlistSuggestionsView;
    private int playlistSuggestionsGeneration;
    private String playlistSuggestionsName = "";
    private boolean collectionSwipeAnimating;
    private View libraryLoading;
    private SongAdapter songAdapter;
    private FolderAdapter folderAdapter;
    private PlaylistAdapter playlistAdapter;
    private boolean playlistDragging;
    private String pinnedPlaylistsSignature = "";
    private final Runnable playlistUiObserver = () -> {
        if (isFinishing() || isDestroyed() || playlistDragging || playlistAdapter == null) return;
        refreshPlaylistsView();
        populatePinnedPlaylists();
    };
    private View selectionBar, btnCloseSelection;
    private TextView txtSelectionCount, checkSelectAll, txtSelectAllLabel, txtLibrarySort;
    private View btnSelectAll, btnAddToPlaylistBulk, btnBulkPlay, btnBulkNext, btnBulkQueue, btnBulkDelete, layoutLibraryTools, btnLibrarySort;
    private boolean selectionShown;
    private SongAdapter.Sort savedSort = SongAdapter.Sort.DEFAULT;
    private boolean savedAscending = true;
    private final cl.arichard.descargador.util.SongDeleter.SystemPrompt deletePrompt = cl.arichard.descargador.util.SongDeleter.prompter(this);
    private final List<Song> allSongs = new ArrayList<>();
    private final Set<String> activeDownloadingVideoIds = new HashSet<>();
    /** Videos already attempted by auto-download in this session, so a failure is not retried in a loop. */
    private final Set<String> autoAttempted = new HashSet<>();
    private int currentLibraryMode = 0; // 0=Songs, 1=Favorites, 2=Folders, 3=Playlists
    /** Songs un-hearted while Favoritos is open stay listed (to undo a stray tap) until the user leaves the list. */
    private final java.util.Map<String, Object[]> heldFavorites = new java.util.LinkedHashMap<>();
    private boolean isViewingSubList = false;
    private String currentSubListName = "";
    private boolean libraryReady, changingLibrary, libraryContentDirty;
    private int scanGeneration;
    private int discoveryGeneration, forYouBindGeneration, topCollectionsGeneration, recentPlaysGeneration, topLibraryGeneration;
    private boolean initialUiSettled;
    /** Esqueleto del Inicio mientras llegan las recomendaciones y la biblioteca (solo la primera vez). */
    private cl.arichard.descargador.util.HomeSkeletonView homeSkeleton;
    private int homeReadyMask;
    private static final int HOME_FOR_YOU = 1, HOME_COLLECTIONS = 2, HOME_RECENTS = 4, HOME_LIBRARY = 8, HOME_ALL = 15;
    private boolean libraryScanRunning, libraryScanPending;
    // Local music must not wait behind the extractor's network update at startup.
    private static final java.util.concurrent.ExecutorService LIBRARY_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService ARTIST_IO =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    private SharedPreferences libraryPrefs() { return getSharedPreferences("library_navigation", MODE_PRIVATE); }
    private String libraryPositionKey() {
        return currentLibraryMode + ":" + currentSubListName + ":" + inputSearchLibrary.getText()
                + ":" + (songAdapter == null ? "" : songAdapter.getArtistFilter());
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
                .putString("artist", songAdapter == null ? "" : songAdapter.getArtistFilter())
                .putBoolean("search_tab", currentTabIsSearch).apply();
    }
    private void restoreLibraryNavigation() {
        SharedPreferences prefs = libraryPrefs();
        int mode = prefs.getInt("mode", 0);
        String name = prefs.getString("sublist", ""), query = prefs.getString("query", "");
        String artist = prefs.getString("artist", "");
        if (mode == 1) { mode = 3; name = "Favoritos"; }
        boolean search = prefs.getBoolean("search_tab", true);
        setLibraryMode(mode);
        if (!name.isEmpty() && (mode == 3 || mode == 2) && PlaylistManager.getInstance(this).getPlaylistNames().contains(name)) showPlaylistSongs(name);
        else if (!name.isEmpty() && mode == 2) {
            List<Song> songs = MusicScanner.groupByFolder(allSongs).get(name);
            if (songs != null) showFolderSongs(name, songs);
        }
        inputSearchLibrary.setText(query);
        if (isViewingSubList && currentLibraryMode == 3) {
            songAdapter.setArtistFilter(artist);
            updatePlaylistArtistFilter();
            updateLibraryCounter();
        }
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
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        boolean testRuntime = "robolectric".equals(android.os.Build.FINGERPRINT);
        if (!testRuntime && requiresSignIn() && FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish(); return;
        }
        uiState = new androidx.lifecycle.ViewModelProvider(this).get(MainUiState.class);
        if (!testRuntime) cl.arichard.descargador.util.UpdateChecker.check(this);
        searchCache = uiState.cache; isMp3 = uiState.isMp3;
        allSongs.addAll(uiState.library);
        setContentView(R.layout.activity_main);
        boolean showingLaunchSplash = !uiState.splashShown;
        if (showingLaunchSplash) {
            uiState.splashShown = true;
            cl.arichard.descargador.util.AnimatedSplashView splash =
                    new cl.arichard.descargador.util.AnimatedSplashView(this);
            addContentView(splash, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            splash.start();
            findViewById(R.id.mainGestureRoot).postDelayed(() -> {
                initialUiSettled = true;
                refreshDiscoveryContent();
            }, 1450);
        } else {
            initialUiSettled = true;
        }
        PlayerManager.getInstance().setMainActivityHost(findViewById(R.id.globalWebVideoContainer));

        initViews();
        setupNavigation();
        setupSearchTab();
        setupHomeSkeleton();
        setupLibraryTab();
        PlaylistManager.getInstance(this).setAutoDownloadListener((playlist, track) ->
                runOnUiThread(() -> startDownload(track.getUrl(), track.getTitle(), playlist)));
        setupMiniPlayer();
        if (!testRuntime && requiresSignIn()) {
            cloudSync = new CloudSyncManager(this, () -> runOnUiThread(this::refreshCloudLibraryState));
            cloudSync.start();
        }
        cl.arichard.descargador.util.SafeTap.installButtons(findViewById(R.id.mainGestureRoot));
        if (uiState.libraryLoaded) { restoreLibraryNavigation(); libraryReady = true; }
        applyScreenState();
        uiState.revision.observe(this, revision -> applyScreenState());
        if (showingLaunchSplash && !testRuntime)
            findViewById(R.id.mainGestureRoot).postDelayed(this::checkPermissionsAndScan, 1400);
        else
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
        inputSearch.setThreshold(0);
        inputSearch.setDropDownBackgroundResource(R.drawable.bg_search_field);
        inputSearch.setDropDownVerticalOffset(dp(6));
        inputSearch.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (inputSearch.isPopupShowing()) fitRecentSearches();
        });
        inputSearch.setOnClickListener(v -> showRecentSearches());
        inputSearch.setOnFocusChangeListener((v, focused) -> {
            if (focused) inputSearch.post(this::showRecentSearches);
            else inputSearch.dismissDropDown();
        });
        inputSearch.setOnItemClickListener((parent, view, position, id) -> {
            String query = String.valueOf(parent.getItemAtPosition(position));
            inputSearch.dismissDropDown();
            inputSearch.clearFocus();
            triggerSearchOrDownload(query);
        });
        btnClearSearch = findViewById(R.id.btnClearSearch);
        inputSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (restoringSearchText) return;
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
        sectionForYou = findViewById(R.id.sectionForYou);
        sectionForYouArtists = findViewById(R.id.sectionForYouArtists);
        forYouScroll = findViewById(R.id.forYouScroll);
        btnForYouRefresh = findViewById(R.id.btnForYouRefresh);
        layoutForYouCards = findViewById(R.id.layoutForYouCards);
        layoutForYouArtists = findViewById(R.id.layoutForYouArtists);
        txtForYouSubtitle = findViewById(R.id.txtForYouSubtitle);
        txtForYouEmpty = findViewById(R.id.txtForYouEmpty);
        if (btnForYouRefresh != null) btnForYouRefresh.setOnClickListener(v -> refreshForYou(true));
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
        txtLibraryContext = findViewById(R.id.txtLibraryContext);
        libraryHeader = findViewById(R.id.libraryHeader);
        libraryContentSwipe = findViewById(R.id.libraryContentSwipe);
        setupPlaylistSuggestions();
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

        // Barra de selección múltiple y ordenar
        selectionBar = findViewById(R.id.selectionBar);
        txtSelectionCount = findViewById(R.id.txtSelectionCount);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        checkSelectAll = findViewById(R.id.checkSelectAll);
        txtSelectAllLabel = findViewById(R.id.txtSelectAllLabel);
        btnAddToPlaylistBulk = findViewById(R.id.btnAddToPlaylistBulk);
        btnCloseSelection = findViewById(R.id.btnCloseSelection);
        btnBulkPlay = findViewById(R.id.btnBulkPlay);
        btnBulkNext = findViewById(R.id.btnBulkNext);
        btnBulkQueue = findViewById(R.id.btnBulkQueue);
        btnBulkDelete = findViewById(R.id.btnBulkDelete);
        setupSelectionBar();
        setupLibrarySort();



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
            uiState.loadingMore = false; loadingSearchPage = false; uiState.searchContinuation = "";
            uiState.searchStatus = "";
            uiState.searchFilter = "songs"; uiState.hasMoreResults = true; searchArtists.clear();
            findViewById(R.id.searchFilterRow).setVisibility(View.GONE);
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
        uiState.loadingMore = false; loadingSearchPage = false; uiState.searchContinuation = "";
        uiState.searchStatus = "";
        uiState.searchFilter = "songs"; uiState.hasMoreResults = true; searchArtists.clear();
        findViewById(R.id.searchFilterRow).setVisibility(View.GONE);
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
        if (!isSearch && !pendingPlaylistArrival.isEmpty() && !isViewingSubList) revealImportedPlaylist();
        else if (!isSearch && libraryContentDirty) rebuildLibraryContent();
        settleTabs();
        if (changed && isSearch) refreshDiscoveryContent();
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
            public void onMoreClick(SearchResult result, View anchor) {
                showResultMenu(result, anchor);
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
        // Las filas nuevas ya entran con su propia cascada; el animador por defecto la cortaría.
        recyclerSearchResults.setItemAnimator(null);
        chipSearchAll = findViewById(R.id.chipSearchAll);
        chipSearchSongs = findViewById(R.id.chipSearchSongs);
        chipSearchVideos = findViewById(R.id.chipSearchVideos);
        chipSearchArtists = findViewById(R.id.chipSearchArtists);
        layoutSearchArtists = findViewById(R.id.layoutSearchArtists);
        chipSearchAll.setOnClickListener(v -> setSearchFilter("all"));
        chipSearchSongs.setOnClickListener(v -> setSearchFilter("songs"));
        chipSearchVideos.setOnClickListener(v -> setSearchFilter("videos"));
        chipSearchArtists.setOnClickListener(v -> setSearchFilter("artists"));
        recyclerSearchResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy <= 0 || loadingSearchPage || !uiState.hasMoreResults || uiState.searchLoading) return;
                LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= searchResultAdapter.getItemCount() - 3)
                    postSearchPagination(view);
            }
        });
        findViewById(R.id.scrollSearchArtists).setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (y > oldY && !view.canScrollVertically(1)) postSearchPagination(view);
        });
        renderSearchFilter();

        inputSearch.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnter = (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                inputSearch.dismissDropDown();
                hideKeyboard();
                if (pendingLiveSearch != null) liveSearchHandler.removeCallbacks(pendingLiveSearch);
                String query = inputSearch.getText().toString().trim();
                if (!query.isEmpty()) triggerSearchOrDownload(query);
                return true;
            }
            return false;
        });
    }

    private void setSearchFilter(String filter) {
        if (filter.equals(uiState.searchFilter)) return;
        uiState.searchFilter = filter;
        uiState.searchContinuation = "";
        uiState.results = new ArrayList<>();
        uiState.resultsVisible = true;
        uiState.hasMoreResults = true;
        searchArtists.clear();
        renderSearchFilter();
        String query = inputSearch.getText().toString().trim();
        if (!query.isEmpty()) searchFiltered(query, true);
        else {
            recyclerSearchResults.setVisibility("artists".equals(filter) ? View.GONE : View.VISIBLE);
            findViewById(R.id.scrollSearchArtists).setVisibility("artists".equals(filter) ? View.VISIBLE : View.GONE);
            uiState.searchStatus = cl.arichard.descargador.util.Tr.t("Escribe para buscar");
            uiState.changed();
        }
    }

    private void renderSearchFilter() {
        if (chipSearchSongs == null) return;
        for (Object[] item : new Object[][]{{chipSearchAll,"all"},{chipSearchSongs,"songs"},{chipSearchVideos,"videos"},{chipSearchArtists,"artists"}}) {
            View chip = (View) item[0];
            boolean selected = item[1].equals(uiState.searchFilter);
            chip.setBackgroundResource(selected ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            ((TextView) chip).setTextColor(ContextCompat.getColor(this, selected ? R.color.chip_active_text : R.color.text_primary));
            chip.animate().cancel(); chip.setScaleX(.96f); chip.setScaleY(.96f);
            chip.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
        }
        boolean artists = "artists".equals(uiState.searchFilter);
        boolean searching = uiState.resultsVisible || uiState.searchLoading || uiState.loadingMore || !uiState.searchStatus.isEmpty();
        boolean showResults = uiState.resultsVisible && !uiState.searchLoading;
        containerSearchDiscovery.setVisibility(searching ? View.GONE : View.VISIBLE);
        searchLoading.setVisibility(uiState.searchLoading ? View.VISIBLE : View.GONE);
        findViewById(R.id.searchFilterRow).setVisibility(searching ? View.VISIBLE : View.GONE);
        recyclerSearchResults.setVisibility(!artists && showResults ? View.VISIBLE : View.GONE);
        findViewById(R.id.scrollSearchArtists).setVisibility(artists && showResults ? View.VISIBLE : View.GONE);
        if (artists && layoutSearchArtists.getChildCount() == 0 && !searchArtists.isEmpty()) renderSearchArtists();
    }

    private void searchFiltered(String query, boolean fresh) {
        final int generation = ++uiState.searchGeneration;
        final String filter = uiState.searchFilter;
        final String requestedContinuation = fresh ? "" : uiState.searchContinuation;
        if (fresh) {
            loadingSearchPage = false;
            uiState.activePagedSearchQuery = query;
            uiState.results = new ArrayList<>(); searchArtists.clear(); uiState.searchContinuation = "";
            uiState.hasMoreResults = true; layoutSearchArtists.removeAllViews();
        }
        uiState.searchLoading = fresh;
        uiState.loadingMore = !fresh;
        uiState.resultsVisible = true;
        uiState.searchStatus = cl.arichard.descargador.util.Tr.t(fresh ? "Buscando en YouTube..." : "Cargando más resultados...");
        uiState.changed();
        DescargadorApp.IO.execute(() -> {
            try {
                cl.arichard.descargador.util.YtMusicApi.SearchPage page =
                        cl.arichard.descargador.util.YtMusicApi.searchPage(query, filter, requestedContinuation, 20);
                runOnUiThread(() -> {
                    if (uiState.searchGeneration != generation) return;
                    if ("artists".equals(filter)) {
                        searchArtists.addAll(page.artists);
                        renderSearchArtists();
                    } else {
                        List<SearchResult> merged = new ArrayList<>(uiState.results);
                        Set<String> ids = new HashSet<>();
                        for (SearchResult result : merged) ids.add(result.getId());
                        for (SearchResult result : page.songs) if (ids.add(result.getId())) merged.add(result);
                        uiState.results = merged;
                        // Los primeros resultados se resuelven mientras el usuario mira la lista: al tocarlos ya están listos.
                        if (requestedContinuation.isEmpty() && !"videos".equals(filter))
                            for (int i = 0; i < Math.min(2, uiState.results.size()); i++)
                                PlayerManager.getInstance().prefetchVideo(uiState.results.get(i).getId());
                    }
                    if (uiState.hasMoreResults && page.songs.isEmpty() && page.artists.isEmpty()
                            && !page.continuation.isEmpty() && !page.continuation.equals(requestedContinuation)) {
                        uiState.searchContinuation = page.continuation;
                        loadingSearchPage = false;
                        searchFiltered(query, false);
                        return;
                    }
                    uiState.searchContinuation = page.continuation;
                    uiState.hasMoreResults = !page.continuation.isEmpty()
                            && !page.continuation.equals(requestedContinuation)
                            && (!page.songs.isEmpty() || !page.artists.isEmpty());
                    uiState.searchLoading = false; uiState.loadingMore = false;
                    loadingSearchPage = false;
                    int count = "artists".equals(filter) ? searchArtists.size() : uiState.results.size();
                    uiState.searchStatus = count == 0 ? cl.arichard.descargador.util.Tr.t("No se encontraron resultados")
                            : count + cl.arichard.descargador.util.Tr.t(" resultados");
                    uiState.changed();
                    if ("artists".equals(filter) && uiState.hasMoreResults
                            && !findViewById(R.id.scrollSearchArtists).canScrollVertically(1))
                        postSearchPagination(findViewById(R.id.scrollSearchArtists));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (uiState.searchGeneration != generation) return;
                    if (fresh && !"artists".equals(filter)) {
                        android.util.Log.w("MusicBoxSearch", "YouTube Music no respondió; usando YouTube", error);
                        performPlainYoutubeSearch(query);
                        return;
                    }
                    uiState.searchLoading = false; uiState.loadingMore = false;
                    loadingSearchPage = false;
                    uiState.searchStatus = ((DescargadorApp) getApplication()).hasNetwork()
                            ? cl.arichard.descargador.util.Tr.t("YouTube no respondió. Toca buscar para reintentar")
                            : cl.arichard.descargador.util.Tr.t("Sin conexión a internet");
                    uiState.changed();
                });
            }
        });
    }

    private void postSearchPagination(View view) {
        // The loading footer and results update the adapter, so leave the scroll callback first.
        int generation = uiState.searchGeneration;
        view.post(() -> {
            if (generation != uiState.searchGeneration || !view.isShown() || isFinishing()) return;
            if (loadingSearchPage || uiState.loadingMore || uiState.searchLoading || !uiState.resultsVisible
                    || !uiState.hasMoreResults || uiState.searchContinuation.isEmpty()) return;
            String query = inputSearch.getText().toString().trim();
            if (query.isEmpty() || !query.equals(uiState.activePagedSearchQuery)) return;
            loadingSearchPage = true;
            searchFiltered(query, false);
        });
    }

    private void renderSearchArtists() {
        layoutSearchArtists.removeAllViews();
        for (cl.arichard.descargador.util.YtMusicApi.Artist artist : searchArtists) {
            LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10)); row.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(72)); lp.bottomMargin = dp(8); row.setLayoutParams(lp);
            ImageView photo = new ImageView(this); photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            CardView circle = new CardView(this); circle.setRadius(dp(25)); circle.setCardElevation(0);
            circle.addView(photo, new FrameLayout.LayoutParams(-1,-1)); row.addView(circle, new LinearLayout.LayoutParams(dp(50),dp(50)));
            cl.arichard.descargador.util.ImageLoader.getInstance().load(artist.photo, photo);
            TextView name = new TextView(this); name.setText(artist.name); name.setTextColor(ContextCompat.getColor(this,R.color.text_primary)); name.setTextSize(15); name.setTypeface(null,android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,-2,1); textLp.setMargins(dp(14),0,dp(8),0); row.addView(name,textLp);
            ImageView arrow = new ImageView(this); arrow.setImageResource(R.drawable.ic_arrow_back); arrow.setRotation(180); arrow.setColorFilter(ContextCompat.getColor(this,R.color.text_secondary)); row.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(20)));
            row.setOnClickListener(v -> { Intent intent=new Intent(this,ArtistActivity.class); intent.putExtra(ArtistActivity.EXTRA_ID,artist.browseId); intent.putExtra(ArtistActivity.EXTRA_NAME,artist.name); intent.putExtra(ArtistActivity.EXTRA_PHOTO,artist.photo); startActivity(intent); });
            cl.arichard.descargador.util.SafeTap.installRowTap(row); layoutSearchArtists.addView(row);
        }
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




    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void showResultMenu(SearchResult result, View anchor) {
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
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Ver alternativas")).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongAlternativesSheet.show(MainActivity.this, song, MainActivity.this::refreshPlaylistsView); return true;
        });
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t("Información de la canción")).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongInfo.show(MainActivity.this, song); return true;
        });
        menu.show();
    }

    private void ensureRecommender() {
        if (recommender != null) return;
        recommender = new RecommendationEngine(new RecommendationEngine.Fetcher() {
                @Override public List<SearchResult> related(String videoId) throws Exception {
                    List<SearchResult> songs = cl.arichard.descargador.util.YtMusicApi.related(videoId, RecommendationEngine.PER_SEED);
                    return songs.isEmpty() ? cl.arichard.descargador.util.YoutubeApi.related(videoId, RecommendationEngine.PER_SEED) : songs;
                }
                @Override public List<SearchResult> search(String query) throws Exception {
                    return cl.arichard.descargador.util.YoutubeApi.search(query, 12);
                }
            }, System::currentTimeMillis, getSharedPreferences("recommendations", MODE_PRIVATE));
    }

    private void setupPlaylistSuggestions() {
        playlistSuggestionsView = findViewById(R.id.playlistSuggestions);
        if (playlistSuggestionsView == null) return;
        playlistSuggestionsView.setCollapsed(getSharedPreferences("recommendations", MODE_PRIVATE)
                .getBoolean("playlist_suggestions_collapsed", false), false, false);
        playlistSuggestionsView.setListener(new cl.arichard.descargador.util.PlaylistSuggestionsView.Listener() {
            @Override public void onPlay(SearchResult song) { playOnlineResult(song, false); }
            @Override public void onAdd(SearchResult song) {
                if (currentSubListName.isEmpty()) return;
                PlaylistManager.getInstance(MainActivity.this).addOnlineTrack(currentSubListName, song);
                refreshPlaylistsView();
            }
            @Override public void onRefresh() { refreshPlaylistSuggestions(true); }
            @Override public void onCollapsedChanged(boolean collapsed) {
                getSharedPreferences("recommendations", MODE_PRIVATE).edit()
                        .putBoolean("playlist_suggestions_collapsed", collapsed).apply();
            }
        });
    }

    /** Pide canciones parecidas a las de la playlist abierta (solo de ella) y excluye las que ya tiene. */
    private void refreshPlaylistSuggestions(boolean fresh) {
        if (playlistSuggestionsView == null) return;
        final String name = currentSubListName;
        final int generation = ++playlistSuggestionsGeneration;
        if (!(isViewingSubList && currentLibraryMode == 3) || name.isEmpty()) {
            playlistSuggestionsName = "";
            playlistSuggestionsView.setLoading(false);
            playlistSuggestionsView.setSuggestions(new ArrayList<>());
            updatePlaylistSuggestionsVisibility();
            return;
        }
        ensureRecommender();
        PlaylistManager manager = PlaylistManager.getInstance(this);
        cl.arichard.descargador.data.ClickStats stats = cl.arichard.descargador.data.ClickStats.getInstance(this);
        List<String> ids = new ArrayList<>();
        Set<String> known = new HashSet<>();
        List<String> titles = new ArrayList<>();
        for (String entry : manager.getSongPaths(name)) {
            String id = entry.startsWith("youtube:") ? entry.substring("youtube:".length()) : manager.videoIdFor(entry);
            if (id != null && !id.isEmpty()) { ids.add(id); known.add(id); }
            SearchResult online = manager.getOnlineTrack(entry);
            if (online != null) titles.add(online.getTitle());
        }
        for (Song song : manager.resolveSongs(name, allSongs)) titles.add(song.getTitle());
        List<RecommendationEngine.PlaylistSeed> seeds = RecommendationEngine.playlistSeeds(
                ids, id -> stats.count("yt:" + id), RecommendationEngine.PLAYLIST_SEEDS);
        if (!name.equals(playlistSuggestionsName)) playlistSuggestionsView.setSuggestions(new ArrayList<>());
        playlistSuggestionsName = name;
        if (seeds.isEmpty()) {
            playlistSuggestionsView.setLoading(false);
            updatePlaylistSuggestionsVisibility();
            return;
        }
        // Mientras carga, la tarjeta queda plegada; al terminar se despliega con animación según la preferencia del usuario.
        boolean userCollapsed = getSharedPreferences("recommendations", MODE_PRIVATE)
                .getBoolean("playlist_suggestions_collapsed", false);
        if (!playlistSuggestionsView.hasSuggestions()) playlistSuggestionsView.setCollapsed(true, false, false);
        playlistSuggestionsView.setLoading(true);
        updatePlaylistSuggestionsVisibility();
        DescargadorApp.IO.execute(() -> {
            RecommendationEngine.Result result = recommender.forPlaylist(seeds, known, titles, fresh);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != playlistSuggestionsGeneration) return;
                playlistSuggestionsView.setLoading(false);
                boolean keepPrevious = "offline".equals(result.mode) && playlistSuggestionsView.hasSuggestions();
                if (!keepPrevious) playlistSuggestionsView.setSuggestions(result.songs);
                if (playlistSuggestionsView.isCollapsed() != userCollapsed && playlistSuggestionsView.hasSuggestions())
                    playlistSuggestionsView.setCollapsed(userCollapsed, true, false);
                updatePlaylistSuggestionsVisibility();
            });
        });
    }

    /** La tarjeta solo aparece con una playlist abierta y sin búsqueda ni filtro de artista que cambien la lista. */
    private void updatePlaylistSuggestionsVisibility() {
        if (playlistSuggestionsView == null || inputSearchLibrary == null || songAdapter == null) return;
        boolean wanted = isViewingSubList && currentLibraryMode == 3 && currentSubListName.equals(playlistSuggestionsName)
                && inputSearchLibrary.getText().length() == 0 && songAdapter.getArtistFilter().isEmpty()
                && playlistSuggestionsView.hasContent();
        playlistSuggestionsView.setShown(wanted);
    }

    /** @param fresh ignora lo guardado y pide todo de nuevo: al abrir la app y con el botón de actualizar. */
    private void refreshForYou(boolean fresh) {
        if (uiState == null) return;
        ensureRecommender();
        if (uiState.forYou == null) uiState.forYou = recommender.lastKnown();
        final int generation = ++uiState.forYouGeneration;
        uiState.forYouLoading = true;
        populateForYou();
        List<String> titles = new ArrayList<>();
        for (Song song : allSongs) titles.add(song.getTitle());
        List<String> queries = SearchHistoryManager.getInstance(this).getRecentQueries();
        PlaylistManager playlists = PlaylistManager.getInstance(this);
        DescargadorApp.IO.execute(() -> {
            RecommendationEngine.Result result = recommender.compute(
                    cl.arichard.descargador.data.ClickStats.getInstance(this), playlists::hasLocalCopy, titles, queries,
                    System.currentTimeMillis(), fresh);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != uiState.forYouGeneration) return;
                uiState.forYouLoading = false;
                boolean keepPrevious = "offline".equals(result.mode) && uiState.forYou != null && !uiState.forYou.isEmpty();
                if (!keepPrevious) uiState.forYou = result;
                lastForYou = null;
                populateForYou();
            });
        });
    }

    private void populateForYou() {
        if (sectionForYou == null || layoutForYouCards == null || uiState == null) return;
        if (!initialUiSettled) return;
        // Lista solo cuando la carga ya empezó y terminó (con o sin resultados); antes de empezar aún no hay nada que mostrar.
        if ((uiState.forYouStarted || !loadsRecommendationsOnStart()) && !uiState.forYouLoading) markHomeReady(HOME_FOR_YOU);
        RecommendationEngine.Result result = uiState.forYou;
        boolean hasSongs = result != null && !result.songs.isEmpty();
        boolean loading = uiState.forYouLoading;
        boolean failed = !hasSongs && !loading && result != null && "offline".equals(result.mode);
        StringBuilder signature = new StringBuilder().append(loading).append(failed).append(result == null ? "" : result.mode);
        if (hasSongs) for (SearchResult song : result.songs) signature.append(song.getId());
        if (result != null) for (String artist : result.artists) signature.append('|').append(artist);
        if (signature.toString().equals(lastForYou)) return;
        lastForYou = signature.toString();

        boolean skeleton = !hasSongs && loading;
        sectionForYou.setVisibility(hasSongs || skeleton || failed ? View.VISIBLE : View.GONE);
        btnForYouRefresh.setEnabled(!loading);
        btnForYouRefresh.setAlpha(loading ? .4f : 1f);
        forYouScroll.setVisibility(hasSongs || skeleton ? View.VISIBLE : View.GONE);
        txtForYouEmpty.setVisibility(failed ? View.VISIBLE : View.GONE);
        int subtitle = result == null ? 0 : "seeds".equals(result.mode) ? R.string.segun_lo_que_escuchas
                : "queries".equals(result.mode) ? R.string.segun_tus_busquedas
                : "trending".equals(result.mode) ? R.string.para_empezar_a_descubrir : 0;
        txtForYouSubtitle.setVisibility(subtitle != 0 && hasSongs ? View.VISIBLE : View.GONE);
        if (subtitle != 0) txtForYouSubtitle.setText(subtitle);

        layoutForYouCards.removeAllViews();
        final int bindGeneration = ++forYouBindGeneration;
        if (hasSongs) for (int i = 0; i < result.songs.size(); i++) {
            SearchResult song = result.songs.get(i);
            layoutForYouCards.postDelayed(() -> {
                if (bindGeneration == forYouBindGeneration && !isFinishing() && !isDestroyed())
                    layoutForYouCards.addView(buildForYouCard(song));
            }, i * 18L);
        }
        else if (skeleton) for (int i = 0; i < 3; i++) layoutForYouCards.addView(buildForYouSkeleton());

        layoutForYouArtists.removeAllViews();
        boolean hasArtists = hasSongs && !result.artists.isEmpty();
        sectionForYouArtists.setVisibility(hasArtists ? View.VISIBLE : View.GONE);
        if (hasArtists) for (int i = 0; i < result.artists.size(); i++) {
            String artist = result.artists.get(i);
            long delay = (result.songs.size() + i) * 18L;
            layoutForYouArtists.postDelayed(() -> {
                if (bindGeneration == forYouBindGeneration && !isFinishing() && !isDestroyed())
                    layoutForYouArtists.addView(buildArtistBubble(artist));
            }, delay);
        }
    }

    /** Foto circular y nombre; al tocar abre la discografía del artista, no una búsqueda genérica. */
    private View buildArtistBubble(String artistName) {
        int size = dp(92);
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(dp(112), dp(140));
        outer.setMargins(0, 0, dp(8), 0);
        bubble.setLayoutParams(outer);

        CardView circle = new CardView(this);
        circle.setRadius(size / 2f);
        circle.setCardElevation(0);
        circle.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        circle.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout framed = new FrameLayout(this);
        framed.addView(circle, new FrameLayout.LayoutParams(size, size));
        View ring = new View(this);
        android.graphics.drawable.GradientDrawable oval = new android.graphics.drawable.GradientDrawable();
        oval.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        oval.setStroke(dp(2), ContextCompat.getColor(this, R.color.border));
        ring.setBackground(oval);
        framed.addView(ring, new FrameLayout.LayoutParams(size, size));
        bubble.addView(framed, new LinearLayout.LayoutParams(size, size));

        TextView name = new TextView(this);
        name.setText(artistName);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setTextSize(12f);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        name.setGravity(android.view.Gravity.CENTER);
        name.setMaxLines(2);
        bubble.addView(name, new LinearLayout.LayoutParams(-1, dp(44)));

        final cl.arichard.descargador.util.YtMusicApi.Artist[] resolved = {null};
        bubble.setOnClickListener(v -> {
            cl.arichard.descargador.util.YtMusicApi.Artist a = resolved[0];
            if (a == null) return;
            Intent intent = new Intent(this, ArtistActivity.class);
            intent.putExtra(ArtistActivity.EXTRA_ID, a.browseId);
            intent.putExtra(ArtistActivity.EXTRA_NAME, a.name);
            intent.putExtra(ArtistActivity.EXTRA_PHOTO, a.photo);
            startActivity(intent);
        });
        ARTIST_IO.execute(() -> {
            try {
                cl.arichard.descargador.util.YtMusicApi.Artist found = cl.arichard.descargador.util.YtMusicApi.findArtist(artistName);
                if (found != null) runOnUiThread(() -> {
                    if (!bubble.isAttachedToWindow()) return;
                    resolved[0] = found;
                    cl.arichard.descargador.util.ImageLoader.getInstance().load(found.photo, photo);
                    name.setText(found.name);
                    bubble.setAlpha(1f);
                });
            } catch (Exception ignored) {}
        });
        bubble.setAlpha(.72f);
        cl.arichard.descargador.util.SafeTap.install(bubble);
        return bubble;
    }

    private View buildForYouCard(SearchResult result) {
        int cover = dp(156);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cover, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        card.setLayoutParams(lp);

        FrameLayout frame = new FrameLayout(this);
        androidx.cardview.widget.CardView cv = new androidx.cardview.widget.CardView(this);
        cv.setRadius(dp(16));
        cv.setCardElevation(0f);
        cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cv.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cl.arichard.descargador.util.ImageLoader.getInstance().load(result.getThumbnailUrl(), iv);
        frame.addView(cv, new FrameLayout.LayoutParams(cover, cover));

        ImageView add = new ImageView(this);
        add.setImageResource(R.drawable.ic_add);
        add.setColorFilter(0xFFFFFFFF);
        add.setPadding(dp(7), dp(7), dp(7), dp(7));
        android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
        disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(0x99000000);
        add.setBackground(disc);
        add.setContentDescription(cl.arichard.descargador.util.Tr.t("Agregar a playlist"));
        FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(dp(32), dp(32), android.view.Gravity.END | android.view.Gravity.BOTTOM);
        addLp.setMargins(0, 0, dp(8), dp(8));
        add.setOnClickListener(v -> showResultMenu(result, v));
        cl.arichard.descargador.util.SafeTap.install(add);
        frame.addView(add, addLp);
        card.addView(frame);

        TextView title = new TextView(this);
        title.setText(result.getTitle());
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setTextSize(13f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(2), dp(8), dp(2), 0);
        TextView channel = new TextView(this);
        channel.setText(RecommendationEngine.artistName(result));
        channel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        channel.setTextSize(11f);
        channel.setSingleLine(true);
        channel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        channel.setPadding(dp(2), 0, dp(2), 0);
        card.addView(title);
        card.addView(channel);

        card.setOnClickListener(v -> playOnlineResult(result, true));
        cl.arichard.descargador.util.SafeTap.installRowTap(card);
        return card;
    }

    private View buildForYouSkeleton() {
        int cover = dp(156);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cover, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        card.setLayoutParams(lp);
        androidx.cardview.widget.CardView cv = new androidx.cardview.widget.CardView(this);
        cv.setRadius(dp(16));
        cv.setCardElevation(0f);
        View shimmer = new View(this);
        shimmer.setBackground(new cl.arichard.descargador.util.ShimmerDrawable(this));
        cv.addView(shimmer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.addView(cv, new LinearLayout.LayoutParams(cover, cover));
        for (int width : new int[]{dp(110), dp(70)}) {
            androidx.cardview.widget.CardView bar = new androidx.cardview.widget.CardView(this);
            bar.setRadius(dp(5));
            bar.setCardElevation(0f);
            View fill = new View(this);
            fill.setBackground(new cl.arichard.descargador.util.ShimmerDrawable(this));
            bar.addView(fill, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(width, dp(10));
            barLp.setMargins(dp(2), dp(8), 0, 0);
            card.addView(bar, barLp);
        }
        return card;
    }

    /** Muestra el esqueleto sobre el Inicio hasta que cada sección haya resuelto, para no ver la pantalla vacía. */
    private void setupHomeSkeleton() {
        if ("robolectric".equals(android.os.Build.FINGERPRINT) || uiState == null || uiState.homeSkeletonDone || containerSearchDiscovery == null) return;
        if (!(containerSearchDiscovery.getParent() instanceof FrameLayout)) return;
        FrameLayout host = (FrameLayout) containerSearchDiscovery.getParent();
        homeSkeleton = new cl.arichard.descargador.util.HomeSkeletonView(this);
        homeSkeleton.setPadding(0, 0, 0, 0);
        host.addView(homeSkeleton, host.indexOfChild(containerSearchDiscovery) + 1,
                new FrameLayout.LayoutParams(-1, -1));
        containerSearchDiscovery.setAlpha(0f);
        // Por si alguna sección no llega a resolver (sin red, sin permiso de música): nunca queda el esqueleto para siempre.
        host.postDelayed(this::hideHomeSkeleton, 12000);
    }

    private void markHomeReady(int section) {
        if (homeSkeleton == null) return;
        homeReadyMask |= section;
        if (homeReadyMask == HOME_ALL) hideHomeSkeleton();
    }

    /** El esqueleto se funde y el contenido real entra subiendo suavemente. */
    private void hideHomeSkeleton() {
        final cl.arichard.descargador.util.HomeSkeletonView skeleton = homeSkeleton;
        if (skeleton == null) return;
        homeSkeleton = null;
        if (uiState != null) uiState.homeSkeletonDone = true;
        if (containerSearchDiscovery != null) {
            containerSearchDiscovery.setTranslationY(dp(14));
            containerSearchDiscovery.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(380)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
        skeleton.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            if (skeleton.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) skeleton.getParent()).removeView(skeleton);
        }).start();
    }

    private void refreshDiscoveryContent() {
        populatePinnedPlaylists();
        if (!initialUiSettled) return;
        final int generation = ++discoveryGeneration;
        populateForYou();
        populateRecentQueries();
        // These sections are independent. Spreading their binding over frames keeps startup and tab swipes fluid.
        containerSearchDiscovery.post(() -> {
            if (generation == discoveryGeneration && !isFinishing() && !isDestroyed()) populateTopCollections();
        });
        containerSearchDiscovery.postDelayed(() -> {
            if (generation == discoveryGeneration && !isFinishing() && !isDestroyed()) populateRecentPlays();
        }, 20);
        containerSearchDiscovery.postDelayed(() -> {
            if (generation == discoveryGeneration && !isFinishing() && !isDestroyed()) populateTopLibrary();
        }, 40);
    }

    private void populatePinnedPlaylists() {
        View scroll = findViewById(R.id.pinnedPlaylistsScroll);
        LinearLayout row = findViewById(R.id.layoutPinnedPlaylists);
        if (scroll == null || row == null) return;
        PlaylistManager manager = PlaylistManager.getInstance(this);
        List<String> names = manager.getPinnedPlaylistNames(allSongs);
        StringBuilder signature = new StringBuilder();
        for (String name : names) {
            signature.append(name).append('\u0000');
            for (Song song : manager.resolveSongs(name, allSongs)) signature.append(song.getPath()).append('\u0001');
        }
        scroll.setVisibility(names.isEmpty() ? View.GONE : View.VISIBLE);
        if (signature.toString().equals(pinnedPlaylistsSignature)) return;
        pinnedPlaylistsSignature = signature.toString();
        row.removeAllViews();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPaddingRelative(dp(10), dp(8), dp(10), dp(8));
            card.setBackgroundResource(R.drawable.bg_chip_inactive);
            card.setTag(name);
            card.setContentDescription(cl.arichard.descargador.util.Tr.t("Abrir playlist") + " " + cl.arichard.descargador.util.Tr.name(name));
            cl.arichard.descargador.util.CoverCollageView cover = new cl.arichard.descargador.util.CoverCollageView(this, null);
            cover.setSongs(manager.resolveSongs(name, allSongs));
            card.addView(cover, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView title = new TextView(this);
            title.setText(cl.arichard.descargador.util.Tr.name(name));
            title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            title.setTextSize(13); title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setIncludeFontPadding(false);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
            titleParams.setMarginStart(dp(10));
            card.addView(title, titleParams);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(164), -1);
            if (index < names.size() - 1) cardParams.setMarginEnd(dp(8));
            row.addView(card, cardParams);
            card.setOnClickListener(view -> { switchTab(false); showPlaylistSongs(name); });
            card.setOnLongClickListener(view -> { showPlaylistSettingsMenu(name, view); return true; });
            cl.arichard.descargador.util.SafeTap.installRowTap(card);
        }
    }

    private void populateRecentQueries() {
        if (layoutRecentChips == null || sectionRecentQueries == null) return;
        sectionRecentQueries.setVisibility(View.GONE);
        List<String> queries = SearchHistoryManager.getInstance(this).getRecentQueries();
        String signature = queries.toString(); if (signature.equals(lastRecentQueries)) return; lastRecentQueries = signature;
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_dropdown_item_1line, queries) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                row.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_primary));
                row.setTextSize(15);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setMinHeight(dp(48));
                row.setPadding(dp(16), dp(10), dp(16), dp(10));
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
                row.setCompoundDrawablePadding(dp(12));
                row.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(MainActivity.this, R.color.text_secondary)));
                return row;
            }
        };
        inputSearch.setAdapter(adapter);
    }

    private void showRecentSearches() {
        if (inputSearch.hasFocus() && inputSearch.getAdapter() != null
                && inputSearch.getAdapter().getCount() > 0 && fitRecentSearches()) inputSearch.showDropDown();
    }

    private boolean fitRecentSearches() {
        View anchor = findViewById(R.id.searchField);
        android.graphics.Rect visibleFrame = new android.graphics.Rect();
        anchor.getWindowVisibleDisplayFrame(visibleFrame);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int available = visibleFrame.bottom - location[1] - anchor.getHeight() - dp(12);
        if (available < dp(48)) { inputSearch.dismissDropDown(); return false; }
        int height = Math.min(dp(216), available);
        if (inputSearch.getDropDownHeight() != height) inputSearch.setDropDownHeight(height);
        if (inputSearch.getDropDownWidth() != anchor.getWidth()) inputSearch.setDropDownWidth(anchor.getWidth());
        return anchor.getWidth() > 0;
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
        final int generation = ++topCollectionsGeneration;
        final List<Song> snapshot = new ArrayList<>(allSongs);
        LIBRARY_IO.execute(() -> {
            List<CollectionRank> ranked = new ArrayList<>();
            cl.arichard.descargador.data.ClickStats stats =
                    cl.arichard.descargador.data.ClickStats.getInstance(getApplicationContext());
            for (Map.Entry<String, List<Song>> entry : MusicScanner.groupByFolder(snapshot).entrySet()) {
                int plays = stats.count(entry.getValue());
                if (plays > 0) ranked.add(new CollectionRank(entry.getKey(), false, entry.getValue(), plays));
            }
            PlaylistManager playlists = PlaylistManager.getInstance(getApplicationContext());
            for (String name : playlists.getPlaylistNames()) {
                List<Song> songs = playlists.resolveSongs(name, snapshot);
                int plays = stats.count(songs);
                if (plays > 0) ranked.add(new CollectionRank(name, true, songs, plays));
            }
            ranked.sort((a, b) -> {
                int byPlays = Integer.compare(b.plays, a.plays);
                return byPlays != 0 ? byPlays : a.name.compareToIgnoreCase(b.name);
            });
            final List<CollectionRank> result = ranked.size() > 6
                    ? new ArrayList<>(ranked.subList(0, 6)) : ranked;
            runOnUiThread(() -> {
                if (generation == topCollectionsGeneration && !isFinishing() && !isDestroyed())
                    renderTopCollections(result);
            });
        });
    }

    private void renderTopCollections(List<CollectionRank> ranked) {
        markHomeReady(HOME_COLLECTIONS);
        final int generation = topCollectionsGeneration;
        StringBuilder signature = new StringBuilder();
        for (CollectionRank item : ranked) signature.append(item.playlist).append(item.name).append(item.plays);
        if (signature.toString().equals(lastTopCollections)) return;
        lastTopCollections = signature.toString();
        layoutTopCollections.removeAllViews();
        if (ranked.isEmpty()) { sectionTopCollections.setVisibility(View.GONE); return; }
        sectionTopCollections.setVisibility(View.VISIBLE);
        int width = Math.round(270 * getResources().getDisplayMetrics().density);
        int margin = Math.round(10 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < ranked.size(); i++) {
            CollectionRank item = ranked.get(i);
            layoutTopCollections.postDelayed(() -> {
            if (generation != topCollectionsGeneration || isFinishing() || isDestroyed()) return;
            View card = getLayoutInflater().inflate(R.layout.item_folder, layoutTopCollections, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(84));
            params.setMargins(0, 0, margin, 0); card.setLayoutParams(params);
            ((cl.arichard.descargador.util.CoverCollageView) card.findViewById(R.id.folderCollage)).setSongs(item.songs);
            ((TextView) card.findViewById(R.id.txtFolderName)).setText(item.name);
            ((TextView) card.findViewById(R.id.txtFolderName)).setSingleLine(true);
            ((TextView) card.findViewById(R.id.txtFolderName)).setEllipsize(android.text.TextUtils.TruncateAt.END);
            ((TextView) card.findViewById(R.id.txtFolderSongCount)).setSingleLine(true);
            ((TextView) card.findViewById(R.id.txtFolderSongCount)).setEllipsize(android.text.TextUtils.TruncateAt.END);
            ((TextView) card.findViewById(R.id.txtFolderSongCount)).setText(
                    (item.playlist ? cl.arichard.descargador.util.Tr.t("Playlist") : cl.arichard.descargador.util.Tr.t("Carpeta")) + " • " + item.plays
                            + (item.plays == 1 ? cl.arichard.descargador.util.Tr.t(" reproducción") : cl.arichard.descargador.util.Tr.t(" reproducciones")));
            card.setOnClickListener(v -> {
                switchTab(false);
                setLibraryMode(item.playlist ? 3 : 2);
                if (item.playlist) showPlaylistSongs(item.name); else showFolderSongs(item.name, item.songs);
            });
            cl.arichard.descargador.util.SafeTap.installRowTap(card);
            layoutTopCollections.addView(card);
            }, i * 18L);
        }
    }

    private void populateRecentPlays() {
        if (layoutRecentPlaysCards == null || sectionRecentPlays == null) return;
        final int generation = ++recentPlaysGeneration;
        LIBRARY_IO.execute(() -> {
            List<Song> recents = SearchHistoryManager.getInstance(getApplicationContext()).getRecentPlayedSongs();
            runOnUiThread(() -> {
                if (generation == recentPlaysGeneration && !isFinishing() && !isDestroyed())
                    renderRecentPlays(recents);
            });
        });
    }

    private void renderRecentPlays(List<Song> recents) {
        markHomeReady(HOME_RECENTS);
        final int generation = recentPlaysGeneration;
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

        for (int i = 0; i < recents.size(); i++) {
            Song s = recents.get(i);
            layoutRecentPlaysCards.postDelayed(() -> {
            if (generation != recentPlaysGeneration || isFinishing() || isDestroyed()) return;
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
            }, i * 18L);
        }
    }

    private void populateTopLibrary() {
        if (layoutTopLibraryList == null || sectionTopLibrary == null) return;
        final int generation = ++topLibraryGeneration;
        final List<Song> snapshot = new ArrayList<>(allSongs);
        LIBRARY_IO.execute(() -> {
            List<Song> topSongs = SearchHistoryManager.getInstance(getApplicationContext()).getTopPlayed(snapshot, 5);
            runOnUiThread(() -> {
                if (generation == topLibraryGeneration && !isFinishing() && !isDestroyed())
                    renderTopLibrary(topSongs);
            });
        });
    }

    private void renderTopLibrary(List<Song> topSongs) {
        markHomeReady(HOME_LIBRARY);
        final int generation = topLibraryGeneration;
        cl.arichard.descargador.data.ClickStats stats = cl.arichard.descargador.data.ClickStats.getInstance(this);
        StringBuilder signature = new StringBuilder();
        for (Song song : topSongs) signature.append(song.getPath()).append(song.getTitle()).append(':').append(stats.count(song));
        if (signature.toString().equals(lastTopLibrary)) return; lastTopLibrary = signature.toString();
        if (topSongs.isEmpty()) {
            sectionTopLibrary.setVisibility(View.GONE);
            return;
        }
        sectionTopLibrary.setVisibility(View.VISIBLE);
        layoutTopLibraryList.removeAllViews();

        for (int i = 0; i < topSongs.size(); i++) {
            Song s = topSongs.get(i);
            layoutTopLibraryList.postDelayed(() -> {
            if (generation != topLibraryGeneration || isFinishing() || isDestroyed()) return;
            View itemView = getLayoutInflater().inflate(R.layout.item_song, layoutTopLibraryList, false);
            TextView title = itemView.findViewById(R.id.txtSongTitle);
            TextView sub = itemView.findViewById(R.id.txtSongSubtitle);
            ImageView cover = itemView.findViewById(R.id.imgSongCover);
            View btnFav = itemView.findViewById(R.id.btnFavorite);
            View btnMenu = itemView.findViewById(R.id.btnSongMenu);

            title.setText(s.getTitle());
            sub.setText(s.getArtist() + " • " + s.getFormattedDuration());
            TextView clicks = itemView.findViewById(R.id.txtSongClicks);
            int count = stats.count(s);
            clicks.setText(String.valueOf(count));
            clicks.setVisibility(View.VISIBLE);
            CoverArtLoader.getInstance().loadSongCover(s.getCoverPath(), cover);

            if (btnFav != null) btnFav.setVisibility(View.GONE);
            if (btnMenu != null) btnMenu.setVisibility(View.GONE);
            itemView.findViewById(R.id.songActionZone).setVisibility(View.GONE);

            itemView.setOnClickListener(v -> {
                PlayerManager.getInstance().setPlaybackSource("songs", "");
                PlayerManager.getInstance().playSong(s, allSongs, false);
                startActivity(new Intent(MainActivity.this, NowPlayingActivity.class));
            });

            cl.arichard.descargador.util.SafeTap.installRowTap(itemView);
            layoutTopLibraryList.addView(itemView);
            }, i * 18L);
        }
    }

    private void triggerSearchOrDownload(String query) {
        if (query == null || query.trim().isEmpty()) return;
        query = query.trim();
        if (pendingLiveSearch != null) liveSearchHandler.removeCallbacks(pendingLiveSearch);
        pendingLiveSearch = null;
        inputSearch.dismissDropDown();
        inputSearch.clearFocus();
        uiState.searchQuery = query;
        // Un enlace de playlist de Spotify, YouTube, Deezer, Apple Music o SoundCloud se importa completo en vez de descargarse.
        cl.arichard.descargador.util.PlaylistImport.Link playlistLink = cl.arichard.descargador.util.PlaylistImport.find(query);
        if (playlistLink != null) {
            cl.arichard.descargador.util.PlaylistImportDialog.start(this, playlistLink);
            return;
        }
        SearchHistoryManager.getInstance(this).addSearchQuery(query);
        hideHomeSkeleton();
        if (containerSearchDiscovery != null) containerSearchDiscovery.setVisibility(View.GONE);

        Matcher matcher = YOUTUBE.matcher(query);
        if (matcher.find() || query.startsWith("http://") || query.startsWith("https://")) {
            // Direct URL download
            startDownload(query, null);
        } else {
            // Perform native YouTube search with thumbnail list
            performYoutubeSearch(query);
        }
    }

    /** Canciones, videos y artistas salen del catálogo de YouTube Music, como en la app de escritorio. */
    private void performYoutubeSearch(String query) {
        findViewById(R.id.searchFilterRow).setVisibility(View.VISIBLE);
        searchFiltered(query, true);
    }

    /** Respaldo: búsqueda normal de YouTube solo cuando YouTube Music no responde. */
    private void performPlainYoutubeSearch(String query) {
        final int generation = ++uiState.searchGeneration;
        uiState.loadingMore = false; loadingSearchPage = false; uiState.hasMoreResults = false;
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
                    state.hasMoreResults = false;
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
        return cl.arichard.descargador.util.YoutubeApi.search(query, 8);
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
        if (!inputSearch.getText().toString().equals(uiState.searchQuery)) {
            restoringSearchText = true;
            try { inputSearch.setText(uiState.searchQuery); }
            finally { restoringSearchText = false; }
        }
        boolean resultsChanged = renderedSearchResults != uiState.results;
        if (resultsChanged) { searchResultAdapter.setResults(uiState.results); renderedSearchResults = uiState.results; }
        // Spinner al final de la lista mientras llega la página siguiente de resultados.
        searchResultAdapter.setFilter(uiState.searchFilter);
        searchResultAdapter.setLoadingMore(uiState.loadingMore && !"artists".equals(uiState.searchFilter));
        renderSearchFilter();
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
    private void startDownload(String targetUrl, String defaultTitle) {
        startDownload(targetUrl, defaultTitle, null);
    }

    private void startDownload(String targetUrl, String defaultTitle, String playlist) {
        boolean audio = playlist == null ? isMp3 : !"MP4".equals(getSharedPreferences("musicbox_prefs", MODE_PRIVATE).getString("autoFormat", "MP3"));
        cl.arichard.descargador.download.DownloadManager manager = cl.arichard.descargador.download.DownloadManager.get(this);
        String quality = getSharedPreferences("musicbox_prefs", MODE_PRIVATE).getString(audio ? "quality_mp3" : "quality_mp4", audio ? "192 kbps" : "1080p");
        int added = manager.enqueue(Collections.singletonList(targetUrl),
                new cl.arichard.descargador.download.DownloadOptions(audio, quality,
                        false, "", cl.arichard.descargador.download.DownloadManager.cookiesPath(this),
                        StorageUtil.getDownloadDir(this).getAbsolutePath()), defaultTitle, playlist == null ? "manual" : "auto", playlist == null ? "" : playlist);
        if (added == 0) Toast.makeText(this, cl.arichard.descargador.util.Tr.t("Descarga en curso"), Toast.LENGTH_SHORT).show();
        renderManagedDownloads();
    }

    /** Cada pantalla observa la misma cola; ninguna tarea retiene una Activity cerrada. */
    private void renderManagedDownloads() {
        if (isDestroyed() || uiState == null || songAdapter == null || searchResultAdapter == null) return;
        cl.arichard.descargador.download.DownloadManager manager = cl.arichard.descargador.download.DownloadManager.get(this);
        activeDownloadingVideoIds.clear();
        boolean completed = false;
        for (cl.arichard.descargador.download.DownloadJob job : manager.jobs()) {
            String id = extractVideoId(job.url);
            if (id == null || id.isEmpty()) continue;
            if (!job.status.isFinished()) {
                activeDownloadingVideoIds.add(id);
                searchResultAdapter.addDownloadingId(id);
                songAdapter.setDownloadStarted(id);
                // En espera o preparándose todavía no hay porcentaje real: el anillo gira en vez de quedar quieto en 0 %.
                boolean noProgressYet = job.status == cl.arichard.descargador.download.DownloadJob.Status.WAITING
                        || job.status == cl.arichard.descargador.download.DownloadJob.Status.PREPARING;
                float ringProgress = noProgressYet ? -1f : job.progress;
                searchResultAdapter.setDownloadProgress(id, ringProgress);
                songAdapter.setDownloadProgress(id, ringProgress);
            } else if (finishedDownloadJobs.add(job.id)) {
                boolean success = job.status == cl.arichard.descargador.download.DownloadJob.Status.DONE;
                searchResultAdapter.removeDownloadingId(id);
                songAdapter.setDownloadFinished(id, success);
                if (success) {
                    searchResultAdapter.markDownloadCompleted(id);
                    uiState.completedDownloads++;
                    completed = true;
                } else if (!job.error.isEmpty()) {
                    cl.arichard.descargador.util.AppNotice.error(findViewById(R.id.bottomBarArea),
                            cl.arichard.descargador.download.ErrorReason.notice(job.error));
                }
            }
        }
        uiState.downloading = manager.isBusy();
        uiState.downloadVisible = false;
        cardProgress.setVisibility(View.GONE);
        if (completed) uiState.changed();
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
        cl.arichard.descargador.util.PlaylistPicker.show(this, cl.arichard.descargador.util.Tr.t("Agregar a playlist"), false, selected -> {
            PlaylistManager manager = PlaylistManager.getInstance(this);
            boolean added = manager.addOnlineTrack(selected, result);
            Toast.makeText(this, added ? cl.arichard.descargador.util.Tr.t("Agregada a ") + selected : cl.arichard.descargador.util.Tr.t("Ya estaba en ") + selected, Toast.LENGTH_SHORT).show();
            searchResultAdapter.notifyDataSetChanged();
        });
    }

    private void showPlaylistSettingsMenu(String name, View anchor) {
        PlaylistManager manager = PlaylistManager.getInstance(this);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(cl.arichard.descargador.util.Tr.t(manager.isPinned(name) ? "Quitar de Inicio" : "Fijar en Inicio"))
                .setOnMenuItemClickListener(item -> {
                    manager.setPinned(name, !manager.isPinned(name)); populatePinnedPlaylists(); return true;
                });
        menuAction(menu, cl.arichard.descargador.util.Tr.t("Editar nombre y descripción"), R.drawable.ic_edit, false).setOnMenuItemClickListener(item -> {
            showEditPlaylistDialog(name); return true;
        });
        boolean auto = manager.isAutoDownloadEnabled(name);
        menuAction(menu, auto ? cl.arichard.descargador.util.Tr.t("Desactivar descarga automática") : cl.arichard.descargador.util.Tr.t("Activar descarga automática"),
                auto ? R.drawable.ic_downloaded_badge : R.drawable.ic_download, false)
                .setOnMenuItemClickListener(item -> {
                    if (auto) { manager.setAutoDownloadEnabled(name, false); refreshPlaylistsView(); }
                    else enableAutoDownload(name, this::refreshPlaylistsView);
                    return true;
                });
        if (!"Favoritos".equals(name)) menuAction(menu, cl.arichard.descargador.util.Tr.t("Eliminar playlist"), R.drawable.ic_delete, true).setOnMenuItemClickListener(item -> {
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
        libraryContentSwipe.setListener(new cl.arichard.descargador.util.CollectionSwipeLayout.Listener() {
            @Override public boolean canSwipe() { return canSwipeCollections(); }
            @Override public boolean canNavigate(int direction) { return adjacentCollection(direction) != null; }
            @Override public void onDrag(float distance, boolean available) { previewCollectionSwipe(distance, available); }
            @Override public void onRelease(int direction, boolean committed, float distance, float velocity) {
                finishCollectionSwipe(direction, committed);
            }
        });

        songAdapter = new SongAdapter(this, allSongs, new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(Song song, List<Song> queue, int position) {
                saveLibraryNavigation();
                rememberPlaybackSource();
                List<Song> local = preferLocalCopies(queue);
                int index = queue.indexOf(song);
                PlayerManager.getInstance().playSong(index >= 0 ? local.get(index) : song, local, false);
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
        new androidx.recyclerview.widget.ItemTouchHelper(new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP | androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
            private boolean moved;
            @Override public int getMovementFlags(RecyclerView list, RecyclerView.ViewHolder holder) {
                return list.getAdapter() == playlistAdapter && !isViewingSubList
                        ? super.getMovementFlags(list, holder) : 0;
            }
            @Override public boolean onMove(RecyclerView list, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                boolean changed = playlistAdapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
                moved |= changed; return changed;
            }
            @Override public void onSwiped(RecyclerView.ViewHolder holder, int direction) {}
            @Override public void onSelectedChanged(RecyclerView.ViewHolder holder, int state) {
                playlistDragging = state == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG;
                super.onSelectedChanged(holder, state);
            }
            @Override public void clearView(RecyclerView list, RecyclerView.ViewHolder holder) {
                super.clearView(list, holder);
                playlistDragging = false;
                if (moved) {
                    moved = false;
                    PlaylistManager.getInstance(MainActivity.this).setPlaylistOrder(playlistAdapter.getPlaylistOrder());
                }
                populatePinnedPlaylists();
            }
        }).attachToRecyclerView(recyclerLibrary);
        songAdapter.setSort(savedSort, savedAscending);
        btnPlaylistArtist = findViewById(R.id.btnPlaylistArtist);
        btnPlaylistArtist.setOnClickListener(v -> showPlaylistArtistPicker());
        cl.arichard.descargador.util.SafeTap.install(btnPlaylistArtist);
        View clearArtist = findViewById(R.id.btnClearPlaylistArtist);
        clearArtist.setContentDescription(cl.arichard.descargador.util.Tr.t("Quitar filtro de artista"));
        clearArtist.setOnClickListener(v -> applyPlaylistArtistFilter(""));
        cl.arichard.descargador.util.SafeTap.install(clearArtist);

        chipSongs.setOnClickListener(v -> setLibraryMode(0));
        chipFolders.setOnClickListener(v -> setLibraryMode(2));
        chipPlaylists.setOnClickListener(v -> setLibraryMode(3));

        View btnLibraryMore = findViewById(R.id.btnLibraryMore);
        btnLibraryMore.setOnClickListener(v -> showLibraryMoreMenu(btnLibraryMore));
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
                List<Song> local = preferLocalCopies(shuffled);
                PlayerManager.getInstance().playSong(local.get(0), local, false);
            } else {
                Toast.makeText(this, cl.arichard.descargador.util.Tr.t("No hay canciones para reproducir"), Toast.LENGTH_SHORT).show();
            }
        });

        inputSearchLibrary.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { saveLibraryPosition(); }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyLibraryFilter(s != null ? s.toString() : "");
                restoreLibraryPosition();
                updatePlaylistSuggestionsVisibility();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnNewPlaylist.setOnClickListener(v -> showCreatePlaylistDialog());
    }

    private void showLibraryMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        final int locate = 1;
        final int importPlaylists = 2;
        menuAction(menu, locate, 0, cl.arichard.descargador.util.Tr.t("Ir a la canción actual"), R.drawable.ic_locate_playing, false);
        menuAction(menu, importPlaylists, 1, cl.arichard.descargador.util.Tr.t("Importar playlists"), R.drawable.ic_playlist, false);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == locate) {
                locatePlayingSong();
                return true;
            }
            if (item.getItemId() == importPlaylists) {
                cl.arichard.descargador.util.PlaylistImportDialog.showEntry(this);
                return true;
            }
            return false;
        });
        menu.show();
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

    private void showPlaylistArtistPicker() {
        List<String> artists = songAdapter.getArtists();
        View content = getLayoutInflater().inflate(R.layout.dialog_artist_filter, null);
        RecyclerView choices = content.findViewById(R.id.recyclerArtistChoices);
        choices.setLayoutManager(new LinearLayoutManager(this));
        final AlertDialog[] opened = new AlertDialog[1];
        ArtistFilterAdapter adapter = new ArtistFilterAdapter(this, artists, songAdapter.getArtistFilter(), artist -> {
            applyPlaylistArtistFilter(artist);
            if (opened[0] != null) opened[0].dismiss();
        });
        choices.setAdapter(adapter);
        EditText search = content.findViewById(R.id.inputArtistSearch);
        search.setHint(cl.arichard.descargador.util.Tr.t("Buscar artistas"));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        opened[0] = new MaterialAlertDialogBuilder(this)
                .setTitle(cl.arichard.descargador.util.Tr.t("Filtrar por artista"))
                .setView(content)
                .setNegativeButton(cl.arichard.descargador.util.Tr.t("Cancelar"), null).create();
        opened[0].show();
    }

    private void applyPlaylistArtistFilter(String artist) {
        saveLibraryPosition();
        songAdapter.setArtistFilter(artist);
        updatePlaylistArtistFilter(); updateLibraryCounter(); restoreLibraryPosition();
        saveLibraryNavigation();
    }

    private void updatePlaylistArtistFilter() {
        if (btnPlaylistArtist == null) return;
        boolean visible = isViewingSubList && currentLibraryMode == 3;
        btnPlaylistArtist.setVisibility(visible ? View.VISIBLE : View.GONE);
        String artist = songAdapter.getArtistFilter();
        boolean selected = !artist.isEmpty();
        btnPlaylistArtist.setSelected(selected);
        btnPlaylistArtist.setText(selected ? artist : cl.arichard.descargador.util.Tr.t("Artistas"));
        btnPlaylistArtist.setTextColor(ContextCompat.getColor(this, selected ? R.color.accent : R.color.text_primary));
        cl.arichard.descargador.util.ArtistAvatarLoader.getInstance(this)
                .load(visible && selected ? artist : "", btnPlaylistArtist, dp(20));
        btnPlaylistArtist.setContentDescription(cl.arichard.descargador.util.Tr.t("Filtrar por artista")
                + ": " + (selected ? artist : cl.arichard.descargador.util.Tr.t("Todos los artistas")));
        androidx.core.view.ViewCompat.setTooltipText(btnPlaylistArtist, btnPlaylistArtist.getContentDescription());
        findViewById(R.id.btnClearPlaylistArtist).setVisibility(visible && selected ? View.VISIBLE : View.GONE);
        findViewById(R.id.libraryToolsSpacer).setVisibility(visible ? View.GONE : View.VISIBLE);
        // Ambos controles comparten el espacio disponible; los nombres largos se recortan.
        View sort = findViewById(R.id.btnLibrarySort);
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) sort.getLayoutParams();
        size.width = visible ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT;
        size.weight = visible ? 1 : 0;
        sort.setLayoutParams(size);
        updatePlaylistSuggestionsVisibility();
    }

    private void setLibraryMode(int mode) {
        saveLibraryPosition(); changingLibrary = true;
        currentLibraryMode = mode == 1 ? 3 : Math.max(0, Math.min(3, mode));
        currentSubListName = ""; isViewingSubList = false; heldFavorites.clear();
        songAdapter.setArtistFilter("");
        inputSearchLibrary.setText("");
        updateLibraryHeader(); rebuildLibraryContent(); changingLibrary = false; restoreLibraryPosition();
    }

    private void updateLibraryHeader() {
        updatePlaylistArtistFilter();
        btnBackLibrary.setVisibility(isViewingSubList ? View.VISIBLE : View.GONE);
        String headerTitle = cl.arichard.descargador.util.Tr.t("Mi Música");
        if (isViewingSubList) {
            headerTitle = cl.arichard.descargador.util.Tr.name(currentSubListName);
        }
        txtLibraryTitle.setText(headerTitle);
        if (txtLibraryContext != null) {
            txtLibraryContext.setVisibility(isViewingSubList ? View.VISIBLE : View.GONE);
            if (isViewingSubList) {
                String type = currentLibraryMode == 3 ? "Playlist" : "Carpeta";
                txtLibraryContext.setText(cl.arichard.descargador.util.Tr.t(type).toUpperCase(java.util.Locale.getDefault()));
            }
        }
        btnNewPlaylist.setVisibility(currentLibraryMode == 3 && !isViewingSubList ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutLibraryModeChips).setVisibility(isViewingSubList ? View.GONE : View.VISIBLE);
        if (layoutLibraryTools != null) layoutLibraryTools.setVisibility(currentLibraryMode == 0 || isViewingSubList ? View.VISIBLE : View.GONE);
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
        if (currentTabIsSearch && !isViewingSubList) {
            libraryContentDirty = true;
            return;
        }
        libraryContentDirty = false;
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
            songAdapter.updateSongs(songs, isViewingSubList && currentLibraryMode == 3);
            if (recyclerLibrary.getAdapter() != songAdapter) recyclerLibrary.setAdapter(songAdapter);
        } else if (currentLibraryMode == 2) {
            folderAdapter.updateFolders(MusicScanner.groupByFolder(allSongs));
            if (recyclerLibrary.getAdapter() != folderAdapter) recyclerLibrary.setAdapter(folderAdapter);
        } else {
            playlistAdapter.updatePlaylists(PlaylistManager.getInstance(this).getVisiblePlaylistNames(allSongs));
            if (recyclerLibrary.getAdapter() != playlistAdapter) recyclerLibrary.setAdapter(playlistAdapter);
        }
        applyLibraryFilter(inputSearchLibrary.getText().toString());
        updatePlaylistArtistFilter();
    }

    private void refreshPlaylistsView() {
        saveLibraryPosition(); rebuildLibraryContent(); restoreLibraryPosition();
    }

    /** Playlist recién creada por una importación que debe aparecer (con rebote) en la biblioteca. */
    private String pendingPlaylistArrival = "";

    /**
     * Una importación terminó de crear [name]. Si la biblioteca está a la vista, la lista se actualiza al instante y la
     * nueva entra con rebote; en la pestaña de inicio o dentro de una lista, se muestra al volver a la lista de playlists.
     */
    public void onPlaylistImported(String name) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || name == null || name.isEmpty()) return;
            cl.arichard.descargador.util.PlaylistImportReviewSheet.pendingReveal = "";
            pendingPlaylistArrival = name;
            libraryContentDirty = true;
            if (!currentTabIsSearch && !isViewingSubList) revealImportedPlaylist();
        });
    }

    private void revealImportedPlaylist() {
        final String name = pendingPlaylistArrival;
        pendingPlaylistArrival = "";
        if (name.isEmpty() || songAdapter == null || playlistAdapter == null) return;
        if (currentLibraryMode != 3) setLibraryMode(3); else refreshPlaylistsView();
        libraryContentDirty = false;
        final int index = playlistAdapter.highlightArrival(name);
        if (index < 0) return;
        recyclerLibrary.post(() -> {
            LinearLayoutManager layout = (LinearLayoutManager) recyclerLibrary.getLayoutManager();
            if (layout != null && (index < layout.findFirstCompletelyVisibleItemPosition()
                    || index > layout.findLastCompletelyVisibleItemPosition()))
                recyclerLibrary.smoothScrollToPosition(index);
        });
    }

    private void showPlaylistSongs(String playlistName) {
        showCollection(3, playlistName);
    }

    private void showFolderSongs(String name, List<Song> songs) {
        showCollection(2, name);
    }

    private void showCollection(int mode, String name) {
        saveLibraryPosition(); changingLibrary = true;
        currentLibraryMode = mode;
        currentSubListName = name; isViewingSubList = true; heldFavorites.clear();
        songAdapter.setArtistFilter("");
        inputSearchLibrary.setText("");
        updateLibraryHeader(); rebuildLibraryContent(); changingLibrary = false; restoreLibraryPosition();
        if (mode == 3) showArrivals(name);
        refreshPlaylistSuggestions(false);
    }

    /** Al abrir una playlist, lleva la vista a las canciones recién agregadas y las anima al entrar. */
    private void showArrivals(String playlist) {
        PlaylistManager manager = PlaylistManager.getInstance(this);
        Set<String> fresh = manager.takeFreshlyAdded(playlist);
        if (fresh.isEmpty()) return;
        List<String> paths = new ArrayList<>();
        Set<String> pending = new java.util.LinkedHashSet<>(fresh);
        int first = -1;
        List<Song> shown = songAdapter.getDisplayedList();
        for (int i = 0; i < shown.size(); i++) {
            Song song = shown.get(i);
            String key = manager.canonicalKey(song);
            if (fresh.contains(song.getPath()) || fresh.contains(key)) {
                paths.add(song.getPath());
                pending.remove(song.getPath()); pending.remove(key);
                if (first < 0) first = i;
            }
        }
        // Las que aún no aparecen en la lista (por ejemplo, mientras se actualiza) se animan la próxima vez.
        manager.keepFreshlyAdded(playlist, pending);
        if (paths.isEmpty()) return;
        final int target = first;
        // Después de restaurar la posición anterior: la primera canción nueva queda a la vista.
        recyclerLibrary.post(() -> {
            LinearLayoutManager layout = (LinearLayoutManager) recyclerLibrary.getLayoutManager();
            if (layout != null && (target < layout.findFirstCompletelyVisibleItemPosition()
                    || target > layout.findLastCompletelyVisibleItemPosition()))
                layout.scrollToPositionWithOffset(target, Math.round(recyclerLibrary.getHeight() * .3f));
            songAdapter.highlightArrivals(paths);
        });
    }

    /** Una animación en curso no bloquea el siguiente deslizamiento: se interrumpe (ver interruptCollectionSwipe). */
    private boolean canSwipeCollections() {
        return isViewingSubList && (currentLibraryMode == 2 || currentLibraryMode == 3)
                && collectionNames().size() > 1 && (songAdapter == null || !songAdapter.isSelectionMode());
    }

    /** ¿Hay una playlist/carpeta anterior a la que ir deslizando a la derecha? Lo usa el gesto de volver para cederle el paso. */
    public boolean hasPreviousCollection() {
        return canSwipeCollections() && adjacentCollection(-1) != null;
    }

    private List<String> collectionNames() {
        if (currentLibraryMode == 3) return PlaylistManager.getInstance(this).getVisiblePlaylistNames(allSongs);
        if (currentLibraryMode == 2) return new ArrayList<>(MusicScanner.groupByFolder(allSongs).keySet());
        return Collections.emptyList();
    }

    /** direction: -1 previous, +1 next. No wrapping keeps the first and last collection predictable. */
    private String adjacentCollection(int direction) {
        if (!isViewingSubList || direction == 0) return null;
        List<String> names = collectionNames();
        int index = names.indexOf(currentSubListName), target = index + direction;
        return index < 0 || target < 0 || target >= names.size() ? null : names.get(target);
    }

    /** Destino de un cambio que ya salió de pantalla pero todavía no se mostró; se muestra si algo lo interrumpe. */
    private String pendingCollectionTarget;

    /** Un deslizamiento nuevo corta la animación anterior: completa el cambio pendiente y deja las vistas en su sitio. */
    private void interruptCollectionSwipe() {
        libraryHeader.animate().cancel(); libraryContentSwipe.animate().cancel();
        String target = pendingCollectionTarget;
        pendingCollectionTarget = null;
        collectionSwipeAnimating = false;
        if (target != null) showCollection(currentLibraryMode, target);
        libraryHeader.setTranslationX(0); libraryContentSwipe.setTranslationX(0);
        libraryHeader.setAlpha(1f); libraryContentSwipe.setAlpha(1f);
    }

    private void previewCollectionSwipe(float distance, boolean destinationAvailable) {
        if (collectionSwipeAnimating) interruptCollectionSwipe();
        float width = Math.max(1f, libraryContentSwipe.getWidth());
        float offset = Math.max(-width * .72f, Math.min(width * .72f,
                distance * (destinationAvailable ? 1f : .18f)));
        float alpha = 1f - Math.min(.32f, Math.abs(offset) / width * .45f);
        libraryHeader.setTranslationX(offset * .32f);
        libraryContentSwipe.setTranslationX(offset);
        libraryHeader.setAlpha(alpha); libraryContentSwipe.setAlpha(alpha);
    }

    private void finishCollectionSwipe(int direction, boolean committed) {
        libraryHeader.animate().cancel(); libraryContentSwipe.animate().cancel();
        String target = committed ? adjacentCollection(direction) : null;
        if (target == null) { settleCollectionViews(); return; }
        collectionSwipeAnimating = true;
        pendingCollectionTarget = target;
        float width = Math.max(1f, libraryContentSwipe.getWidth());
        float exit = direction > 0 ? -width : width;
        // Más corto que antes (110 + 170 ms): el cambio se siente inmediato y se puede encadenar otro deslizamiento.
        libraryHeader.animate().translationX(exit * .32f).alpha(0f).setDuration(110)
                .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
        libraryContentSwipe.animate().translationX(exit).alpha(0f).setDuration(110)
                .setInterpolator(new android.view.animation.AccelerateInterpolator()).withEndAction(() -> {
                    pendingCollectionTarget = null;
                    showCollection(currentLibraryMode, target);
                    float entrance = direction > 0 ? width * .28f : -width * .28f;
                    libraryHeader.setTranslationX(entrance * .32f); libraryContentSwipe.setTranslationX(entrance);
                    libraryHeader.setAlpha(.25f); libraryContentSwipe.setAlpha(.25f);
                    libraryHeader.animate().translationX(0).alpha(1f).setDuration(170)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    libraryContentSwipe.animate().translationX(0).alpha(1f).setDuration(170)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(() -> collectionSwipeAnimating = false).start();
                }).start();
    }

    private void settleCollectionViews() {
        collectionSwipeAnimating = false;
        libraryHeader.animate().translationX(0).alpha(1f).setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        libraryContentSwipe.animate().translationX(0).alpha(1f).setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
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
        menuAction(popup, cl.arichard.descargador.util.Tr.t("Reproducir a continuación"), R.drawable.ic_skip_next, false).setOnMenuItemClickListener(item -> {
            PlayerManager.getInstance().enqueue(song, true); return true;
        });
        menuAction(popup, cl.arichard.descargador.util.Tr.t("Agregar a la cola"), R.drawable.ic_queue, false).setOnMenuItemClickListener(item -> {
            PlayerManager.getInstance().enqueue(song, false); return true;
        });
        menuAction(popup, cl.arichard.descargador.util.Tr.t("Agregar a playlist"), R.drawable.ic_playlist, false).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.addToPlaylist(this, song, this::refreshPlaylistsView); return true;
        });
        if (song.isOnline()) menuAction(popup, cl.arichard.descargador.util.Tr.t("Descargar"), R.drawable.ic_download, false).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.download(this, song, this::refreshMusicLibrary); return true;
        });
        if (currentLibraryMode == 3 && isViewingSubList) menuAction(popup, cl.arichard.descargador.util.Tr.t("Mover a otra playlist"), R.drawable.ic_move, false).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongActions.moveToPlaylist(this, currentSubListName, song, this::refreshPlaylistsView);
            return true;
        });
        if (!song.isOnline()) menuAction(popup, cl.arichard.descargador.util.Tr.t("Compartir archivo"), R.drawable.ic_share, false).setOnMenuItemClickListener(item -> {
            shareSong(song); return true;
        });
        menuAction(popup, cl.arichard.descargador.util.Tr.t("Ver alternativas"), R.drawable.ic_sparkle, false).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongAlternativesSheet.show(this, song, this::refreshPlaylistsView); return true;
        });
        menuAction(popup, cl.arichard.descargador.util.Tr.t("Información de la canción"), R.drawable.ic_info, false).setOnMenuItemClickListener(item -> {
            cl.arichard.descargador.util.SongInfo.show(this, song); return true;
        });
        menuAction(popup, cl.arichard.descargador.util.Tr.t("Seleccionar canciones"), R.drawable.ic_check, false).setOnMenuItemClickListener(item -> {
            if (songAdapter != null) songAdapter.activateSelectionMode(song);
            return true;
        });
        if (currentLibraryMode == 3 && isViewingSubList) menuAction(popup, cl.arichard.descargador.util.Tr.t("Quitar de esta playlist"), R.drawable.ic_close, true).setOnMenuItemClickListener(item -> {
            PlaylistManager.getInstance(this).removeSongFromPlaylist(currentSubListName, song.getPath());
            refreshPlaylistsView(); return true;
        });
        if (!song.isOnline()) menuAction(popup, cl.arichard.descargador.util.Tr.t("Eliminar del dispositivo"), R.drawable.ic_delete, true).setOnMenuItemClickListener(item -> {
            deleteSongs(java.util.Collections.singletonList(song), this::refreshMusicLibrary); return true;
        });
        popup.show();
    }

    private MenuItem menuAction(PopupMenu popup, String title, int icon, boolean critical) {
        return menuAction(popup, 0, popup.getMenu().size(), title, icon, critical);
    }

    private MenuItem menuAction(PopupMenu popup, int id, int order, String title, int icon, boolean critical) {
        popup.setForceShowIcon(true);
        CharSequence label = title;
        if (critical) {
            SpannableString red = new SpannableString(title);
            red.setSpan(new ForegroundColorSpan(android.graphics.Color.rgb(255, 69, 58)), 0, red.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label = red;
        }
        MenuItem item = popup.getMenu().add(0, id, order, label);
        android.graphics.drawable.Drawable drawable = ContextCompat.getDrawable(this, icon);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(critical ? android.graphics.Color.rgb(255, 69, 58)
                    : ContextCompat.getColor(this, R.color.text_secondary));
            item.setIcon(drawable);
        }
        return item;
    }

    // --- Selección múltiple ---

    private void setupSelectionBar() {
        if (selectionBar == null) return;
        txtSelectAllLabel.setText(cl.arichard.descargador.util.Tr.t("Todas"));
        bulkLabel(R.id.labelBulkPlay, "Reproducir");
        bulkLabel(R.id.labelBulkNext, "Siguiente");
        bulkLabel(R.id.labelBulkQueue, "Cola");
        bulkLabel(R.id.labelBulkPlaylist, "Playlist");
        bulkLabel(R.id.labelBulkDelete, "Eliminar");
        int primary = ContextCompat.getColor(this, R.color.text_primary), red = ContextCompat.getColor(this, R.color.heart_red);
        for (int id : new int[]{R.id.iconBulkPlay, R.id.iconBulkNext, R.id.iconBulkQueue, R.id.iconBulkPlaylist})
            ((ImageView) findViewById(id)).setColorFilter(primary);
        ((ImageView) findViewById(R.id.iconBulkDelete)).setColorFilter(red);
        ((TextView) findViewById(R.id.labelBulkDelete)).setTextColor(red);
        btnSelectAll.setOnClickListener(v -> { if (songAdapter != null) songAdapter.toggleSelectAll(); });
        btnCloseSelection.setOnClickListener(v -> { if (songAdapter != null) songAdapter.clearSelection(); });
        btnBulkPlay.setOnClickListener(v -> {
            List<Song> picked = selectedSongs();
            if (picked.isEmpty()) return;
            songAdapter.clearSelection();
            PlayerManager.getInstance().setPlaybackSource("songs", "");
            PlayerManager.getInstance().playSong(picked.get(0), picked, false);
            startActivity(new Intent(this, NowPlayingActivity.class));
        });
        btnBulkNext.setOnClickListener(v -> {
            List<Song> picked = selectedSongs();
            if (picked.isEmpty()) return;
            // Cada una se inserta justo después de la actual; al recorrerlas al revés quedan en su orden.
            for (int i = picked.size() - 1; i >= 0; i--) PlayerManager.getInstance().enqueue(picked.get(i), true);
            songAdapter.clearSelection();
            Toast.makeText(this, picked.size() == 1 ? cl.arichard.descargador.util.Tr.t("Agregada a continuación")
                    : cl.arichard.descargador.util.Tr.f("%d canciones a continuación", picked.size()), Toast.LENGTH_SHORT).show();
        });
        btnBulkQueue.setOnClickListener(v -> {
            List<Song> picked = selectedSongs();
            if (picked.isEmpty()) return;
            for (Song song : picked) PlayerManager.getInstance().enqueue(song, false);
            songAdapter.clearSelection();
            Toast.makeText(this, picked.size() == 1 ? cl.arichard.descargador.util.Tr.t("Agregada a la cola")
                    : cl.arichard.descargador.util.Tr.f("%d canciones en la cola", picked.size()), Toast.LENGTH_SHORT).show();
        });
        btnAddToPlaylistBulk.setOnClickListener(v -> {
            List<Song> picked = selectedSongs();
            if (!picked.isEmpty()) cl.arichard.descargador.util.SongActions.addMultipleToPlaylist(this, picked, () -> {
                songAdapter.clearSelection();
                refreshPlaylistsView();
            });
        });
        btnBulkDelete.setOnClickListener(v -> {
            List<Song> picked = selectedSongs();
            if (!picked.isEmpty()) deleteSongs(picked, () -> { if (songAdapter != null) songAdapter.clearSelection(); refreshMusicLibrary(); });
        });
        for (View v : new View[]{btnSelectAll, btnCloseSelection, btnBulkPlay, btnBulkNext, btnBulkQueue, btnAddToPlaylistBulk, btnBulkDelete})
            cl.arichard.descargador.util.SafeTap.install(v);
    }

    private void bulkLabel(int id, String text) { ((TextView) findViewById(id)).setText(cl.arichard.descargador.util.Tr.t(text)); }

    private List<Song> selectedSongs() { return songAdapter == null ? new ArrayList<>() : songAdapter.getSelectedSongs(); }

    /** Elimina canciones del dispositivo; Android pide su propia confirmación para los archivos que no son de la app. */
    private void deleteSongs(List<Song> songs, Runnable done) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 41);
            return;
        }
        cl.arichard.descargador.util.SongDeleter.delete(this, songs, deletePrompt, done);
    }

    private void refreshSelectAll(int count) {
        boolean all = songAdapter != null && songAdapter.areAllSelected();
        android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
        disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (all) disc.setColor(ContextCompat.getColor(this, R.color.accent));
        else disc.setStroke(dp(2), ContextCompat.getColor(this, R.color.border));
        checkSelectAll.setBackground(disc);
        checkSelectAll.setText(all ? "✓" : "");
    }

    /**
     * La barra de selección sube mientras el reproductor mini y la barra inferior bajan y se ocultan, para que nunca
     * se solapen con los controles; al terminar la selección vuelven en sentido contrario.
     */
    private void updateSelectionBar(int count) {
        if (selectionBar == null) return;
        boolean show = count > 0;
        if (show) {
            txtSelectionCount.setText(cl.arichard.descargador.util.Tr.f(count > 1 ? "%d seleccionadas" : "%d seleccionada", count));
            refreshSelectAll(count);
        }
        if (show == selectionShown) return;
        selectionShown = show;
        View bottom = findViewById(R.id.bottomBarArea);
        selectionBar.animate().cancel();
        bottom.animate().cancel();
        android.view.animation.Interpolator smooth = new android.view.animation.DecelerateInterpolator(1.6f);
        if (show) {
            selectionBar.setVisibility(View.VISIBLE);
            selectionBar.setAlpha(0f);
            selectionBar.setTranslationY(dp(96));
            selectionBar.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(smooth).start();
            bottom.animate().alpha(0f).translationY(dp(120)).setDuration(220).setInterpolator(smooth)
                    .withEndAction(() -> { if (selectionShown) bottom.setVisibility(View.INVISIBLE); }).start();
        } else {
            bottom.setVisibility(View.VISIBLE);
            bottom.animate().alpha(1f).translationY(0f).setDuration(280).setStartDelay(60).setInterpolator(smooth).start();
            selectionBar.animate().alpha(0f).translationY(dp(96)).setDuration(200).setInterpolator(smooth)
                    .withEndAction(() -> { if (!selectionShown) selectionBar.setVisibility(View.GONE); }).start();
        }
    }

    // --- Ordenar la biblioteca ---

    private static String sortLabel(SongAdapter.Sort sort, boolean ascending) {
        switch (sort) {
            case TITLE: return cl.arichard.descargador.util.Tr.t(ascending ? "Título A–Z" : "Título Z–A");
            case ARTIST: return cl.arichard.descargador.util.Tr.t(ascending ? "Artista A–Z" : "Artista Z–A");
            case RECENT: return cl.arichard.descargador.util.Tr.t(ascending ? "Más recientes" : "Más antiguas");
            case PLAYS: return cl.arichard.descargador.util.Tr.t(ascending ? "Más escuchadas" : "Menos escuchadas");
            case DURATION: return cl.arichard.descargador.util.Tr.t(ascending ? "Más cortas" : "Más largas");
            default: return cl.arichard.descargador.util.Tr.t(ascending ? "Orden original" : "Orden inverso");
        }
    }

    private void setupLibrarySort() {
        layoutLibraryTools = findViewById(R.id.layoutLibraryTools);
        btnLibrarySort = findViewById(R.id.btnLibrarySort);
        txtLibrarySort = findViewById(R.id.txtLibrarySort);
        if (btnLibrarySort == null) return;
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("library_sort", "DEFAULT:1");
        try {
            String[] parts = saved.split(":");
            savedSort = SongAdapter.Sort.valueOf(parts[0]);
            savedAscending = parts.length < 2 || !"0".equals(parts[1]);
        } catch (Exception e) { savedSort = SongAdapter.Sort.DEFAULT; savedAscending = true; }
        txtLibrarySort.setText(sortLabel(savedSort, savedAscending));
        btnLibrarySort.setOnClickListener(v -> showSortMenu());
        cl.arichard.descargador.util.SafeTap.install(btnLibrarySort);
    }

    private void showSortMenu() {
        PopupMenu menu = new PopupMenu(this, btnLibrarySort);
        for (SongAdapter.Sort mode : SongAdapter.Sort.values()) {
            boolean current = mode == savedSort;
            String label = (current ? "✓  " : "      ") + sortLabel(mode, current ? savedAscending : true);
            menu.getMenu().add(label).setOnMenuItemClickListener(item -> {
                // Tocar el orden actual invierte su sentido.
                applySort(mode, current ? !savedAscending : true);
                return true;
            });
        }
        menu.show();
    }

    private void applySort(SongAdapter.Sort mode, boolean ascending) {
        savedSort = mode; savedAscending = ascending;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("library_sort", mode.name() + ":" + (ascending ? "1" : "0")).apply();
        String label = sortLabel(mode, ascending);
        txtLibrarySort.animate().cancel();
        txtLibrarySort.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            txtLibrarySort.setText(label);
            txtLibrarySort.animate().alpha(1f).setDuration(140).start();
        }).start();
        if (songAdapter != null) songAdapter.setSort(mode, ascending);
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
        // Este botón es una navegación global: siempre lleva primero a Biblioteca y elimina
        // cualquier filtro que pueda ocultar la pista que está sonando.
        switchTab(false);
        inputSearchLibrary.clearFocus();
        inputSearchLibrary.setText("");
        if (songAdapter != null) songAdapter.setArtistFilter("");
        updatePlaylistArtistFilter();
        int index = -1;
        if (currentLibraryMode == 0 || isViewingSubList) {
            List<Song> visible = songAdapter.getDisplayedList();
            for (int i = 0; i < visible.size(); i++) if (current.getPath().equals(visible.get(i).getPath())) { index = i; break; }
        }
        if (index < 0) {
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
        // Cloud/download callbacks can arrive together: never queue redundant full scans.
        if (libraryScanRunning) { libraryScanPending = true; return; }
        libraryScanRunning = true;
        final int request = ++scanGeneration;
        if (allSongs.isEmpty() && libraryLoading != null) {
            libraryLoading.setVisibility(View.VISIBLE);
            recyclerLibrary.setVisibility(View.GONE);
            txtSongCount.setText(cl.arichard.descargador.util.Tr.t("Cargando…"));
        }
        LIBRARY_IO.execute(() -> {
            List<Song> songs = MusicScanner.scanAllMusic(this);
            runOnUiThread(() -> {
                libraryScanRunning = false;
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
                reconcileAutoDownloads();
                List<Song> migrationSnapshot = new ArrayList<>(allSongs);
                LIBRARY_IO.execute(() -> {
                    PlaylistManager.getInstance(getApplicationContext()).linkLocalSongs(migrationSnapshot);
                    cl.arichard.descargador.data.ClickStats.getInstance(getApplicationContext())
                            .migrateLegacy(SearchHistoryManager.getInstance(getApplicationContext()), migrationSnapshot);
                });
                if (libraryScanPending) {
                    libraryScanPending = false;
                    refreshMusicLibrary();
                }
            });
        });
    }

    /** Cloud metadata changed, but the files on disk did not: update only visible models. */
    private void refreshCloudLibraryState() {
        if (isFinishing() || isDestroyed() || !libraryReady) return;
        saveLibraryPosition();
        rebuildLibraryContent();
        restoreLibraryPosition();
        refreshDiscoveryContent();
        reconcileAutoDownloads();
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
                .replaceAll("\\p{M}", "").replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
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
            startDownload(track.getUrl(), track.getTitle(), PlaylistManager.getInstance(this).autoPlaylistFor(track.getId()));
            queued++;
        }
    }

    /** Turns auto-download on and offers to fetch what the playlist already holds. */
    private void enableAutoDownload(String playlist, Runnable done) {
        PlaylistManager manager = PlaylistManager.getInstance(this);
        List<SearchResult> tracks = new ArrayList<>();
        for (String entry : manager.getSongPaths(playlist)) {
            SearchResult track = manager.getOnlineTrack(entry);
            if (track != null) tracks.add(track);
        }
        List<SearchResult> pending = missingDownloads(tracks);
        if (pending.isEmpty()) { manager.setAutoDownloadEnabled(playlist, true); if (done != null) done.run(); return; }
        new MaterialAlertDialogBuilder(this)
                .setTitle(cl.arichard.descargador.util.Tr.t("Descargar las canciones pendientes"))
                .setMessage(cl.arichard.descargador.util.Tr.f("Esta playlist tiene %d canciones sin descargar. ¿Descargarlas ahora?", pending.size()))
                .setPositiveButton(cl.arichard.descargador.util.Tr.t("Descargar"), (dialog, which) -> {
                    manager.setAutoDownloadEnabled(playlist, true);
                        for (SearchResult track : pending) {
                        autoAttempted.add(track.getId());
                        startDownload(track.getUrl(), track.getTitle(), playlist);
                    }
                })
                .setNegativeButton(cl.arichard.descargador.util.Tr.t("Solo las nuevas"), (dialog, which) -> {
                    SharedPreferences prefs = getSharedPreferences("auto_download", MODE_PRIVATE);
                    Set<String> skipped = new HashSet<>(prefs.getStringSet("skipped", java.util.Collections.emptySet()));
                    for (SearchResult track : pending) skipped.add(track.getId());
                    prefs.edit().putStringSet("skipped", skipped).apply();
                    manager.setAutoDownloadEnabled(playlist, true);
                })
                .setOnDismissListener(dialog -> { if (done != null) done.run(); })
                .show();
    }

    public boolean canSwipeBack() {
        return !currentTabIsSearch && (isViewingSubList || (songAdapter != null && songAdapter.isSelectionMode()));
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        if (intent.getBooleanExtra(cl.arichard.descargador.util.SharedSongSearch.EXTRA_SEARCH, false)) {
            switchTab(true);
            inputSearch.clearFocus();
            String query = intent.getStringExtra(Intent.EXTRA_TEXT);
            inputSearch.setText(query == null ? "" : query);
            if (pendingLiveSearch != null) liveSearchHandler.removeCallbacks(pendingLiveSearch);
            pendingLiveSearch = null;
            uiState.searchFilter = "songs";
            renderSearchFilter();
            if (query != null && !query.isEmpty()) performYoutubeSearch(query);
            return;
        }

        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.trim().isEmpty()) {
                switchTab(true);
                // El escaneo inicial puede restaurar la navegación después de recibir el texto compartido.
                libraryPrefs().edit().putBoolean("search_tab", true).apply();
                inputSearch.setText(sharedText.trim());
                triggerSearchOrDownload(sharedText.trim());
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
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
        if (uiState == null) return;
        PlaylistManager.getInstance(this).addObserver(playlistUiObserver);
        playlistUiObserver.run();
        cl.arichard.descargador.download.DownloadManager.get(this).addObserver(downloadObserver);
        cl.arichard.descargador.download.DownloadManager.get(this).resumePending();
        renderManagedDownloads();
        if (playerPreview != null && playerPreviewCommitted) clearPlayerPreview();
        refreshDiscoveryContent();
        cl.arichard.descargador.data.ClickStats clicks = cl.arichard.descargador.data.ClickStats.getInstance(this);
        // Solo al abrir la app: volver de otra pantalla no cambia las recomendaciones que ya estás viendo.
        if (!uiState.forYouStarted && loadsRecommendationsOnStart()) { uiState.forYouStarted = true; refreshForYou(true); }
        PlayerManager.getInstance().addListener(this);
        updateMiniPlayer();
        if (songAdapter != null) songAdapter.notifyDataSetChanged();
        // Una importación hecha desde otra pantalla (Importar, Descargas): la playlist nueva aparece al volver.
        String imported = cl.arichard.descargador.util.PlaylistImportReviewSheet.pendingReveal;
        if (!imported.isEmpty()) onPlaylistImported(imported);
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
        PlaylistManager.getInstance(this).removeObserver(playlistUiObserver);
        cl.arichard.descargador.download.DownloadManager.get(this).removeObserver(downloadObserver);
        PlayerManager.getInstance().savePosition();
        if (uiState != null) {
            rememberScreenState();
            saveLibraryNavigation();
        }
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
        Song failedSong = PlayerManager.getInstance().getCurrentSong();
        runOnUiThread(() -> cl.arichard.descargador.util.AppNotice.error(
                findViewById(R.id.bottomBarArea), message, "REINTENTAR",
                () -> PlayerManager.getInstance().retryQueuedSong(failedSong)));
    }

    @Override
    public void onProgressUpdate(int currentPos, int duration) {
        runOnUiThread(() -> {
            miniProgress.setMax(duration > 0 ? duration : 100);
            miniProgress.setProgress(currentPos);
        });
    }
}
