package cc.joycreator.handoff;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * FX-1: Joybot carries your work from one room to the next. The switch, the choice of move, and a
 * picture of a room.
 *
 * <p>The owner, 15 Nov: "We've built up this huge library of effects. Why don't we start using it on
 * our own app?" Sending work between rooms is a moment people should remember, and Joybot is the
 * one who carries it. The show only decorates: the file is saved and placed before it starts, a tap
 * skips it, and Settings can turn it off (Joybot still animates everywhere else, by his own rule).</p>
 */
public final class Handoff {

    private Handoff() {}

    /** Shared with JoyBrush's side ({@code RoomShot}), which cannot see this class. */
    public static final String PREFS = "handoff", KEY_SHOW = "show";
    private static final String KEY_LAST = "last_move";
    /** Room pictures are kept under this many pixels on their long side: sharp enough to warp, light on memory. */
    static final int PICTURE_LONG_SIDE = 2048;

    private static SharedPreferences p(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** "Hand-off animation" in Settings, on unless switched off. Needs Android 8 for the room pictures. */
    public static boolean enabled(@NonNull Context c) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && p(c).getBoolean(KEY_SHOW, true);
    }

    public static void setEnabled(@NonNull Context c, boolean on) {
        p(c).edit().putBoolean(KEY_SHOW, on).apply();
    }

    /** A different move every time, in turn (the owner wants several, as with his arrival paths). */
    static int nextMove(@NonNull Context c) {
        int m = (p(c).getInt(KEY_LAST, -1) + 1) % HandoffMoves.MODES;
        p(c).edit().putInt(KEY_LAST, m).apply();
        return m;
    }

    interface Pictured { void onPicture(@Nullable Bitmap picture); }

    /**
     * Picture a window as it is on screen (not the show's own layer, which is a separate surface).
     * {@code then} runs on the main thread, with null when the phone could not do it.
     */
    static void picture(@NonNull Activity a, @NonNull Pictured then) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { then.onPicture(null); return; }
        try {
            Window w = a.getWindow();
            View decor = w.getDecorView();
            int vw = decor.getWidth(), vh = decor.getHeight();
            if (vw <= 0 || vh <= 0 || !decor.isAttachedToWindow()) { then.onPicture(null); return; }
            float k = Math.min(1f, PICTURE_LONG_SIDE / (float) Math.max(vw, vh));
            final Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(vw * k)), Math.max(1, Math.round(vh * k)), Bitmap.Config.ARGB_8888);
            PixelCopy.request(w, b, result -> {
                if (result == PixelCopy.SUCCESS) then.onPicture(b);
                else { b.recycle(); then.onPicture(null); }
            }, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException | OutOfMemoryError e) {
            then.onPicture(null);
        }
    }
}
