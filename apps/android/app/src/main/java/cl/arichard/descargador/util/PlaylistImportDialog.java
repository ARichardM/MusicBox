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

    public static void showEntry(Activity activity) {
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, 0, pad, 0);
        TextView help = new TextView(activity);
        help.setText(Tr.t("Elige una plataforma. Copia el enlace de una playlist pública desde su menú Compartir."));
        help.setTextSize(14);
        content.addView(help);
        EditText linkField = new EditText(activity);
        linkField.setSingleLine(true);
        linkField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        linkField.setHint(Tr.t("Enlace de la playlist"));
        String[] platforms = {"Spotify", "YouTube", "Deezer", "Apple Music", "SoundCloud"};
        int[] icons = {cl.arichard.descargador.R.drawable.ic_platform_spotify, cl.arichard.descargador.R.drawable.ic_platform_youtube,
                cl.arichard.descargador.R.drawable.ic_platform_deezer, cl.arichard.descargador.R.drawable.ic_platform_applemusic,
                cl.arichard.descargador.R.drawable.ic_platform_soundcloud};
        for (int i = 0; i < platforms.length; i++) {
            String platform = platforms[i];
            com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(activity,
                    null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(platform);
            button.setIconResource(icons[i]);
            button.setIconTint(null);
            button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            button.setOnClickListener(v -> {
                help.setText(Tr.t("En {platform}, abre una playlist pública y elige Compartir → Copiar enlace.").replace("{platform}", platform));
                linkField.requestFocus();
            });
            content.addView(button, new LinearLayout.LayoutParams(-1, -2));
        }
        content.addView(linkField);
        TextView detail = new TextView(activity);
        detail.setText(Tr.t("Pega el enlace aquí. Podrás revisar la lista antes de importarla."));
        detail.setTextSize(12);
        content.addView(detail);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(Tr.t("Importar playlists"))
                .setView(scroll).setNegativeButton(Tr.t("Cancelar"), null)
                .setPositiveButton(Tr.t("Leer playlist"), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            PlaylistImport.Link link = PlaylistImport.find(linkField.getText().toString().trim());
            if (link == null) { linkField.setError(Tr.t("Pega un enlace de playlist compatible")); return; }
            dialog.dismiss();
            start(activity, link);
        }));
        dialog.show();
    }

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
        content.addView(status);
        content.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(Tr.t("Importar playlist"))
                .setView(content)
                .setCancelable(false)
                .setNegativeButton(Tr.t("Cancelar"), null)
                .create();
        dialog.show();
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
                dialog.dismiss();
                if (activity.isFinishing() || activity.isDestroyed()) return;
                PlaylistImportReviewSheet.show(activity, playlist);
            });
        });
    }

    private static void showError(AlertDialog dialog, TextView status, ProgressBar bar, Exception error) {
        bar.setVisibility(View.GONE);
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        status.setText(Tr.t("No se pudo importar la playlist. ") + detail);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(Tr.t("Cerrar"));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
    }
}
