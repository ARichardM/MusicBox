package cl.arichard.descargador;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cl.arichard.descargador.data.LibraryFolders;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.importer.FolderImport;
import cl.arichard.descargador.importer.ImportStatusFilter;
import cl.arichard.descargador.importer.SongMatcher;
import cl.arichard.descargador.importer.TextImport;
import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.AppNotice;
import cl.arichard.descargador.util.ImageLoader;
import cl.arichard.descargador.util.SafeTap;
import cl.arichard.descargador.util.Tr;
import cl.arichard.descargador.util.YoutubeApi;

/**
 * Importa una lista de canciones pegada como texto: 1) la lista, 2) la tabla de canciones, 3) la búsqueda de
 * coincidencias en YouTube con elección automática solo cuando es segura, y 4) el informe. El resultado son los
 * enlaces elegidos, que el Descargador pone en su cola sin empezar a descargar.
 */
public class ImporterActivity extends AppCompatActivity {
    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_TREE = "tree";
    public static final String EXTRA_LINKED = "linked";
    public static final String EXTRA_PLAYLIST = "playlist";
    private static final int CANDIDATES = 5;

    private static final class Row {
        final TextImport.ImportedSong song;
        boolean checked = true;
        List<SearchResult> found = new ArrayList<>();
        int chosen = -1, suggested = -1;
        /** idle, searching, auto, review, none o error. */
        String state = "idle";
        String reason = "";
        /** Importar carpeta: archivo local y consulta ya armada con sus etiquetas. */
        String localPath = "", queryOverride = "";
        Row(TextImport.ImportedSong song) { this.song = song; }
        String query() { return queryOverride.isEmpty() ? song.query() : queryOverride; }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Row> visible = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SongAdapter songAdapter = new SongAdapter();
    private final MatchAdapter matchAdapter = new MatchAdapter();
    private int step;
    private volatile int searchGeneration;
    private volatile boolean searching;
    private int searchDone, searchTotal;
    private boolean folderMode;
    private Uri treeUri;
    private boolean readingImage;
    private ImportStatusFilter matchFilter = ImportStatusFilter.ALL, reportFilter = ImportStatusFilter.ALL;
    private final androidx.activity.result.ActivityResultLauncher<String> pickImage = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) readImage(uri);
            });

    private EditText inputList, inputFilter;
    private TextView txtListError, txtListCount, txtTableCount, txtMatchStatus, btnStepNext, btnStepBack, btnMatchStop, btnMatchRetry;
    private ProgressBar progressMatches;
    private View[] pages;
    private TextView[] steps;

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_importer);
        String tree = getIntent().getStringExtra(EXTRA_TREE);
        folderMode = tree != null;
        if (folderMode) treeUri = Uri.parse(tree);
        ((TextView) findViewById(R.id.txtImporterTitle)).setText(folderMode ? Tr.t("Importar carpeta") : Tr.t("Importar lista"));
        findViewById(R.id.btnImporterBack).setOnClickListener(v -> goBack());
        inputList = findViewById(R.id.inputList);
        inputFilter = findViewById(R.id.inputFilter);
        txtListError = findViewById(R.id.txtListError);
        txtListCount = findViewById(R.id.txtListCount);
        txtTableCount = findViewById(R.id.txtTableCount);
        txtMatchStatus = findViewById(R.id.txtMatchStatus);
        progressMatches = findViewById(R.id.progressMatches);
        btnStepNext = findViewById(R.id.btnStepNext);
        btnStepBack = findViewById(R.id.btnStepBack);
        btnMatchStop = findViewById(R.id.btnMatchStop);
        btnMatchRetry = findViewById(R.id.btnMatchRetry);
        pages = new View[]{findViewById(R.id.pageText), findViewById(R.id.pageTable), findViewById(R.id.pageMatches), findViewById(R.id.pageReport)};
        steps = new TextView[]{findViewById(R.id.step0), findViewById(R.id.step1), findViewById(R.id.step2), findViewById(R.id.step3)};
        setupFilters(R.id.matchFilters, false);
        setupFilters(R.id.reportFilters, true);
        ((TextView) findViewById(R.id.txtMatchesEmpty)).setText(Tr.t("No hay canciones en esta categoría."));
        ((TextView) findViewById(R.id.btnReportReview)).setText(Tr.t("Revisar pendientes"));
        findViewById(R.id.btnReportReview).setOnClickListener(view -> {
            boolean review = false;
            for (Row row : rows) if (row.checked && accepts(row, ImportStatusFilter.REVIEW)) { review = true; break; }
            matchFilter = review ? ImportStatusFilter.REVIEW : ImportStatusFilter.PENDING;
            matchAdapter.reload();
            showStep(2, true);
        });
        SafeTap.install(findViewById(R.id.btnReportReview));
        String[] names = {folderMode ? Tr.t("Carpeta") : Tr.t("Lista"), Tr.t("Canciones"), Tr.t("Coincidencias"), Tr.t("Informe")};
        for (int i = 0; i < steps.length; i++) steps[i].setText(names[i]);

        ((TextView) findViewById(R.id.txtListTitle)).setText(Tr.t("Tu lista de canciones"));
        ((TextView) findViewById(R.id.txtListHelp)).setText(Tr.t("Una canción por línea, o copia una lista de Spotify o YouTube Music. No hace falta ordenar nada."));
        inputList.setHint(Tr.t("Artista - Canción\nOtra canción"));
        ((TextView) findViewById(R.id.btnListPaste)).setText(Tr.t("Pegar"));
        ((TextView) findViewById(R.id.btnListTidy)).setText(Tr.t("Limpiar texto"));
        inputFilter.setHint(Tr.t("Buscar en la lista"));
        ((TextView) findViewById(R.id.btnRowsAll)).setText(Tr.t("Marcar todas"));
        ((TextView) findViewById(R.id.btnRowsNone)).setText(Tr.t("Desmarcar"));
        ((TextView) findViewById(R.id.btnRowAdd)).setText(Tr.t("Añadir fila"));
        btnMatchStop.setText(Tr.t("Cancelar"));
        btnMatchRetry.setText(Tr.t("Reintentar pendientes"));
        ((TextView) findViewById(R.id.btnReportCopy)).setText(Tr.t("Copiar informe"));
        ((TextView) findViewById(R.id.btnReportShare)).setText(Tr.t("Compartir informe"));

        RecyclerView songs = findViewById(R.id.recyclerSongs);
        songs.setLayoutManager(new LinearLayoutManager(this));
        songs.setAdapter(songAdapter);
        RecyclerView matches = findViewById(R.id.recyclerMatches);
        matches.setLayoutManager(new LinearLayoutManager(this));
        matches.setAdapter(matchAdapter);
        if (matches.getItemAnimator() != null) matches.getItemAnimator().setChangeDuration(0);

        inputList.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                txtListError.setVisibility(View.GONE);
                int count = TextImport.cleanQueries(s.toString(), false).size();
                txtListCount.setText(count == 1 ? Tr.t("1 línea") : Tr.f("%d líneas", count));
            }
        });
        inputFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        findViewById(R.id.btnListPaste).setOnClickListener(v -> paste());
        findViewById(R.id.btnListTidy).setOnClickListener(v -> {
            List<String> tidy = TextImport.organizePlaylistText(inputList.getText().toString(), null);
            inputList.setText(TextUtils.join("\n", tidy));
        });
        ((TextView) findViewById(R.id.btnListImage)).setText(Tr.t("Leer imagen"));
        findViewById(R.id.btnListImage).setOnClickListener(v -> pickImage.launch("image/*"));
        SafeTap.install(findViewById(R.id.btnListImage));
        findViewById(R.id.btnRowsAll).setOnClickListener(v -> { for (Row r : visible) r.checked = true; songAdapter.notifyDataSetChanged(); renderTableCount(); });
        findViewById(R.id.btnRowsNone).setOnClickListener(v -> { for (Row r : visible) r.checked = false; songAdapter.notifyDataSetChanged(); renderTableCount(); });
        findViewById(R.id.btnRowAdd).setOnClickListener(v -> editRow(null));
        btnMatchStop.setOnClickListener(v -> stopSearch());
        btnMatchRetry.setOnClickListener(v -> { if (!searching) startSearch(true); });
        findViewById(R.id.btnReportCopy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("informe", reportText()));
            AppNotice.info(findViewById(R.id.importerRoot), Tr.t("Informe copiado"));
        });
        findViewById(R.id.btnReportShare).setOnClickListener(v -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, reportText()), Tr.t("Compartir informe"))));
        for (View v : new View[]{btnStepNext, btnStepBack, btnMatchStop, btnMatchRetry, findViewById(R.id.btnListPaste), findViewById(R.id.btnListTidy),
                findViewById(R.id.btnRowsAll), findViewById(R.id.btnRowsNone), findViewById(R.id.btnRowAdd)}) SafeTap.install(v);
        btnStepNext.setOnClickListener(v -> goNext());
        btnStepBack.setOnClickListener(v -> goBack());
        showStep(0, false);
        if (folderMode) {
            ((TextView) findViewById(R.id.txtListTitle)).setText(Tr.t("Leyendo la carpeta…"));
            ((TextView) findViewById(R.id.txtListHelp)).setText(Tr.t("Buscando archivos de audio y sus etiquetas."));
            for (int id : new int[]{R.id.inputList, R.id.txtListCount, R.id.btnListPaste, R.id.btnListTidy, R.id.btnListImage}) findViewById(id).setVisibility(View.GONE);
            btnStepNext.setAlpha(.4f); btnStepNext.setEnabled(false);
            scanFolder();
        }
    }

    @Override protected void onDestroy() {
        searchGeneration++;
        super.onDestroy();
    }

    private void readImage(Uri uri) {
        if (readingImage) return;
        readingImage = true;
        findViewById(R.id.btnListImage).setEnabled(false);
        ((TextView) findViewById(R.id.btnListImage)).setText(Tr.t("Leyendo imagen…"));
        findViewById(R.id.progressOcr).setVisibility(View.VISIBLE);
        renderNext();
        // La imagen se abre fuera del hilo de interfaz. El modelo viene incluido y funciona sin conexión.
        DescargadorApp.IO.execute(() -> {
            try {
                com.google.mlkit.vision.common.InputImage image = com.google.mlkit.vision.common.InputImage.fromFilePath(this, uri);
                com.google.mlkit.vision.text.TextRecognizer recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                        com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
                recognizer.process(image)
                        .addOnSuccessListener(result -> {
                            if (isDestroyed() || isFinishing()) return;
                            String text = result.getText().trim();
                            if (text.isEmpty()) { imageError(Tr.t("No se encontró texto. Prueba con una imagen más nítida.")); return; }
                            EditText review = new EditText(this);
                            review.setText(text);
                            review.setGravity(android.view.Gravity.TOP);
                            review.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                            review.setMinLines(4); review.setMaxLines(10);
                            review.setPadding(dp(20), dp(12), dp(20), dp(12));
                            new MaterialAlertDialogBuilder(this).setTitle(Tr.t("Revisar texto de la imagen"))
                                    .setMessage(Tr.t("Corrige los títulos y artistas antes de añadirlos a tu lista."))
                                    .setView(review).setNegativeButton(Tr.t("Cancelar"), null)
                                    .setPositiveButton(Tr.t("Añadir a la lista"), (dialog, which) -> {
                                        String current = inputList.getText().toString().trim();
                                        String edited = review.getText().toString().trim();
                                        if (!edited.isEmpty()) inputList.setText(current.isEmpty() ? edited : current + "\n" + edited);
                                    }).show();
                        })
                        .addOnFailureListener(error -> imageError(Tr.t("No se pudo leer la imagen. Prueba con otra.")))
                        .addOnCompleteListener(task -> { recognizer.close(); finishReadingImage(); });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    imageError(Tr.t("No se pudo leer la imagen. Prueba con otra."));
                    finishReadingImage();
                });
            }
        });
    }

    private void imageError(String text) {
        if (isDestroyed() || isFinishing()) return;
        txtListError.setText(text);
        txtListError.setVisibility(View.VISIBLE);
    }

    private void finishReadingImage() {
        if (isDestroyed()) return;
        readingImage = false;
        findViewById(R.id.btnListImage).setEnabled(true);
        ((TextView) findViewById(R.id.btnListImage)).setText(Tr.t("Leer imagen"));
        findViewById(R.id.progressOcr).setVisibility(View.GONE);
        renderNext();
    }

    // --- Navegación ---

    private void showStep(int index, boolean animate) {
        step = index;
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == index;
            steps[i].setBackgroundResource(selected ? R.drawable.bg_chip_active : 0);
            steps[i].setTextColor(ContextCompat.getColor(this, selected ? R.color.chip_active_text : R.color.text_secondary));
            View page = pages[i];
            if (selected) {
                page.setVisibility(View.VISIBLE);
                if (animate) {
                    page.setAlpha(0f); page.setTranslationY(dp(10));
                    page.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                } else { page.setAlpha(1f); page.setTranslationY(0f); }
            } else {
                page.animate().cancel();
                page.setVisibility(View.GONE);
            }
        }
        btnStepBack.setText(index == 0 ? Tr.t("Cancelar") : Tr.t("Atrás"));
        String[] labels = {Tr.t("Revisar canciones →"), Tr.t("Buscar canciones marcadas →"), Tr.t("Ver informe →"),
                folderMode ? Tr.t("Enlazar a una playlist") : Tr.t("Añadir a la cola")};
        btnStepNext.setText(labels[index]);
        renderNext();
        if (index == 1) { applyFilter(); renderTableCount(); }
        if (index == 3) renderReport();
    }

    private void renderNext() {
        boolean blocked = readingImage || step == 2 && searching;
        btnStepNext.setAlpha(blocked ? .4f : 1f);
        btnStepNext.setEnabled(!blocked);
    }

    private void goBack() {
        if (step == 0) { setResult(RESULT_CANCELED); finish(); return; }
        if (step == 2) stopSearch();
        showStep(step - 1, true);
    }

    private void goNext() {
        if (step == 0) {
            if (!buildRows()) return;
            showStep(1, true);
        } else if (step == 1) {
            boolean any = false;
            for (Row r : rows) if (r.checked) any = true;
            if (!any) { AppNotice.error(findViewById(R.id.importerRoot), Tr.t("Marca al menos una canción.")); return; }
            matchAdapter.reload();
            showStep(2, true);
            startSearch(false);
        } else if (step == 2) {
            showStep(3, true);
        } else if (folderMode) {
            finishFolder();
        } else {
            finishWithUrls();
        }
    }

    private void paste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence text = data != null && data.getItemCount() > 0 ? data.getItemAt(0).coerceToText(this) : null;
        if (text == null || text.toString().trim().isEmpty()) { AppNotice.info(findViewById(R.id.importerRoot), Tr.t("El portapapeles está vacío")); return; }
        String current = inputList.getText().toString().trim();
        inputList.setText(current.isEmpty() ? text.toString() : current + "\n" + text);
        inputList.setSelection(inputList.getText().length());
    }

    // --- Paso 1 → 2: de texto a filas ---

    private boolean buildRows() {
        String text = inputList.getText().toString();
        List<Row> built = new ArrayList<>();
        try {
            TextImport.ParsedPlaylist parsed = TextImport.parseExportedPlaylist(text);
            if (parsed != null) {
                for (TextImport.ImportedSong song : parsed.songs) built.add(new Row(song));
            } else {
                for (String line : TextImport.cleanQueries(text, true)) built.add(new Row(new TextImport.ImportedSong(line)));
            }
        } catch (IllegalArgumentException e) {
            txtListError.setText(Tr.t(e.getMessage()));
            txtListError.setVisibility(View.VISIBLE);
            return false;
        }
        if (built.isEmpty()) {
            txtListError.setText(Tr.t("Pega al menos una canción."));
            txtListError.setVisibility(View.VISIBLE);
            return false;
        }
        if (built.size() > TextImport.MAX_QUERIES) built = new ArrayList<>(built.subList(0, TextImport.MAX_QUERIES));
        rows.clear(); rows.addAll(built);
        inputFilter.setText("");
        return true;
    }

    // --- Paso 2: tabla ---

    private void applyFilter() {
        String[] words = SongMatcher.normalize(inputFilter.getText().toString()).split(" ");
        visible.clear();
        for (Row row : rows) {
            String hay = SongMatcher.normalize(row.song.title + " " + row.song.artist + " " + row.song.album);
            boolean all = true;
            for (String word : words) if (!word.isEmpty() && !hay.contains(word)) all = false;
            if (all) visible.add(row);
        }
        songAdapter.notifyDataSetChanged();
        renderTableCount();
    }

    private void renderTableCount() {
        int marked = 0;
        for (Row r : rows) if (r.checked) marked++;
        txtTableCount.setText(Tr.f("%d canciones · %d marcadas · %d visibles", rows.size(), marked, visible.size()));
    }

    private void editRow(Row row) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText title = new EditText(this), artist = new EditText(this);
        title.setHint(Tr.t("Canción o título")); artist.setHint(Tr.t("Artista (opcional)"));
        title.setSingleLine(true); artist.setSingleLine(true);
        if (row != null) { title.setText(row.song.title); artist.setText(row.song.artist); }
        form.addView(title); form.addView(artist);
        new MaterialAlertDialogBuilder(this)
                .setTitle(row == null ? Tr.t("Añadir fila") : Tr.t("Editar canción"))
                .setView(form)
                .setNegativeButton(Tr.t("Cancelar"), null)
                .setPositiveButton(Tr.t("Guardar"), (d, w) -> {
                    String newTitle = title.getText().toString().trim(), newArtist = artist.getText().toString().trim();
                    if (newTitle.isEmpty() || rows.size() >= TextImport.MAX_QUERIES && row == null) return;
                    if (row == null) {
                        TextImport.ImportedSong song = new TextImport.ImportedSong(newTitle);
                        song.artist = newArtist;
                        rows.add(0, new Row(song));
                    } else {
                        row.song.title = newTitle; row.song.artist = newArtist;
                        // Una edición invalida la búsqueda anterior de esa fila.
                        row.state = "idle"; row.found = new ArrayList<>(); row.chosen = row.suggested = -1; row.reason = "";
                    }
                    applyFilter();
                }).show();
    }

    private class SongAdapter extends RecyclerView.Adapter<SongHolder> {
        @Override public SongHolder onCreateViewHolder(ViewGroup parent, int type) {
            return new SongHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_import_song, parent, false));
        }
        @Override public void onBindViewHolder(SongHolder h, int position) {
            Row row = visible.get(position);
            h.title.setText(row.song.title);
            List<String> parts = new ArrayList<>();
            if (!row.song.artist.isEmpty()) parts.add(row.song.artist);
            if (!row.song.album.isEmpty()) parts.add(row.song.album);
            if (!row.song.duration.isEmpty()) parts.add(row.song.duration);
            h.sub.setText(TextUtils.join(" · ", parts));
            h.sub.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
            h.warn.setText(Tr.t(row.song.reviewReason));
            h.warn.setVisibility(row.song.reviewReason.isEmpty() ? View.GONE : View.VISIBLE);
            bindCheck(h.check, row.checked);
            View.OnClickListener toggle = v -> {
                row.checked = !row.checked;
                int index = h.getBindingAdapterPosition();
                if (index >= 0) notifyItemChanged(index);
                renderTableCount();
            };
            h.check.setOnClickListener(toggle);
            h.itemView.setOnClickListener(v -> editRow(row));
            h.delete.setOnClickListener(v -> { rows.remove(row); applyFilter(); });
            SafeTap.installRowTap(h.itemView);
        }
        @Override public int getItemCount() { return visible.size(); }
    }

    private static final class SongHolder extends RecyclerView.ViewHolder {
        final TextView check, title, sub, warn, delete;
        SongHolder(View view) {
            super(view);
            check = view.findViewById(R.id.checkSong); title = view.findViewById(R.id.txtSongTitle);
            sub = view.findViewById(R.id.txtSongSub); warn = view.findViewById(R.id.txtSongWarn); delete = view.findViewById(R.id.btnSongDelete);
        }
    }

    private void bindCheck(TextView check, boolean on) {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        if (on) disc.setColor(ContextCompat.getColor(this, R.color.accent));
        else disc.setStroke(dp(2), ContextCompat.getColor(this, R.color.border));
        check.setBackground(disc);
        check.setText(on ? "✓" : "");
    }

    // --- Paso 3: búsqueda de coincidencias ---

    private void startSearch(boolean onlyPending) {
        List<Row> targets = new ArrayList<>();
        for (Row r : rows) {
            if (!r.checked) continue;
            boolean take = r.state.equals("idle") || r.state.equals("searching")
                    || (onlyPending && (r.state.equals("none") || r.state.equals("error")));
            if (take) targets.add(r);
        }
        if (targets.isEmpty()) { renderSearchStatus(); return; }
        final int mine = ++searchGeneration;
        searching = true;
        searchDone = 0; searchTotal = targets.size();
        for (Row r : targets) r.state = "idle";
        renderSearchStatus();
        new Thread(() -> {
            for (Row row : targets) {
                if (mine != searchGeneration) return;
                row.state = "searching";
                main.post(() -> { if (mine == searchGeneration) matchAdapter.reload(); });
                search(row);
                searchDone++;
                main.post(() -> { if (mine == searchGeneration) { renderSearchStatus(); matchAdapter.reload(); } });
            }
            if (mine == searchGeneration) main.post(() -> { searching = false; renderSearchStatus(); renderNext(); });
        }).start();
    }

    private void search(Row row) {
        try {
            List<SearchResult> usable = usable(YoutubeApi.search(row.query(), CANDIDATES));
            if (usable.isEmpty() && !row.query().equals(row.song.title)) usable = usable(YoutubeApi.search(row.song.title, CANDIDATES));
            row.found = usable;
            if (usable.isEmpty()) { row.state = "none"; row.reason = Tr.t("Sin resultados"); return; }
            List<SongMatcher.Candidate> candidates = new ArrayList<>();
            for (SearchResult r : usable) candidates.add(new SongMatcher.Candidate(r.getId(), r.getTitle(), r.getChannel(), r.getDurationSeconds()));
            int best = SongMatcher.best(row.query(), candidates, row.song.title, row.song.artist, row.song.duration);
            SongMatcher.Match match = SongMatcher.score(row.query(), candidates.get(best), row.song.title, row.song.artist, row.song.duration);
            row.suggested = best;
            if (match.autoSelect) { row.chosen = best; row.state = "auto"; row.reason = ""; }
            else { row.chosen = -1; row.state = "review"; row.reason = Tr.t(match.reviewReason); }
        } catch (Exception e) {
            row.state = "error";
            row.reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private static List<SearchResult> usable(List<SearchResult> found) { return VideoSearchSheet.usable(found); }

    private void stopSearch() {
        searchGeneration++;
        searching = false;
        for (Row r : rows) if (r.state.equals("searching")) r.state = "idle";
        renderSearchStatus();
        renderNext();
        matchAdapter.reload();
    }

    private void renderSearchStatus() {
        int pending = 0, chosen = 0, review = 0;
        for (Row r : rows) {
            if (!r.checked) continue;
            if (accepts(r, ImportStatusFilter.READY)) chosen++;
            else if (accepts(r, ImportStatusFilter.REVIEW)) review++;
            else pending++;
        }
        txtMatchStatus.setText(searching ? Tr.f("Buscando %d de %d…", Math.min(searchDone + 1, searchTotal), searchTotal)
                : Tr.f("%d elegidas · %d por revisar · %d pendientes", chosen, review, pending));
        progressMatches.setProgress(searchTotal == 0 ? 0 : searchDone * 1000 / searchTotal);
        btnMatchStop.setVisibility(searching ? View.VISIBLE : View.GONE);
        btnMatchRetry.setVisibility(!searching && pending > 0 ? View.VISIBLE : View.GONE);
        renderNext();
    }

    private final class MatchAdapter extends RecyclerView.Adapter<MatchHolder> {
        private final List<Row> shown = new ArrayList<>();
        @Override public MatchHolder onCreateViewHolder(ViewGroup parent, int type) {
            return new MatchHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_import_match, parent, false));
        }
        /** Vuelve a leer las filas marcadas y repinta. */
        void reload() {
            shown.clear();
            for (Row r : rows) if (r.checked && accepts(r, matchFilter)) shown.add(r);
            renderFilters(R.id.matchFilters, matchFilter);
            findViewById(R.id.txtMatchesEmpty).setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            notifyDataSetChanged();
        }
        @Override public void onBindViewHolder(MatchHolder h, int position) {
            Row row = shown.get(position);
            h.query.setText(row.query());
            int color; String label;
            switch (row.state) {
                case "auto": label = Tr.t(row.chosen >= 0 ? "✓ AUTO" : "REVISAR"); color = row.chosen >= 0 ? R.color.success : R.color.accent; break;
                case "review": label = row.chosen >= 0 ? Tr.t("ELEGIDA") : Tr.t("REVISAR"); color = row.chosen >= 0 ? R.color.success : R.color.accent; break;
                case "searching": label = Tr.t("BUSCANDO…"); color = R.color.text_secondary; break;
                case "none": label = Tr.t("SIN RESULTADOS"); color = R.color.heart_red; break;
                case "error": label = Tr.t("ERROR"); color = R.color.heart_red; break;
                default: label = Tr.t("EN COLA"); color = R.color.text_secondary;
            }
            if (row.chosen >= 0 && row.state.equals("review")) { label = Tr.t("ELEGIDA"); color = R.color.success; }
            int tint = ContextCompat.getColor(ImporterActivity.this, color);
            h.badge.setText(label); h.badge.setTextColor(tint);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(12)); pill.setColor((tint & 0x00FFFFFF) | 0x26000000);
            h.badge.setBackground(pill);
            boolean needsReview = row.chosen < 0 && !row.reason.isEmpty();
            h.reason.setText(row.reason);
            h.reason.setVisibility(needsReview ? View.VISIBLE : View.GONE);
            h.candidates.removeAllViews();
            for (int i = 0; i < row.found.size(); i++) {
                final int index = i;
                SearchResult item = row.found.get(i);
                View view = LayoutInflater.from(ImporterActivity.this).inflate(R.layout.item_import_candidate, h.candidates, false);
                TextView radio = view.findViewById(R.id.radioCandidate);
                bindCheck(radio, row.chosen == i);
                ((TextView) view.findViewById(R.id.txtCandidateTitle)).setText(item.getTitle());
                String duration = VideoSearchSheet.durationLabel(item.getDurationSeconds());
                ((TextView) view.findViewById(R.id.txtCandidateMeta)).setText(item.getChannel() + (duration.isEmpty() ? "" : " · " + duration)
                        + (i == row.suggested ? " · " + Tr.t("sugerida") : ""));
                ImageLoader.getInstance().load(item.getThumbnailUrl(), (ImageView) view.findViewById(R.id.imgCandidate));
                view.setOnClickListener(v -> {
                    row.chosen = row.chosen == index ? -1 : index;
                    if (row.state.equals("review") && row.chosen < 0) row.state = "review";
                    renderSearchStatus();
                    reload();
                });
                view.findViewById(R.id.btnCandidateListen).setOnClickListener(v -> listen(item));
                h.candidates.addView(view);
            }
        }
        @Override public int getItemCount() { return shown.size(); }
    }

    private static final class MatchHolder extends RecyclerView.ViewHolder {
        final TextView query, badge, reason; final LinearLayout candidates;
        MatchHolder(View view) {
            super(view);
            query = view.findViewById(R.id.txtMatchQuery); badge = view.findViewById(R.id.badgeMatch);
            reason = view.findViewById(R.id.txtMatchReason); candidates = view.findViewById(R.id.layoutCandidates);
        }
    }

    private void listen(SearchResult item) {
        PlayerManager.getInstance().setPlaybackSource("search", "");
        PlayerManager.getInstance().playOnline(item.getId(), item.getTitle(), item.getChannel(), item.getThumbnailUrl(), item.getUrl());
        startActivity(new Intent(this, NowPlayingActivity.class)
                .putExtra(NowPlayingActivity.EXTRA_VIDEO_ID, item.getId()).putExtra(NowPlayingActivity.EXTRA_URL, item.getUrl())
                .putExtra(NowPlayingActivity.EXTRA_TITLE, item.getTitle()).putExtra(NowPlayingActivity.EXTRA_CHANNEL, item.getChannel())
                .putExtra(NowPlayingActivity.EXTRA_THUMBNAIL, item.getThumbnailUrl()));
    }

    // --- Paso 4: informe y resultado ---

    private Map<String, List<Row>> chosenByUrl() {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!r.checked || r.chosen < 0 || r.chosen >= r.found.size()) continue;
            groups.computeIfAbsent(r.found.get(r.chosen).getUrl(), k -> new ArrayList<>()).add(r);
        }
        return groups;
    }

    private static boolean accepts(Row row, ImportStatusFilter filter) {
        return filter.matches(row.state, row.chosen >= 0 && row.chosen < row.found.size());
    }

    private void setupFilters(int containerId, boolean report) {
        View container = findViewById(containerId);
        int[] ids = {R.id.filterAll, R.id.filterReady, R.id.filterReview, R.id.filterPending};
        ImportStatusFilter[] filters = ImportStatusFilter.values();
        for (int index = 0; index < ids.length; index++) {
            ImportStatusFilter filter = filters[index];
            View button = container.findViewById(ids[index]);
            button.setOnClickListener(view -> {
                if (report) { reportFilter = filter; renderReport(); }
                else {
                    matchFilter = filter;
                    matchAdapter.reload();
                    ((RecyclerView) findViewById(R.id.recyclerMatches)).scrollToPosition(0);
                }
            });
            SafeTap.install(button);
        }
    }

    private void renderFilters(int containerId, ImportStatusFilter selected) {
        View container = findViewById(containerId);
        int[] ids = {R.id.filterAll, R.id.filterReady, R.id.filterReview, R.id.filterPending};
        String[] labels = {Tr.t("Todas"), Tr.t("Elegidas"), Tr.t("Por revisar"), Tr.t("Pendientes")};
        ImportStatusFilter[] filters = ImportStatusFilter.values();
        for (int index = 0; index < ids.length; index++) {
            int count = 0;
            for (Row row : rows) if (row.checked && accepts(row, filters[index])) count++;
            TextView button = container.findViewById(ids[index]);
            button.setText(labels[index] + " · " + count);
            boolean active = filters[index] == selected;
            button.setSelected(active);
            button.setBackgroundResource(active ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            button.setTextColor(ContextCompat.getColor(this, active ? R.color.chip_active_text : R.color.text_secondary));
        }
    }

    private String reportText() { return reportText(ImportStatusFilter.ALL); }

    private String reportText(ImportStatusFilter filter) {
        StringBuilder out = new StringBuilder();
        int marked = 0, chosen = 0;
        List<Row> pending = new ArrayList<>();
        for (Row r : rows) {
            if (!r.checked || !accepts(r, filter)) continue;
            marked++;
            if (accepts(r, ImportStatusFilter.READY)) chosen++; else pending.add(r);
        }
        Map<String, List<Row>> groups = chosenByUrl();
        out.append(Tr.f("Canciones marcadas: %d · elegidas: %d · pendientes: %d", marked, chosen, pending.size())).append('\n');
        if (groups.size() < chosen) out.append(Tr.f("Varias canciones apuntan al mismo video: %d enlaces únicos.", groups.size())).append('\n');
        if (!pending.isEmpty()) {
            out.append('\n').append(Tr.t("PENDIENTES")).append('\n');
            for (Row r : pending) out.append("- ").append(r.query()).append(": ")
                    .append(r.reason.isEmpty() ? Tr.t("sin elegir") : r.reason).append('\n');
        }
        if (chosen > 0) {
            out.append('\n').append(Tr.t("ELEGIDAS")).append('\n');
            for (Row r : rows) {
                if (!r.checked || !accepts(r, filter) || !accepts(r, ImportStatusFilter.READY)) continue;
                SearchResult pick = r.found.get(r.chosen);
                out.append("- ").append(r.query()).append(" → ").append(pick.getTitle()).append('\n').append("  ").append(pick.getUrl()).append('\n');
            }
        }
        return marked == 0 ? Tr.t("No hay canciones en esta categoría.") : out.toString().trim();
    }

    private void renderReport() {
        int chosen = 0, marked = 0;
        for (Row r : rows) { if (!r.checked) continue; marked++; if (r.chosen >= 0) chosen++; }
        ((TextView) findViewById(R.id.txtReportSummary)).setText(folderMode ? Tr.f("%d de %d canciones listas para enlazar", chosen, marked)
                : Tr.f("%d de %d canciones listas para la cola", chosen, marked));
        renderFilters(R.id.reportFilters, reportFilter);
        ((TextView) findViewById(R.id.txtReport)).setText(reportText(reportFilter));
        findViewById(R.id.btnReportReview).setVisibility(marked > chosen ? View.VISIBLE : View.GONE);
        btnStepNext.setAlpha(chosen == 0 ? .4f : 1f);
        btnStepNext.setEnabled(chosen > 0);
    }

    // --- Importar carpeta: los archivos locales pasan por los mismos pasos que una lista de texto ---

    private void scanFolder() {
        final Uri tree = treeUri;
        new Thread(() -> {
            List<Row> built = new ArrayList<>();
            boolean truncated = false;
            String error = null;
            try {
                List<String[]> files = new ArrayList<>();   // {documentId, ruta}
                collectAudio(tree, android.provider.DocumentsContract.getTreeDocumentId(tree), files);
                files.sort((a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
                truncated = files.size() > FolderImport.MAX_FILES;
                for (String[] file : files.subList(0, Math.min(files.size(), FolderImport.MAX_FILES))) {
                    built.add(describeFile(tree, file[0], file[1]));
                    final int count = built.size();
                    main.post(() -> ((TextView) findViewById(R.id.txtListHelp)).setText(Tr.f("%d archivos leídos…", count)));
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final boolean tooMany = truncated;
            final String failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (failure != null || built.isEmpty()) {
                    ((TextView) findViewById(R.id.txtListTitle)).setText(Tr.t("No se encontró música"));
                    ((TextView) findViewById(R.id.txtListHelp)).setText(failure != null
                            ? Tr.t("No se pudo leer esa carpeta.") : Tr.t("Esa carpeta no tiene archivos de audio compatibles."));
                    return;
                }
                rows.clear(); rows.addAll(built);
                if (tooMany) AppNotice.info(findViewById(R.id.importerRoot), Tr.f("Se importan los primeros %d archivos.", FolderImport.MAX_FILES));
                showStep(1, true);
            });
        }).start();
    }

    private void collectAudio(Uri tree, String rootId, List<String[]> out) {
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        pending.add(rootId);
        int visited = 0;
        while (!pending.isEmpty() && out.size() <= FolderImport.MAX_FILES && visited++ < 5000) {
            String parent = pending.poll();
            Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
            try (android.database.Cursor cursor = getContentResolver().query(children, new String[]{
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                    if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) pending.add(id);
                    else if (FolderImport.isAudio(name)) {
                        String path = LibraryFolders.pathFromDocumentId(id);
                        out.add(new String[]{id, path == null ? name : path});
                    }
                }
            } catch (Exception ignored) { /* una subcarpeta ilegible no detiene el resto */ }
        }
    }

    private Row describeFile(Uri tree, String documentId, String path) {
        String name = FolderImport.baseName(path), title = "", artist = "";
        long seconds = 0;
        try {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, documentId));
                String tagTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                String tagArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (tagTitle != null) title = tagTitle.trim();
                if (tagArtist != null) artist = tagArtist.trim();
                if (duration != null) seconds = Math.round(Long.parseLong(duration) / 1000.0);
            } finally { retriever.release(); }
        } catch (Exception ignored) { /* sin etiquetas: se usa el nombre del archivo */ }
        if (title.isEmpty()) {
            String[] split = FolderImport.splitName(FolderImport.titleFromFilename(FolderImport.stem(name)));
            title = split[0]; artist = split[1];
        }
        TextImport.ImportedSong song = new TextImport.ImportedSong(title);
        song.artist = artist;
        song.duration = FolderImport.clock(seconds);
        Row row = new Row(song);
        row.localPath = path;
        row.queryOverride = FolderImport.buildQuery(title, artist);
        return row;
    }

    private void finishFolder() {
        List<Row> picked = new ArrayList<>();
        for (Row r : rows) if (r.checked && r.chosen >= 0 && r.chosen < r.found.size() && !r.localPath.isEmpty()) picked.add(r);
        if (picked.isEmpty()) return;
        cl.arichard.descargador.util.PlaylistPicker.show(this, Tr.t("Enlazar a una playlist"), true, selected -> {
                    if (selected != null) { applyToPlaylist(selected, picked); return; }
                    EditText input = new EditText(this);
                    String folder = FolderImport.baseName(LibraryFolders.pathFromTree(treeUri) == null ? "" : LibraryFolders.pathFromTree(treeUri));
                    input.setText(folder); input.setSingleLine(true);
                    LinearLayout box = new LinearLayout(this);
                    box.setPadding(dp(24), dp(8), dp(24), 0);
                    box.addView(input, new LinearLayout.LayoutParams(-1, -2));
                    new MaterialAlertDialogBuilder(this).setTitle(Tr.t("Nombre de la playlist")).setView(box)
                            .setNegativeButton(Tr.t("Cancelar"), null)
                            .setPositiveButton(Tr.t("Crear"), (d, w) -> {
                                String name = input.getText().toString().trim();
                                if (!name.isEmpty()) applyToPlaylist(name, picked);
                            }).show();
                });
    }

    /** Añade cada video elegido a la playlist y marca el archivo local como su copia; no descarga nada. */
    private void applyToPlaylist(String name, List<Row> picked) {
        PlaylistManager playlists = PlaylistManager.getInstance(this);
        if (!playlists.getPlaylistNames().contains(name)) playlists.createPlaylist(name);
        int linked = 0;
        for (Row row : picked) {
            SearchResult video = row.found.get(row.chosen);
            playlists.addOnlineTrack(name, video);
            playlists.linkLocalFile(row.localPath, video.getId());
            linked++;
        }
        playlists.linkLocalSongs(new ArrayList<>());
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_LINKED, linked).putExtra(EXTRA_PLAYLIST, name));
        finish();
    }

    private void finishWithUrls() {
        Map<String, List<Row>> groups = chosenByUrl();
        if (groups.isEmpty()) return;
        ArrayList<String> urls = new ArrayList<>(groups.keySet());
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(EXTRA_URLS, urls).putExtra(EXTRA_COUNT, urls.size()));
        finish();
    }
}
