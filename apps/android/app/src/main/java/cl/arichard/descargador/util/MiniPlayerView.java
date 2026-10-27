package cl.arichard.descargador.util;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import cl.arichard.descargador.NowPlayingActivity;
import cl.arichard.descargador.R;
import cl.arichard.descargador.model.Song;
import cl.arichard.descargador.player.PlayerManager;
import cl.arichard.descargador.player.QueueSheet;

public final class MiniPlayerView extends SwipeActionsLayout implements PlayerManager.PlaybackListener {
    private final Activity activity;
    private final PlayerManager player = PlayerManager.getInstance();
    private boolean observing;
    private String coverPath;

    public MiniPlayerView(Activity activity) {
        super(activity, null);
        this.activity = activity;
        setId(R.id.miniPlayerLayout);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_mini_player);
        LayoutInflater.from(activity).inflate(R.layout.view_mini_player, this, true);
        setVisibility(GONE);
        Runnable expand = () -> {
            if (player.getCurrentSong() != null) activity.startActivity(new Intent(activity, NowPlayingActivity.class));
        };
        setOnClickListener(view -> expand.run());
        setSwipeActions(player::playNext, player::playPrevious, expand);
        findViewById(R.id.btnMiniPlayPause).setOnClickListener(view -> player.togglePlayPause());
        findViewById(R.id.btnMiniQueue).setOnClickListener(view -> QueueSheet.show(activity));
        findViewById(R.id.btnMiniNext).setOnClickListener(view -> player.playNext());
        findViewById(R.id.btnMiniQueue).setContentDescription(Tr.t("Abrir cola"));
        findViewById(R.id.btnMiniNext).setContentDescription(Tr.t("Siguiente"));
    }

    public void start() {
        observing = true;
        player.addListener(this);
        render();
    }

    public void stop() {
        observing = false;
        player.removeListener(this);
        ((LoadingRingView) findViewById(R.id.miniLoadingRing)).setActive(false);
    }

    private void render() {
        if (!observing) return;
        Song song = player.getCurrentSong();
        setVisibility(song == null ? GONE : VISIBLE);
        if (song == null) return;
        ((TextView) findViewById(R.id.txtMiniTitle)).setText(song.getTitle());
        ((TextView) findViewById(R.id.txtMiniArtist)).setText(song.getArtist());
        if (!song.getCoverPath().equals(coverPath)) {
            coverPath = song.getCoverPath();
            CoverArtLoader.getInstance().loadSongCover(coverPath, findViewById(R.id.imgMiniCover));
        }
        ImageView play = findViewById(R.id.btnMiniPlayPause);
        play.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        play.setContentDescription(Tr.t(player.isPlaying() ? "Pausar" : "Reproducir"));
        play.setAlpha(player.isLoading() ? 0f : 1f);
        ((LoadingRingView) findViewById(R.id.miniLoadingRing)).setActive(player.isLoading());
        renderProgress(player.getCurrentPosition(), player.getDuration());
    }

    private void renderProgress(int position, int duration) {
        if (!observing) return;
        ProgressBar progress = findViewById(R.id.miniProgress);
        progress.setMax(Math.max(1, duration));
        progress.setProgress(position);
    }

    @Override public void onTrackChanged(Song song) { post(this::render); }
    @Override public void onPlaybackStateChanged(boolean playing) { post(this::render); }
    @Override public void onPlaybackLoading(boolean loading) { post(this::render); }
    @Override public void onProgressUpdate(int position, int duration) { post(() -> renderProgress(position, duration)); }
}
