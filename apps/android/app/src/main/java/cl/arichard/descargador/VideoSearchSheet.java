package cl.arichard.descargador;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import cl.arichard.descargador.model.SearchResult;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.util.ImageLoader;
import cl.arichard.descargador.util.SafeTap;
import cl.arichard.descargador.util.Tr;
import cl.arichard.descargador.util.YoutubeApi;

/**
 * "Buscar con portadas": hasta 10 videos con su miniatura, para elegir varios y añadirlos a la cola sin
 * empezar a descargar. Equivale al diálogo de búsqueda con portadas del escritorio.
 */
public final class VideoSearchSheet {
    private static final int LIMIT = 10;

    private final Activity activity;
    private final Consumer<List<String>> onAdd;
    private final List<SearchResult> results = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Adapter adapter = new Adapter();
    private BottomSheetDialog dialog;
    private EditText input;
    private TextView status, empty, add;
    private ProgressBar progress;
    private int generation;

    /** @param onAdd recibe los enlaces elegidos; la pantalla los pone en la cola sin repetirlos. */
    public static void show(Activity activity, Consumer<List<String>> onAdd) { new VideoSearchSheet(activity, onAdd).open(); }

    private VideoSearchSheet(Activity activity, Consumer<List<String>> onAdd) { this.activity = activity; this.onAdd = onAdd; }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    /** M:SS o H:MM:SS; vacío si se desconoce. */
    public static String durationLabel(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** Descarta directos y próximos estrenos (sin duración) y los repetidos, como hace el escritorio. */
    public static List<SearchResult> usable(List<SearchResult> found) {
        List<SearchResult> usable = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SearchResult item : found) {
            if (item.getId().isEmpty() || item.getDurationSeconds() <= 0 || !seen.add(item.getUrl())) continue;
            usable.add(item);
        }
        return usable;
    }

    private void open() {
        dialog = new BottomSheetDialog(activity);
        View root = LayoutInflater.from(activity).inflate(R.layout.sheet_video_search, null);
        dialog.setContentView(root);
        root.getLayoutParams().height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .9f);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);

        input = root.findViewById(R.id.inputVideoQuery);
        status = root.findViewById(R.id.txtVideoStatus);
        empty = root.findViewById(R.id.txtVideoEmpty);
        progress = root.findViewById(R.id.progressVideoSearch);
        add = root.findViewById(R.id.btnVideoAdd);
        RecyclerView recycler = root.findViewById(R.id.recyclerVideos);
        recycler.setLayoutManager(new LinearLayoutManager(activity));
        recycler.setAdapter(adapter);

        View search = root.findViewById(R.id.btnVideoSearch);
        search.setOnClickListener(v -> search());
        SafeTap.install(search);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) { search(); return true; }
            return false;
        });
        root.findViewById(R.id.btnVideoAll).setOnClickListener(v -> {
            for (SearchResult item : results) selected.add(item.getId());
            adapter.notifyDataSetChanged(); renderAdd();
        });
        root.findViewById(R.id.btnVideoNone).setOnClickListener(v -> { selected.clear(); adapter.notifyDataSetChanged(); renderAdd(); });
        add.setOnClickListener(v -> {
            List<String> urls = new ArrayList<>();
            for (SearchResult item : results) if (selected.contains(item.getId())) urls.add(item.getUrl());
            if (urls.isEmpty()) return;
            dialog.dismiss();
            onAdd.accept(urls);
        });
        SafeTap.install(add);
        renderAdd();
        dialog.show();
        input.requestFocus();
    }

    private void renderAdd() {
        add.setText(Tr.f("Añadir elegidos a la cola (%d)", selected.size()));
        add.setAlpha(selected.isEmpty() ? .4f : 1f);
        add.setEnabled(!selected.isEmpty());
    }

    private void search() {
        String query = input.getText().toString().trim();
        if (query.isEmpty() || query.length() > 300) {
            status.setText(Tr.t("Escribe entre 1 y 300 caracteres"));
            return;
        }
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        final int mine = ++generation;
        progress.setVisibility(View.VISIBLE);
        status.setText(Tr.t("Buscando en YouTube…"));
        DescargadorApp.IO.execute(() -> {
            List<SearchResult> found = null;
            String error = "";
            try { found = usable(YoutubeApi.search(query, LIMIT)); }
            catch (Exception e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final List<SearchResult> shown = found;
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (mine != generation || activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                progress.setVisibility(View.INVISIBLE);
                if (shown == null) { status.setText(Tr.t("No se pudo completar la búsqueda. ") + failure); return; }
                results.clear(); results.addAll(shown); selected.clear();
                adapter.notifyDataSetChanged(); renderAdd();
                empty.setText(Tr.t("No se encontraron videos reproducibles. Prueba con otra búsqueda."));
                empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
                status.setText(shown.isEmpty() ? "" : Tr.f("%d resultados", shown.size()));
            });
        });
    }

    private void listen(SearchResult item) {
        PlayerManager.getInstance().setPlaybackSource("search", "");
        PlayerManager.getInstance().playOnline(item.getId(), item.getTitle(), item.getChannel(), item.getThumbnailUrl(), item.getUrl());
        activity.startActivity(new Intent(activity, NowPlayingActivity.class)
                .putExtra(NowPlayingActivity.EXTRA_VIDEO_ID, item.getId())
                .putExtra(NowPlayingActivity.EXTRA_URL, item.getUrl())
                .putExtra(NowPlayingActivity.EXTRA_TITLE, item.getTitle())
                .putExtra(NowPlayingActivity.EXTRA_CHANNEL, item.getChannel())
                .putExtra(NowPlayingActivity.EXTRA_THUMBNAIL, item.getThumbnailUrl()));
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_candidate, parent, false));
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            SearchResult item = results.get(position);
            h.title.setText(item.getTitle());
            String duration = durationLabel(item.getDurationSeconds());
            h.meta.setText(item.getChannel() + (duration.isEmpty() ? "" : " · " + duration));
            ImageLoader.getInstance().load(item.getThumbnailUrl(), h.thumb);
            bindCheck(h.check, selected.contains(item.getId()));
            View.OnClickListener toggle = v -> {
                if (!selected.remove(item.getId())) selected.add(item.getId());
                int index = h.getBindingAdapterPosition();
                if (index >= 0) notifyItemChanged(index);
                renderAdd();
            };
            h.itemView.setOnClickListener(toggle);
            h.check.setOnClickListener(toggle);
            h.listen.setOnClickListener(v -> listen(item));
            h.youtube.setOnClickListener(v -> {
                try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.getUrl()))); } catch (Exception ignored) {}
            });
            SafeTap.installRowTap(h.itemView);
            SafeTap.install(h.listen); SafeTap.install(h.youtube);
        }

        @Override public int getItemCount() { return results.size(); }
    }

    private void bindCheck(TextView check, boolean on) {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        if (on) disc.setColor(ContextCompat.getColor(activity, R.color.accent));
        else disc.setStroke(dp(2), ContextCompat.getColor(activity, R.color.border));
        check.setBackground(disc);
        check.setText(on ? "✓" : "");
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb; final TextView title, meta, check, listen, youtube;
        Holder(View view) {
            super(view);
            thumb = view.findViewById(R.id.imgVideoThumb); title = view.findViewById(R.id.txtVideoTitle);
            meta = view.findViewById(R.id.txtVideoMeta); check = view.findViewById(R.id.checkVideo);
            listen = view.findViewById(R.id.btnVideoListen); youtube = view.findViewById(R.id.btnVideoYoutube);
        }
    }
}
