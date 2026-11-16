package cc.joycreator.handoff;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.R;

import cc.joycreator.joybrush.android.handoff.RoomShot;

/**
 * The first half of a hand-off (FX-1), on the screen between two rooms: the room you left is still
 * on screen as a picture, Joybot is already out of his button, and if there is something to ask
 * ("which project?") he is the one beside the question, waiting in his hover.
 *
 * <p>Owner, 16 Nov: "If there's a dialogue that comes up, the Joybot should already be out and part
 * of that dialogue asking you."</p>
 */
@MainThread
public final class HandoffHost {

    private final Activity activity;
    private final HandoffEngine engine;
    private final View scrim;
    private final float density;
    private final float winH;

    private HandoffHost(Activity a, HandoffEngine e, View scrim, float density, float winH) {
        this.activity = a;
        this.engine = e;
        this.scrim = scrim;
        this.density = density;
        this.winH = winH;
    }

    /**
     * Set the screen up for a show from JoyBrush to Studio. Null: no show this time (switched off, an
     * old phone, or JoyBrush took no picture of itself), and the caller carries on exactly as before.
     */
    @Nullable
    public static HandoffHost open(@NonNull Activity a) {
        RoomShot.Shot shot = RoomShot.take();
        if (shot == null) return null;
        if (!Handoff.enabled(a)) { shot.getPicture().recycle(); return null; }
        try {
            Bitmap pic = shot.getPicture();
            float d = a.getResources().getDisplayMetrics().density;
            float w = shot.getWindowW() / d, h = shot.getWindowH() / d;
            Rect btn = shot.getJoybot();
            // as the rooms on both sides: the whole screen, no system bars sliding in between them
            Window win = a.getWindow();
            win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            HandoffEngine e = HandoffEngine.start(a, Handoff.nextMove(a), d, w, h,
                    btn.exactCenterX() / d, btn.exactCenterY() / d, pic,
                    0xFF5C43FD, 0xFF4397FD,                         // JoyBrush's gradient (RoomColours.JB_START / JB_END)
                    ContextCompat.getColor(a, R.color.s_room_studio), ContextCompat.getColor(a, R.color.s_go_end));
            FrameLayout root = new FrameLayout(a);
            ImageView room = new ImageView(a);
            room.setScaleType(ImageView.ScaleType.FIT_XY);
            room.setImageBitmap(pic);
            root.addView(room, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(a);
            scrim.setBackgroundColor(0x73000000);
            scrim.setAlpha(0f);
            root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(new HandoffSurfaceView(a, e), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ((ViewGroup) win.getDecorView()).addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new HandoffHost(a, e, scrim, d, h);
        } catch (RuntimeException | OutOfMemoryError e) {
            HandoffEngine live = HandoffEngine.live();
            if (live != null) live.abort();
            return null;
        }
    }

    /**
     * Show the question with Joybot beside it. The dialog keeps its own look; the room behind is
     * dimmed here instead of by the dialog, so that he is not dimmed with it.
     */
    public void present(@NonNull Dialog dialog) {
        Window dw = dialog.getWindow();
        if (dw != null) dw.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.show();
        scrim.setAlpha(1f);
        final View card = dw != null ? dw.getDecorView() : null;
        if (card == null) { engine.ask(60f, .7f * winH); return; }
        card.post(() -> {
            // the card itself, not the dialog window's clear margins around it
            View panel = dialog.findViewById(androidx.appcompat.R.id.parentPanel);
            View box = panel != null ? panel : card;
            int[] at = new int[2];
            box.getLocationOnScreen(at);
            float left = at[0] / density, top = at[1] / density, bottom = (at[1] + box.getHeight()) / density;
            // his seat: by the card's lower left corner (his corner in the chat too); above it when there is no room below
            float seatX = left + 34f;
            float seatY = bottom + 52f;
            if (seatY + 44f > winH) seatY = Math.max(60f, top - 44f);
            engine.ask(seatX, seatY);
        });
    }

    /** The answer is in, or there was nothing to ask: he goes into the move. */
    public void go() {
        scrim.setAlpha(0f);
        engine.go();
    }

    /** The question was dismissed: there is no hand-off after all. */
    public void cancel() {
        engine.abort();
    }

    /** Mark the next room's intent, so it carries the show on, and knows which item is arriving. */
    public void into(@NonNull Intent room, @Nullable String itemId) {
        room.putExtra(HandoffArrival.EXTRA, true);
        if (itemId != null) room.putExtra(HandoffArrival.EXTRA_ITEM, itemId);
    }

    /** The hand-off failed (the usual message is shown by the caller): no show. */
    public void fail() {
        engine.abort();
    }

    /** Start the next room with no window animation of Android's own: the show is the transition. */
    public void start(@NonNull Intent room) {
        activity.startActivity(room);
        activity.overridePendingTransition(0, 0);
    }
}
