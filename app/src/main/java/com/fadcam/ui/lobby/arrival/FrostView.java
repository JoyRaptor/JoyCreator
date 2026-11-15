package com.fadcam.ui.lobby.arrival;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Frost for the Joybot chat: the screen it opened over, blurred, drawn behind the drawer's see-through
 * fill and kept lined up with the real screen as the drawer drops in or the card is moved.
 *
 * <p>Owner: drawers are never opaque; Frost only adds blur. Most phones (the owner's Samsungs among
 * them) do not offer Android's own window blur, so, like Studio's drawers ({@code ObjectDrawer}
 * captureFrost), this is a picture blurred on the CPU, here once per open: the picture
 * {@link JoybotOpen} took as the chat opened, shrunk and box-blurred. What changes underneath while the
 * chat is open is not followed (the blur hides most of it).</p>
 */
final class FrostView extends View {

    @Nullable private Bitmap blurred;
    /** Where the picture sits on the screen, px. */
    private final Rect onScreen = new Rect();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final int[] at = new int[2];

    FrostView(@NonNull Context c) {
        super(c);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    boolean hasPicture() { return blurred != null; }

    private static final java.util.concurrent.ExecutorService WORK = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** When the blurred picture arrived: it fades in over {@link #FADE_MS}. */
    private long shownAt;
    private static final long FADE_MS = 220L;

    /**
     * Blur {@code screen} (a picture of the screen at {@code where}, px) off the main thread, once, and fade
     * it in: the main thread pays nothing (owner: extras stay cheap, no frame-rate cost).
     */
    void setPicture(@NonNull Bitmap screen, @NonNull Rect where) {
        final Rect at0 = new Rect(where);
        WORK.execute(() -> {
            long t0 = android.os.SystemClock.elapsedRealtimeNanos();
            Bitmap out = null;
            int w = Math.max(8, screen.getWidth() / 4), h = Math.max(8, screen.getHeight() / 4);
            try {
                Bitmap small = Bitmap.createScaledBitmap(screen, w, h, true);
                Bitmap b = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
                int[] px = new int[w * h], tmp = new int[w * h];
                b.getPixels(px, 0, w, 0, 0, w, h);
                boxBlur(px, tmp, w, h, 3);
                boxBlur(px, tmp, w, h, 3);
                b.setPixels(px, 0, w, 0, 0, w, h);
                out = b;
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // no frost this time; never an error
            }
            android.util.Log.i("ChatArrival", "frost blur " + (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 100_000 / 10f
                    + " ms for " + w + "x" + h + " (off the main thread)");
            final Bitmap done = out;
            post(() -> {
                if (done == null) return;
                onScreen.set(at0);
                blurred = done;
                shownAt = android.os.SystemClock.uptimeMillis();
                invalidate();
            });
        });
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        Bitmap b = blurred;
        if (b == null) return;
        getLocationOnScreen(at);
        dst.set(onScreen.left - at[0], onScreen.top - at[1], onScreen.right - at[0], onScreen.bottom - at[1]);
        float k = Math.min(1f, (android.os.SystemClock.uptimeMillis() - shownAt) / (float) FADE_MS);
        paint.setAlpha(Math.round(255 * k));
        c.drawBitmap(b, null, dst, paint);
        if (k < 1f) postInvalidateOnAnimation();
    }

    /** One horizontal then one vertical box pass of radius {@code r}, in place (as ObjectDrawer's). */
    private static void boxBlur(@NonNull int[] px, @NonNull int[] tmp, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int ra = 0, ga = 0, ba = 0, row = y * w;
            for (int i = -r; i <= r; i++) {
                int p = px[row + Math.max(0, Math.min(w - 1, i))];
                ra += (p >> 16) & 255; ga += (p >> 8) & 255; ba += p & 255;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = 0xFF000000 | ((ra / div) << 16) | ((ga / div) << 8) | (ba / div);
                int out = px[row + Math.max(0, x - r)], in = px[row + Math.min(w - 1, x + r + 1)];
                ra += ((in >> 16) & 255) - ((out >> 16) & 255);
                ga += ((in >> 8) & 255) - ((out >> 8) & 255);
                ba += (in & 255) - (out & 255);
            }
        }
        for (int x = 0; x < w; x++) {
            int ra = 0, ga = 0, ba = 0;
            for (int i = -r; i <= r; i++) {
                int p = tmp[Math.max(0, Math.min(h - 1, i)) * w + x];
                ra += (p >> 16) & 255; ga += (p >> 8) & 255; ba += p & 255;
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = 0xFF000000 | ((ra / div) << 16) | ((ga / div) << 8) | (ba / div);
                int out = tmp[Math.max(0, y - r) * w + x], in = tmp[Math.min(h - 1, y + r + 1) * w + x];
                ra += ((in >> 16) & 255) - ((out >> 16) & 255);
                ga += ((in >> 8) & 255) - ((out >> 8) & 255);
                ba += (in & 255) - (out & 255);
            }
        }
    }
}
