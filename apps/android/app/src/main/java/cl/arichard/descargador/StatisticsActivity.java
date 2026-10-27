package cl.arichard.descargador;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import cl.arichard.descargador.data.ClickStats;
import cl.arichard.descargador.data.MusicScanner;
import cl.arichard.descargador.data.PlaylistManager;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.util.CoverArtLoader;
import cl.arichard.descargador.util.Tr;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Account statistics. Time is measured while playback advances; historical clicks remain intact. */
public final class StatisticsActivity extends AppCompatActivity {
    private LinearLayout content, ranking;
    private boolean playlists;
    private final Map<String, Song> library = new HashMap<>();
    private List<JSONObject> songs = new ArrayList<>(), lists = new ArrayList<>();
    private final Runnable observer = () -> runOnUiThread(this::render);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) playlists = state.getBoolean("playlists");
        ClickStats.getInstance(this).addObserver(observer);
        render();
        new Thread(() -> {
            List<Song> scanned = MusicScanner.scanAllMusic(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                PlaylistManager manager = PlaylistManager.getInstance(this);
                for (Song song : scanned) {
                    library.put(ClickStats.keyOf(song), song);
                    String id = manager.videoIdFor(song.getPath());
                    if (id != null) library.put("yt:" + id, song);
                }
                render();
            });
        }, "statistics-covers").start();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("playlists", playlists); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { ClickStats.getInstance(this).removeObserver(observer); super.onDestroy(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int resource) { return ContextCompat.getColor(this, resource); }
    private TextView text(String value, int size, boolean bold, boolean muted) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(color(muted ? R.color.text_secondary : R.color.text_primary));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + " s";
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + minutes % 60 + " min";
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        int scrollY = content != null && content.getParent() instanceof ScrollView ? ((ScrollView)content.getParent()).getScrollY() : 0;
        ClickStats stats = ClickStats.getInstance(this);
        songs = stats.listeningRows(false); lists = stats.listeningRows(true);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(color(R.color.bg_primary));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(22),dp(18),dp(22),dp(38));
        scroll.addView(content); setContentView(scroll);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this); back.setImageResource(R.drawable.ic_arrow_back); back.setColorFilter(color(R.color.text_primary)); back.setBackgroundResource(android.R.color.transparent); back.setContentDescription(Tr.t("Volver")); back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView heading = text(Tr.t("Tu música en cifras"), 24, true, false); toolbar.addView(heading, new LinearLayout.LayoutParams(0,-2,1)); content.addView(toolbar);
        TextView subtitle = text(Tr.t("Cada reproducción cuenta"),14,false,true); subtitle.setPadding(dp(4),dp(6),0,dp(22)); content.addView(subtitle);
        long totalMillis = 0; for (JSONObject row : songs) totalMillis += row.optLong("ms");
        LinearLayout metrics = new LinearLayout(this); metrics.setOrientation(LinearLayout.HORIZONTAL);
        metric(metrics, duration(totalMillis), Tr.t("Tiempo escuchado")); metric(metrics, String.valueOf(stats.total()), Tr.t("Reproducciones"));
        content.addView(metrics);
        TextView note = text(Tr.t("El tiempo se mide desde esta actualización. Las reproducciones anteriores se conservan."),12,false,true); note.setPadding(dp(4),dp(12),dp(4),dp(20)); content.addView(note);
        com.google.android.material.button.MaterialButtonToggleGroup tabs = new com.google.android.material.button.MaterialButtonToggleGroup(this);
        tabs.setSingleSelection(true); tabs.setSelectionRequired(true);
        for (int i=0;i<2;i++) {
            com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setId(i==0?android.R.id.button1:android.R.id.button2); button.setText(Tr.t(i==0?"Canciones":"Playlists")); button.setAllCaps(false); button.setTextSize(14); button.setCornerRadius(dp(24)); button.setLetterSpacing(0);
            tabs.addView(button,new LinearLayout.LayoutParams(0,dp(52),1));
        }
        tabs.check(playlists?android.R.id.button2:android.R.id.button1);
        tabs.addOnButtonCheckedListener((group,id,checked)->{if(checked){playlists=id==android.R.id.button2;renderRanking();}});
        content.addView(tabs);
        ranking = new LinearLayout(this); ranking.setOrientation(LinearLayout.VERTICAL); content.addView(ranking);
        renderRanking();
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }
    private void metric(LinearLayout parent,String number,String caption) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16),dp(20),dp(12),dp(20)); card.setBackgroundResource(R.drawable.bg_settings_card);
        card.addView(text(number,23,true,false)); TextView label=text(caption,12,false,true); label.setPadding(0,dp(5),0,0); card.addView(label);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1); if(parent.getChildCount()>0)params.setMarginStart(dp(10)); parent.addView(card,params);
    }
    private void renderRanking() {
        if(ranking==null)return; ranking.removeAllViews();
        TextView heading=text(Tr.t(playlists?"Tus playlists más escuchadas":"Tus canciones más escuchadas"),19,true,false); heading.setPadding(dp(2),dp(24),0,dp(14)); ranking.addView(heading);
        List<JSONObject> rows=playlists?lists:songs;
        if(rows.isEmpty()){TextView empty=text(Tr.t(playlists?"Escucha desde una playlist para ver aquí su actividad.":"Reproduce tu primera canción y empieza tu historia musical."),14,false,true);empty.setPadding(dp(8),dp(30),dp(8),dp(30));ranking.addView(empty);return;}
        long maximum=Math.max(1,rows.get(0).optLong("plays"));
        for(int index=0;index<rows.size();index++)addRow(rows.get(index),index,maximum);
    }
    private void addRow(JSONObject data,int index,long maximum) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(14),dp(12),dp(14));row.setBackgroundResource(R.drawable.bg_settings_card);
        TextView rank=text(String.format(java.util.Locale.getDefault(),"%02d",index+1),12,true,true);row.addView(rank,new LinearLayout.LayoutParams(dp(26),-2));
        CardView cover=new CardView(this);cover.setRadius(dp(13));cover.setCardElevation(0);cover.setCardBackgroundColor(color(R.color.bg_elevated));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageResource(R.drawable.ic_music_note);cover.addView(image,new FrameLayout.LayoutParams(-1,-1));row.addView(cover,new LinearLayout.LayoutParams(dp(58),dp(58)));
        String key=data.optString("key"),title=data.optString("title"),artist=data.optString("artist"),thumbnail=data.optString("thumbnail");Song local=library.get(key);
        if(local!=null){title=local.getTitle();artist=local.getArtist();thumbnail=local.getCoverPath();}
        if(playlists){PlaylistManager manager=PlaylistManager.getInstance(this);for(String entry:manager.getSongPaths(title)){cl.arichard.descargador.model.SearchResult online=manager.getOnlineTrack(entry);if(online!=null){thumbnail=online.getThumbnailUrl();break;}for(Song candidate:library.values())if(candidate.getPath().equals(entry)){thumbnail=candidate.getCoverPath();break;}if(!thumbnail.isEmpty())break;}}
        if(thumbnail.isEmpty()&&key.startsWith("yt:"))thumbnail="https://i.ytimg.com/vi/"+key.substring(3)+"/mqdefault.jpg";
        if(!thumbnail.isEmpty())CoverArtLoader.getInstance().loadSongCover(thumbnail,image);
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.setPadding(dp(13),0,0,0);TextView name=text(title,14,true,false);name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(name);
        if(!artist.isEmpty()){TextView byline=text(artist,12,false,true);byline.setSingleLine();byline.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(byline);}
        TextView detail=text(data.optLong("plays")+" "+Tr.t("reproducciones")+"  ·  "+duration(data.optLong("ms")),12,false,true);detail.setPadding(0,dp(5),0,dp(7));words.addView(detail);
        LinearLayout bar=new LinearLayout(this);bar.setBackgroundColor(color(R.color.bg_elevated));View fill=new View(this);fill.setBackgroundColor(color(R.color.accent));float fraction=Math.min(1f,Math.max(.015f,(float)data.optLong("plays")/maximum));bar.addView(fill,new LinearLayout.LayoutParams(0,dp(3),fraction));bar.addView(new View(this),new LinearLayout.LayoutParams(0,dp(3),1-fraction));words.addView(bar,new LinearLayout.LayoutParams(-1,dp(3)));
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(10);ranking.addView(row,params);
    }
}
