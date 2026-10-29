package cl.arichard.descargador.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import cl.arichard.descargador.R;
import cl.arichard.descargador.util.ArtistAvatarLoader;
import cl.arichard.descargador.util.Tr;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable artist choices with asynchronously loaded circular portraits. */
public final class ArtistFilterAdapter extends RecyclerView.Adapter<ArtistFilterAdapter.Holder> {
    private final Context context;
    private final List<String> all = new ArrayList<>(), shown = new ArrayList<>();
    private final Consumer<String> listener;
    private String selected;

    public ArtistFilterAdapter(Context context, List<String> artists, String selected, Consumer<String> listener) {
        this.context = context; this.listener = listener;
        all.add(""); all.addAll(artists); shown.addAll(all);
        this.selected = selected == null ? "" : selected;
        setHasStableIds(true);
    }

    public void filter(String query) {
        String wanted = normalized(query);
        shown.clear();
        for (String artist : all) {
            if (artist.isEmpty() || wanted.isEmpty() || normalized(artist).contains(wanted)) shown.add(artist);
        }
        notifyDataSetChanged();
    }

    private static String normalized(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    @Override public long getItemId(int position) { return shown.get(position).toLowerCase(Locale.ROOT).hashCode(); }
    @Override public int getItemCount() { return shown.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_artist_filter, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        String artist = shown.get(position);
        String label = artist.isEmpty() ? Tr.t("Todos los artistas") : artist;
        boolean checked = artist.equalsIgnoreCase(selected);
        holder.name.setText(label);
        holder.name.setTextColor(ContextCompat.getColor(context, checked ? R.color.accent : R.color.text_primary));
        holder.check.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        holder.itemView.setActivated(checked);
        holder.itemView.setContentDescription(label + (checked ? ", " + Tr.t("Seleccionado") : ""));
        ArtistAvatarLoader.getInstance(context).load(artist, holder.name, dp(42));
        holder.itemView.setOnClickListener(v -> {
            selected = artist; notifyDataSetChanged(); listener.accept(artist);
        });
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name; final ImageView check;
        Holder(View item) {
            super(item); name = item.findViewById(R.id.txtArtistChoice); check = item.findViewById(R.id.imgArtistSelected);
        }
    }
}
