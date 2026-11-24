package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.fx.FxDeviceGuard;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxParam;
import com.fadcam.ui.faditor.fx.FxStack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STU-6 step 1, the views: a picker tile (swatch over the effect's name) and the small live
 * swatch in a stack card's header. The drawing is {@link FxSwatchRenderer}; this class only
 * puts the result on screen without ever blocking the UI, and without ever showing an error:
 * where a picture cannot be made, the tile is a flat raised square with the name, as before.
 */
final class FxSwatchViews {

    private FxSwatchViews() {}

    /** One GL worker: swatches are drawn one at a time, in the order they were asked for. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fx-swatch");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Pixels drawn for a picker tile and for a card header (dp sizes below, x2 for sharpness). */
    private static final int TILE_PX = 192;
    private static final int HEAD_PX = 96;

    // ── picker tile ─────────────────────────────────────────────────────────

    /**
     * A grid tile: the effect's picture over its name. {@code available} false draws it faded
     * (cannot render on this subject) while keeping it legible and tappable for the explanation.
     */
    @NonNull
    static View tile(@NonNull Context ctx, @NonNull FxEffectDef def, float d, boolean available) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(4 * d);
        col.setPadding(pad, pad, pad, pad);

        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Studio.RAISED);
        int side = Math.round(76 * d);
        col.addView(img, new LinearLayout.LayoutParams(side, side));
        TextView name = new TextView(ctx);
        name.setText(def.displayName);
        name.setTextColor(available ? Studio.INK : Studio.INK_FAINT);
        name.setTextSize(11f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                side, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Math.round(3 * d);
        col.addView(name, nlp);
        // A shader this phone's driver refused: say so on the tile, now or once the swatch fails.
        final Runnable markUnavailable = () -> {
            name.setText(com.fadcam.R.string.faditor_fx_unavailable_short);
            name.setTextColor(Studio.INK_FAINT);
            img.setAlpha(0.4f);
        };
        if (FxDeviceGuard.isUnavailable(def.id)) markUnavailable.run();
        else load(img, def, TILE_PX, () -> {
            if (FxDeviceGuard.isUnavailable(def.id)) markUnavailable.run();
        });

        if (!available) img.setAlpha(0.4f);
        return col;
    }

    // ── card header swatch ──────────────────────────────────────────────────

    /** The header swatch of one stack card, re-drawn (throttled) when its values change. */
    static final class CardSwatch extends ImageView {
        private final FxInstance card;
        private long lastAskMs;
        private boolean pending;
        private int ticket;

        CardSwatch(@NonNull Context ctx, @NonNull FxInstance card) {
            super(ctx);
            this.card = card;
            setScaleType(ScaleType.CENTER_CROP);
            setBackgroundColor(Studio.RAISED);
            refresh();
        }

        /** Ask for a fresh picture: at most about ten a second, never on the main thread. */
        void refresh() {
            final long now = android.os.SystemClock.uptimeMillis();
            if (now - lastAskMs < 100) {
                if (!pending) {
                    pending = true;
                    MAIN.postDelayed(() -> { pending = false; refresh(); }, 100);
                }
                return;
            }
            lastAskMs = now;
            final int mine = ++ticket;
            final FxInstance snap = card.copy();
            WORKER.execute(() -> {
                final Bitmap b = FxSwatchRenderer.renderCard(snap, HEAD_PX);
                if (b == null) return;
                MAIN.post(() -> { if (mine == ticket) setImageBitmap(b); });
            });
        }
    }

    /**
     * Wraps the panel's host so any value change on a card also refreshes that card's swatch,
     * without touching the dozens of slider listeners that already call {@code onFxChanged}.
     */
    @NonNull
    static FxPanel.Host watching(@NonNull FxPanel.Host real, @NonNull CardSwatch swatch) {
        return new FxPanel.Host() {
            @Override public void onFxChanged() { real.onFxChanged(); swatch.refresh(); }
            @Override public void recordUndo(@NonNull String label, @NonNull Runnable redo,
                                             @NonNull Runnable undo) {
                real.recordUndo(label, redo, undo);
            }
            @Override public long playheadMs() { return real.playheadMs(); }
            @Override public void seekTo(long ms) { real.seekTo(ms); }
            @Override public String previewCaveat() { return real.previewCaveat(); }
            @Override public void editGradientInPreview(@Nullable FxStack stack,
                    @Nullable FxInstance c, @Nullable FxParam param) {
                real.editGradientInPreview(stack, c, param);
            }
            @Override public boolean isEditingGradientInPreview(@Nullable FxInstance c,
                    @Nullable FxParam param) {
                return real.isEditingGradientInPreview(c, param);
            }
        };
    }

    // ── loading ─────────────────────────────────────────────────────────────

    private static void load(@NonNull ImageView img, @NonNull FxEffectDef def, int px,
                             @NonNull Runnable whenNoPicture) {
        Bitmap hit = FxSwatchRenderer.cached(def, px);
        if (hit != null) { img.setImageBitmap(hit); return; }
        WORKER.execute(() -> {
            final Bitmap b = FxSwatchRenderer.renderDefault(def, px);
            if (b == null) { MAIN.post(whenNoPicture); return; }
            MAIN.post(() -> { img.setImageBitmap(b); });
        });
    }
}
