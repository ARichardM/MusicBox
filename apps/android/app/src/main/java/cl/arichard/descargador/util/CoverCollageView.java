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
            covers.add(image); addView(image, new LayoutParams(-1, -1));
            CoverArtLoader.getInstance().loadSongCover(song.getCoverPath(), image);
        }
        setContentDescription(Tr.t("Portadas de las canciones más escuchadas"));
        // Las posiciones se calculan en onLayout, cuando el tamaño ya se conoce: no depende del orden de los posts.
        requestLayout();
    }

    /** Coloca el mosaico según la cantidad de portadas: 1 a pantalla completa, 2 en columnas y 3-4 en cuadrícula. */
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int count = covers.size();
        if (count == 0) { super.onLayout(changed, left, top, right, bottom); return; }
        int w = right - left, h = bottom - top;
        int halfW = w / 2, halfH = h / 2;
        for (int i = 0; i < count; i++) {
            int x, y, cw, ch;
            if (count == 1) { x = 0; y = 0; cw = w; ch = h; }
            else if (count == 2) { x = i * halfW; y = 0; cw = i == 0 ? halfW : w - halfW; ch = h; }
            else if (count == 3 && i == 0) { x = 0; y = 0; cw = w; ch = halfH; }
            else if (count == 3) { x = (i - 1) * halfW; y = halfH; cw = i == 1 ? halfW : w - halfW; ch = h - halfH; }
            else { x = (i % 2) * halfW; y = (i / 2) * halfH; cw = i % 2 == 0 ? halfW : w - halfW; ch = i / 2 == 0 ? halfH : h - halfH; }
            ImageView image = covers.get(i);
            image.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY));
            image.layout(x, y, x + cw, y + ch);
        }
    }
}
