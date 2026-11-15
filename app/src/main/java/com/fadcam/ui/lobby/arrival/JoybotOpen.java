package com.fadcam.ui.lobby.arrival;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Opens the Joybot chat from a button so he can fly out of it.
 *
 * <p>Two things go with the open: where the button is ({@link Intent#setSourceBounds}), which is where
 * the drawer opens from and where his flight starts; and a quick, half-size picture of the screen as
 * it was, which his heat haze bends (the chat cannot see the screen under it any other way). The
 * picture is taken by {@link PixelCopy}, so it costs the GPU a copy and the main thread nothing; if it
 * is slow or refused the chat opens anyway, just without the haze.</p>
 */
public final class JoybotOpen {

    private JoybotOpen() {}

    /** Longest the open waits for the picture. */
    private static final long WAIT_MS = 120L;
    /** A picture older than this is of some other moment. */
    private static final long FRESH_MS = 4000L;

    @Nullable private static Bitmap back;
    private static final Rect backOnScreen = new Rect();
    private static long backAt;

    /** Open the chat from {@code button} (null: from where his button usually is, top right). */
    public static void start(@NonNull Activity from, @Nullable View button, @NonNull Intent intent) {
        if (button != null && button.isAttachedToWindow() && button.getWidth() > 0) {
            int[] at = new int[2];
            button.getLocationOnScreen(at);
            intent.setSourceBounds(new Rect(at[0], at[1], at[0] + button.getWidth(), at[1] + button.getHeight()));
        }
        Window w = from.getWindow();
        View decor = w != null ? w.getDecorView() : null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || decor == null || decor.getWidth() < 2 || !decor.isAttachedToWindow()) {
            from.startActivity(intent);
            return;
        }
        final boolean[] started = {false};
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable go = () -> {
            if (started[0]) return;
            started[0] = true;
            if (!from.isFinishing() && !from.isDestroyed()) from.startActivity(intent);
        };
        try {
            final Bitmap pic = Bitmap.createBitmap(Math.max(1, decor.getWidth() / 2), Math.max(1, decor.getHeight() / 2),
                    Bitmap.Config.ARGB_8888);
            final int[] at = new int[2];
            decor.getLocationOnScreen(at);
            PixelCopy.request(w, pic, result -> {
                if (result == PixelCopy.SUCCESS && !started[0]) {
                    synchronized (JoybotOpen.class) {
                        back = pic;
                        backOnScreen.set(at[0], at[1], at[0] + decor.getWidth(), at[1] + decor.getHeight());
                        backAt = android.os.SystemClock.uptimeMillis();
                    }
                }
                go.run();
            }, main);
            main.postDelayed(go, WAIT_MS);
        } catch (RuntimeException | OutOfMemoryError e) {
            go.run();
        }
    }

    /**
     * The picture of the screen the chat just opened over, once: null when there is none or it is stale.
     * {@code onScreen} receives where it sits on the screen, in pixels.
     */
    @Nullable
    static synchronized Bitmap takeBackdrop(@NonNull Rect onScreen) {
        Bitmap b = back;
        back = null;
        if (b == null || android.os.SystemClock.uptimeMillis() - backAt > FRESH_MS) return null;
        onScreen.set(backOnScreen);
        return b;
    }
}
