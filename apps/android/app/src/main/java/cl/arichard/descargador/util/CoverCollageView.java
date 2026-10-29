package cl.arichard.descargador.util;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import cl.arichard.descargador.data.SearchHistoryManager;
import cl.arichard.descargador.model.Song;
import java.util.ArrayList;
import java.util.List;

/** A bounded, asynchronously loaded cover mosaic ranked by actual playback history. */
public class CoverCollageView extends FrameLayout {
    private final List<ImageView> covers = new ArrayList<>();
    private String renderedSignature = "";

    public CoverCollageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 10 * getResources().getDisplayMetrics().density);
            }
        });
    }

    public void setSongs(List<Song> songs) {
        List<Song> top = SearchHistoryManager.getInstance(getContext()).getTopPlayed(songs, 4);
        if (songs != null) for (Song song : songs) {
            if (top.size() == 4) break;
            boolean present = false;
            for (Song selected : top) if (selected.getPath().equals(song.getPath())) { present = true; break; }
            if (!present) top.add(song);
        }
        StringBuilder signature = new StringBuilder();
        for (Song song : top) signature.append(song.getPath()).append('\u0000').append(song.getCoverPath()).append('\u0001');
        if (signature.toString().equals(renderedSignature)) return;
        renderedSignature = signature.toString();
        removeAllViews(); covers.clear();
        setBackgroundResource(cl.arichard.descargador.R.drawable.bg_cover_fallback);
        if (top.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(Tr.t("Sin canciones")); empty.setTextSize(9); empty.setTextColor(0xFFFFFFFF);
            empty.setGravity(Gravity.CENTER);
            addView(empty, new LayoutParams(-1, -1));
            setContentDescription(Tr.t("Colección sin canciones"));
            return;
        }
        for (Song song : top) {
            ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            covers.add(image); addView(image, new LayoutParams(1, 1));
            CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), image);
        }
        setContentDescription(Tr.t("Portadas de las canciones más escuchadas"));
        post(this::layoutCovers);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); layoutCovers();
    }

    private void layoutCovers() {
        int count = covers.size(), w = getWidth(), h = getHeight();
        for (int i = 0; i < count; i++) {
            int left = count == 1 ? 0 : (i % 2) * (w / 2);
            int top = count <= 2 || (count == 3 && i == 0) ? 0 : (i / 2) * (h / 2);
            int width = count == 1 || (count == 3 && i == 0) ? w : w / 2;
            int height = count <= 2 ? h : h / 2;
            if (count == 3 && i > 0) { left = (i - 1) * (w / 2); top = h / 2; }
            LayoutParams params = new LayoutParams(left + width == w - 1 ? width + 1 : width,
                    top + height == h - 1 ? height + 1 : height);
            params.leftMargin = left; params.topMargin = top;
            covers.get(i).setLayoutParams(params);
        }
    }
}
