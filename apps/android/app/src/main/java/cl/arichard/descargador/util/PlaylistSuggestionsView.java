package cl.arichard.descargador.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cl.arichard.descargador.R;
import cl.arichard.descargador.model.SearchResult;

/**
 * Tarjeta compacta con un carrusel de canciones sugeridas para la playlist abierta: una por vez, con la
 * siguiente asomando, indicador de puntos, paso automático, botón + y plegado animado.
 */
public class PlaylistSuggestionsView extends LinearLayout {
    public interface Listener {
        void onPlay(SearchResult song);
        void onAdd(SearchResult song);
        void onRefresh();
        void onCollapsedChanged(boolean collapsed);
    }

    private static final long AUTO_MS = 6000;
    private static final int PEEK_DP = 26;

    private final List<SearchResult> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Adapter adapter = new Adapter();
    private final int headerHeight = dp(38);
    private final int bodyHeight = dp(68);

    private final ImageView sparkle;
    private final DotsView dots;
    private final ImageView btnRefresh;
    private final ImageView btnChevron;
    private final FrameLayout body;
    private final RecyclerView recycler;
    private final View shimmer;
    private Listener listener;
    private boolean collapsed;
    private boolean loading;
    private boolean entrance;
    private boolean touching;
    private float scrollX;
    private ObjectAnimator spin;
    private ValueAnimator pulse;
    private ValueAnimator bodyAnimator;

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            if (canAutoAdvance()) {
                int next = (currentPage() + 1) % items.size();
                recycler.smoothScrollToPosition(next);
            }
            schedule();
        }
    };

    public PlaylistSuggestionsView(Context context) { this(context, null); }

    public PlaylistSuggestionsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(18));
        background.setColor(ContextCompat.getColor(context, R.color.bg_card));
        background.setStroke(dp(1), ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.border), 120));
        setBackground(background);
        setClipToOutline(true);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), 0, dp(6), 0);
        addView(header, new LayoutParams(-1, headerHeight));

        sparkle = new ImageView(context);
        sparkle.setImageResource(R.drawable.ic_sparkle);
        sparkle.setColorFilter(ContextCompat.getColor(context, R.color.accent));
        sparkle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(sparkle, new LayoutParams(dp(18), dp(18)));

        TextView title = new TextView(context);
        title.setText(Tr.t("Para esta playlist"));
        title.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        title.setTextSize(12f);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        LayoutParams titleLp = new LayoutParams(0, -2, 1f);
        titleLp.setMargins(dp(8), 0, dp(8), 0);
        header.addView(title, titleLp);

        dots = new DotsView(context);
        header.addView(dots, new LayoutParams(-2, dp(10)));

        btnRefresh = iconButton(R.drawable.ic_refresh, Tr.t("Actualizar sugerencias"));
        btnRefresh.setOnClickListener(v -> { if (listener != null) listener.onRefresh(); });
        header.addView(btnRefresh, new LayoutParams(dp(34), dp(34)));
        btnChevron = iconButton(R.drawable.ic_chevron_down, Tr.t("Ocultar sugerencias"));
        btnChevron.setOnClickListener(v -> setCollapsed(!collapsed, true, true));
        header.addView(btnChevron, new LayoutParams(dp(34), dp(34)));

        body = new FrameLayout(context);
        addView(body, new LayoutParams(-1, bodyHeight));

        shimmer = new View(context);
        GradientDrawable ghost = new GradientDrawable();
        ghost.setCornerRadius(dp(14));
        ghost.setColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_secondary), 40));
        shimmer.setBackground(ghost);
        shimmer.setVisibility(GONE);
        FrameLayout.LayoutParams ghostLp = new FrameLayout.LayoutParams(-1, dp(58));
        ghostLp.setMargins(dp(12), 0, dp(12), dp(10));
        body.addView(shimmer, ghostLp);

        recycler = new RecyclerView(context);
        recycler.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        recycler.setClipToPadding(false);
        recycler.setPadding(dp(PEEK_DP), 0, dp(PEEK_DP), dp(10));
        recycler.setOverScrollMode(OVER_SCROLL_NEVER);
        recycler.setAdapter(adapter);
        recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull android.graphics.Rect out, @NonNull View view,
                                                 @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                out.left = dp(4); out.right = dp(4);
            }
        });
        androidx.recyclerview.widget.DefaultItemAnimator animator = new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setRemoveDuration(200); animator.setMoveDuration(260); animator.setAddDuration(260);
        recycler.setItemAnimator(animator);
        new PagerSnapHelper().attachToRecyclerView(recycler);
        // El ancho de cada tarjeta depende del de la lista, que no se conoce mientras la vista está oculta.
        recycler.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol && !items.isEmpty()) recycler.post(() -> adapter.notifyItemRangeChanged(0, items.size()));
        });
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                scrollX += dx;
                dots.setPosition(pageOffset());
            }
        });
        recycler.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            touching = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
            if (action == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
            if (!touching) schedule();
            return false;
        });
        body.addView(recycler, new FrameLayout.LayoutParams(-1, -1));
        setVisibility(GONE);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public boolean hasContent() { return !items.isEmpty() || loading; }

    public boolean hasSuggestions() { return !items.isEmpty(); }

    public boolean isCollapsed() { return collapsed; }

    /** Cambia el estado de plegado; con {@code animate} la altura se anima y el chevron gira. */
    public void setCollapsed(boolean value, boolean animate, boolean notify) {
        if (bodyAnimator != null) bodyAnimator.cancel();
        collapsed = value;
        float rotation = value ? 180f : 0f;
        btnChevron.setContentDescription(Tr.t(value ? "Mostrar sugerencias" : "Ocultar sugerencias"));
        dots.setVisibility(value ? INVISIBLE : VISIBLE);
        int target = value ? 0 : bodyHeight;
        ViewGroup.LayoutParams params = body.getLayoutParams();
        if (!animate) {
            btnChevron.setRotation(rotation);
            params.height = target;
            body.setLayoutParams(params);
            body.setAlpha(value ? 0f : 1f);
        } else {
            btnChevron.animate().rotation(rotation).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
            bodyAnimator = ValueAnimator.ofInt(params.height, target);
            bodyAnimator.setDuration(260);
            bodyAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
            bodyAnimator.addUpdateListener(a -> {
                params.height = (int) a.getAnimatedValue();
                body.setLayoutParams(params);
                body.setAlpha(Math.min(1f, params.height / (float) bodyHeight));
            });
            bodyAnimator.start();
        }
        if (notify && listener != null) listener.onCollapsedChanged(value);
        schedule();
    }

    public void setLoading(boolean value) {
        if (loading == value) return;
        loading = value;
        if (value) {
            spin = ObjectAnimator.ofFloat(btnRefresh, View.ROTATION, 0f, 360f);
            spin.setDuration(900); spin.setRepeatCount(ValueAnimator.INFINITE); spin.setInterpolator(new LinearInterpolator());
            spin.start();
        } else if (spin != null) {
            spin.cancel(); spin = null;
            btnRefresh.setRotation(0f);
        }
        updateShimmer();
    }

    private void updateShimmer() {
        boolean show = loading && items.isEmpty();
        shimmer.setVisibility(show ? VISIBLE : GONE);
        if (show && pulse == null) {
            pulse = ValueAnimator.ofFloat(.45f, 1f);
            pulse.setDuration(800); pulse.setRepeatMode(ValueAnimator.REVERSE); pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> shimmer.setAlpha((float) a.getAnimatedValue()));
            pulse.start();
        } else if (!show && pulse != null) {
            pulse.cancel(); pulse = null;
        }
    }

    /** Reemplaza las sugerencias; entran escalonadas y el destello brilla una vez. */
    public void setSuggestions(List<SearchResult> songs) {
        boolean same = songs.size() == items.size();
        for (int i = 0; same && i < songs.size(); i++) same = songs.get(i).getId().equals(items.get(i).getId());
        if (same) return;
        items.clear();
        items.addAll(songs);
        entrance = true;
        scrollX = 0;
        adapter.notifyDataSetChanged();
        recycler.scrollToPosition(0);
        dots.setCount(items.size());
        dots.setPosition(0f);
        updateShimmer();
        if (!songs.isEmpty()) glow();
        schedule();
    }

    private void glow() {
        sparkle.animate().cancel();
        sparkle.animate().scaleX(1.3f).scaleY(1.3f).alpha(.65f).setDuration(220)
                .withEndAction(() -> sparkle.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                        .setInterpolator(new OvershootInterpolator(2f)).start()).start();
    }

    /** Muestra u oculta la tarjeta con un pequeño fundido y desplazamiento. */
    public void setShown(boolean show) {
        if (show && getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setAlpha(0f); setTranslationY(-dp(6));
            animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
        } else if (!show && getVisibility() == VISIBLE) {
            animate().cancel();
            setAlpha(1f); setTranslationY(0f);
            setVisibility(GONE);
        }
        schedule();
    }

    private void remove(SearchResult song) {
        int index = items.indexOf(song);
        if (index < 0) return;
        items.remove(index);
        adapter.notifyItemRemoved(index);
        dots.setCount(items.size());
        if (items.isEmpty()) {
            if (!loading) setShown(false);
            return;
        }
        schedule();
    }

    private int pageWidth() {
        int width = recycler.getWidth() - recycler.getPaddingLeft() - recycler.getPaddingRight();
        return Math.max(dp(200), width);
    }

    private float pageOffset() {
        float stride = pageWidth() + dp(8);
        return stride <= 0 ? 0 : Math.max(0f, Math.min(items.size() - 1f, scrollX / stride));
    }

    private int currentPage() { return Math.round(pageOffset()); }

    private boolean canAutoAdvance() {
        return !collapsed && !touching && items.size() > 1 && isShown()
                && recycler.getScrollState() == RecyclerView.SCROLL_STATE_IDLE;
    }

    private void schedule() {
        handler.removeCallbacks(advance);
        if (items.size() > 1 && !collapsed && getVisibility() == VISIBLE && isAttachedToWindow())
            handler.postDelayed(advance, AUTO_MS);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(advance);
        if (spin != null) spin.cancel();
        if (pulse != null) pulse.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (loading && spin != null) spin.start();
        updateShimmer();
        schedule();
    }

    private ImageView iconButton(int icon, String description) {
        ImageView view = new ImageView(getContext());
        view.setImageResource(icon);
        view.setColorFilter(ContextCompat.getColor(getContext(), R.color.text_secondary));
        view.setPadding(dp(9), dp(9), dp(9), dp(9));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        view.setContentDescription(description);
        androidx.core.view.ViewCompat.setTooltipText(view, description);
        return view;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(new Slide(parent.getContext()));
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.slide.bind(items.get(position));
            ViewGroup.LayoutParams params = holder.slide.getLayoutParams();
            params.width = pageWidth();
            holder.slide.setLayoutParams(params);
            if (entrance && position < 5) {
                holder.slide.setAlpha(0f); holder.slide.setScaleX(.94f); holder.slide.setScaleY(.94f);
                holder.slide.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(position * 55L)
                        .setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
            }
            if (position == Math.min(items.size(), 5) - 1) entrance = false;
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final Slide slide;
        Holder(Slide slide) { super(slide); this.slide = slide; }
    }

    /** Una diapositiva: portada, título, artista y botón +. */
    private final class Slide extends LinearLayout {
        private final ImageView cover;
        private final TextView title;
        private final TextView subtitle;
        private final FrameLayout add;
        private final ImageView addIcon;
        private final GradientDrawable addBackground = new GradientDrawable();
        private SearchResult song;
        private boolean added;

        Slide(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(8), dp(8), dp(8));
            setLayoutParams(new RecyclerView.LayoutParams(dp(260), dp(58)));
            GradientDrawable card = new GradientDrawable();
            card.setCornerRadius(dp(14));
            card.setColor(ContextCompat.getColor(context, R.color.bg_primary));
            setBackground(card);
            setClickable(true); setFocusable(true);
            SafeTap.installRowTap(this);

            CardView frame = new CardView(context);
            frame.setRadius(dp(10)); frame.setCardElevation(0);
            frame.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_elevated));
            cover = new ImageView(context);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            addView(frame, new LayoutParams(dp(42), dp(42)));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            LayoutParams textsLp = new LayoutParams(0, -2, 1f);
            textsLp.setMargins(dp(10), 0, dp(8), 0);
            addView(texts, textsLp);
            title = new TextView(context);
            title.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            title.setTextSize(13f); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            texts.addView(title, new LayoutParams(-1, -2));
            subtitle = new TextView(context);
            subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            subtitle.setTextSize(11f); subtitle.setSingleLine(true); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(subtitle, new LayoutParams(-1, -2));

            add = new FrameLayout(context);
            addBackground.setShape(GradientDrawable.OVAL);
            add.setBackground(addBackground);
            addIcon = new ImageView(context);
            addIcon.setPadding(dp(8), dp(8), dp(8), dp(8));
            add.addView(addIcon, new FrameLayout.LayoutParams(-1, -1));
            addView(add, new LayoutParams(dp(34), dp(34)));
            add.setOnClickListener(v -> confirmAdd());
            setOnClickListener(v -> { if (listener != null && song != null) listener.onPlay(song); });
        }

        void bind(SearchResult result) {
            song = result;
            added = false;
            title.setText(result.getTitle());
            String artist = cl.arichard.descargador.data.RecommendationEngine.artistName(result);
            long seconds = result.getDurationSeconds();
            subtitle.setText(seconds > 0 ? artist + " · " + String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60) : artist);
            cover.setImageDrawable(null);
            ImageLoader.getInstance().load(result.getThumbnailUrl(), cover);
            paintAdd(false);
            add.setContentDescription(Tr.t("Agregar a la playlist"));
        }

        private void paintAdd(boolean done) {
            int accent = ContextCompat.getColor(getContext(), R.color.accent);
            addBackground.setColor(done ? ContextCompat.getColor(getContext(), R.color.success) : ColorUtils.setAlphaComponent(accent, 36));
            addIcon.setImageResource(done ? R.drawable.ic_check : R.drawable.ic_add);
            addIcon.setColorFilter(done ? Color.WHITE : accent);
        }

        private void confirmAdd() {
            if (added || song == null) return;
            added = true;
            paintAdd(true);
            addIcon.setScaleX(.6f); addIcon.setScaleY(.6f);
            addIcon.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
            final SearchResult target = song;
            postDelayed(() -> {
                if (listener != null) listener.onAdd(target);
                remove(target);
            }, 340);
        }
    }

    /** Hasta cinco puntos; el activo se estira siguiendo el desplazamiento. */
    private static final class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int count;
        private float position;

        DotsView(Context context) { super(context); }

        void setCount(int value) {
            count = value;
            setVisibility(value > 1 ? VISIBLE : GONE);
            requestLayout(); invalidate();
        }

        void setPosition(float value) { position = value; invalidate(); }

        private float density() { return getResources().getDisplayMetrics().density; }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            float d = density();
            int total = Math.min(count, 5);
            setMeasuredDimension(Math.round((total * 5 + Math.max(0, total - 1) * 4 + 11) * d), Math.round(10 * d));
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            int total = Math.min(count, 5);
            if (total < 2) return;
            float d = density();
            float active = count <= 5 ? position : position * (total - 1) / Math.max(1, count - 1);
            int on = ContextCompat.getColor(getContext(), R.color.accent);
            int off = ContextCompat.getColor(getContext(), R.color.border);
            float x = 0;
            float cy = getHeight() / 2f;
            for (int i = 0; i < total; i++) {
                float t = Math.max(0f, 1f - Math.abs(i - active));
                float width = (5 + 11 * t) * d;
                paint.setColor(ColorUtils.blendARGB(off, on, t));
                rect.set(x, cy - 2.5f * d, x + width, cy + 2.5f * d);
                canvas.drawRoundRect(rect, 2.5f * d, 2.5f * d, paint);
                x += width + 4 * d;
            }
        }
    }
}
