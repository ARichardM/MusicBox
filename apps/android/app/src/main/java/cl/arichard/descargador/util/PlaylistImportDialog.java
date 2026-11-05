package cl.arichard.descargador.util;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import cl.arichard.descargador.DescargadorApp;
import cl.arichard.descargador.data.PlaylistManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Diálogo que lee una playlist de un enlace, deja elegir su nombre y la importa con progreso. */
public final class PlaylistImportDialog {
    private PlaylistImportDialog() {}

    public static void start(Activity activity, PlaylistImport.Link link) {
        if (activity == null || activity.isFinishing() || link == null) return;
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, 0);
        TextView status = new TextView(activity);
        status.setTextSize(14f);
        status.setText(Tr.f("Leyendo la playlist de %s…", link.platform.label));
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setVisibility(View.GONE);
        name.setHint(Tr.t("Nombre de la playlist"));
        content.addView(status);
        content.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        content.addView(name, new LinearLayout.LayoutParams(-1, -2));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(Tr.t("Importar playlist"))
                .setView(content)
                .setCancelable(false)
                .setNegativeButton(Tr.t("Cancelar"), null)
                .setPositiveButton(Tr.t("Importar"), null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { cancelled.set(true); dialog.dismiss(); });

        DescargadorApp.IO.execute(() -> {
            PlaylistImport.Playlist playlist;
            try {
                playlist = PlaylistImport.fetch(link);
            } catch (Exception error) {
                activity.runOnUiThread(() -> { if (!cancelled.get() && dialog.isShowing()) showError(dialog, status, bar, error); });
                return;
            }
            activity.runOnUiThread(() -> {
                if (cancelled.get() || !dialog.isShowing()) return;
                showReady(activity, dialog, status, bar, name, playlist, cancelled);
            });
        });
    }

    private static void showReady(Activity activity, AlertDialog dialog, TextView status, ProgressBar bar, EditText name,
                                  PlaylistImport.Playlist playlist, AtomicBoolean cancelled) {
        int count = playlist.tracks.size();
        status.setText(Tr.f("%d canciones de %s", count, playlist.platform.label)
                + (playlist.platform == PlaylistImport.Platform.YOUTUBE ? "" : Tr.t(" · se buscan en YouTube y solo entran las coincidencias seguras")));
        bar.setVisibility(View.GONE);
        name.setVisibility(View.VISIBLE);
        name.setText(playlist.name);
        name.setSelection(name.length());
        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setVisibility(View.VISIBLE);
        positive.setOnClickListener(v -> {
            String target = name.getText().toString().trim();
            if (target.isEmpty()) { name.setError(Tr.t("Escribe un nombre")); return; }
            startImport(activity, dialog, status, bar, name, playlist, target, cancelled);
        });
    }

    private static void startImport(Activity activity, AlertDialog dialog, TextView status, ProgressBar bar, EditText name,
                                    PlaylistImport.Playlist playlist, String target, AtomicBoolean cancelled) {
        int total = playlist.tracks.size();
        name.setVisibility(View.GONE);
        bar.setIndeterminate(false);
        bar.setMax(Math.max(1, total));
        bar.setProgress(0);
        bar.setVisibility(View.VISIBLE);
        status.setText(Tr.f("Buscando canciones… 0 de %d", total));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(Tr.t("Detener"));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled.set(true);
            status.setText(Tr.t("Deteniendo…"));
        });
        PlaylistManager playlists = PlaylistManager.getInstance(activity);
        DescargadorApp.IO.execute(() -> {
            try {
                PlaylistImport.Outcome outcome = PlaylistImport.importInto(playlists, playlist, target,
                        (done, all) -> activity.runOnUiThread(() -> {
                            bar.setProgress(done);
                            status.setText(Tr.f("Buscando canciones… %d de %d", done, all));
                        }), cancelled::get);
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    String message = cancelled.get()
                            ? Tr.f("Importación detenida: %d canciones agregadas a «%s»", outcome.added, outcome.playlist)
                            : Tr.f("Playlist «%s» creada con %d canciones", outcome.playlist, outcome.added);
                    if (outcome.skipped > 0) message += Tr.f(" · %d sin coincidencia segura", outcome.skipped);
                    AppNotice.info(activity.findViewById(android.R.id.content), message);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> { if (dialog.isShowing()) showError(dialog, status, bar, error); });
            }
        });
    }

    private static void showError(AlertDialog dialog, TextView status, ProgressBar bar, Exception error) {
        bar.setVisibility(View.GONE);
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        status.setText(Tr.t("No se pudo importar la playlist. ") + detail);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(Tr.t("Cerrar"));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
    }
}
