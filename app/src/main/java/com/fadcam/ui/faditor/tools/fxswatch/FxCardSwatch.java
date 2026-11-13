package com.fadcam.ui.faditor.tools.fxswatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxStack;

import java.util.function.LongSupplier;

/**
 * The small picture in an effect card's header (STU-6 stage 1): THAT card alone, at its current
 * values and the playhead's moment, over the sample scene. Shown collapsed or open.
 *
 * <p>{@link #request} is cheap and may be called as often as anything changes: at most one
 * drawing is in flight, a new one starts no sooner than {@link #MIN_GAP_MS} after the last (about
 * ten a second while a slider moves), and nothing is drawn when the card's values, and for an
 * effect that moves with time the moment, are what was last drawn. Idle otherwise.</p>
 */
public final class FxCardSwatch {

    /** About ten pictures a second while a slider is moving. */
    static final long MIN_GAP_MS = 100;

    private final ImageView view;
    private final FxStack stack;
    private final FxInstance fx;
    private final LongSupplier playheadMs;
    private final boolean movesWithTime;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable later = this::drawNow;

    @Nullable private String lastKey;
    private boolean inFlight, pending, scheduled;
    private long lastStart;

    private FxCardSwatch(@NonNull ImageView view, @NonNull FxStack stack, @NonNull FxInstance fx,
                         @NonNull LongSupplier playheadMs) {
        this.view = view;
        this.stack = stack;
        this.fx = fx;
        this.playheadMs = playheadMs;
        FxEffectDef def = fx.def();
        this.movesWithTime = def != null && (def.glslBody.contains("FX_TIME")
                || def.capability == FxEffectDef.Capability.FEEDBACK);
    }

    /** A swatch for {@code fx} in {@code stack}, drawing its first picture now. */
    @NonNull
    public static FxCardSwatch make(@NonNull Context ctx, @NonNull FxStack stack,
                                    @NonNull FxInstance fx, @NonNull LongSupplier playheadMs,
                                    float d) {
        ImageView v = new ImageView(ctx);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.SUNK);
        bg.setCornerRadius(4 * d);
        v.setBackground(bg);
        v.setClipToOutline(true);
        // 44 x 25dp: the header's 44dp row, 16:9, as drawn on the Note 9.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(44 * d), Math.round(25 * d));
        lp.setMarginStart(Math.round(2 * d));
        lp.setMarginEnd(Math.round(2 * d));
        v.setLayoutParams(lp);
        v.setAlpha(fx.enabled ? 1f : 0.4f);
        FxEffectDef def = fx.def();
        if (def != null) {
            v.setContentDescription(ctx.getString(com.fadcam.R.string.faditor_fx_swatch_card,
                    def.displayName));
        }
        FxCardSwatch s = new FxCardSwatch(v, stack, fx, playheadMs);
        s.request();
        return s;
    }

    @NonNull
    public ImageView view() {
        return view;
    }

    /** Something may have changed: redraw if it did, no faster than {@link #MIN_GAP_MS}. */
    public void request() {
        if (inFlight) {
            pending = true;
            return;
        }
        long wait = lastStart + MIN_GAP_MS - SystemClock.uptimeMillis();
        if (wait > 0) {
            if (!scheduled) {
                scheduled = true;
                main.postDelayed(later, wait);
            }
            return;
        }
        drawNow();
    }

    private void drawNow() {
        scheduled = false;
        long ms = playheadMs.getAsLong();
        FxStack one = alone(ms);
        if (one == null) return;
        String key = one.cards().get(0).toJson().toString() + (movesWithTime ? "@" + ms : "");
        if (key.equals(lastKey)) return;
        lastKey = key;
        Bitmap ready = FxSwatches.get(view.getContext()).cardIfReady(key);
        if (ready != null) {
            view.setImageBitmap(ready);
            return;
        }
        inFlight = true;
        lastStart = SystemClock.uptimeMillis();
        FxSwatches.get(view.getContext()).card(one, ms / 1000f, key, b -> {
            inFlight = false;
            if (b != null) view.setImageBitmap(b);
            if (pending) {
                pending = false;
                request();
            }
        });
    }

    /**
     * This card on its own, resolved at {@code ms} (keyframes applied), switched on so a
     * bypassed card still shows what it would do (the picture is dimmed instead).
     */
    @Nullable
    private FxStack alone(long ms) {
        FxStack resolved = stack.resolveAt(ms);
        FxInstance at = null;
        for (FxInstance c : resolved.cards()) {
            if (c.slot == fx.slot) {
                at = c;
                break;
            }
        }
        if (at == null || at.def() == null) return null;
        FxInstance copy = at.copy();
        copy.enabled = true;
        copy.collapsed = false;
        FxStack one = new FxStack();
        one.cards().add(copy);
        return one;
    }
}
