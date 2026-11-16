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
     * Set the screen up for a show from JoyBrush to Studio ({@code toStudio}) or to SpriteLab. Null: no
     * show this time (switched off, an old phone, or JoyBrush took no picture of itself), and the caller
     * carries on exactly as before.
     */
    @Nullable
    public static HandoffHost open(@NonNull Activity a, boolean toStudio) {
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
                    // the arriving room's gradient, as the lobby and Joybot colour it (RoomColours)
                    ContextCompat.getColor(a, toStudio ? R.color.s_room_studio : R.color.s_room_sprite),
                    ContextCompat.getColor(a, toStudio ? R.color.s_go_end : R.color.s_room_avatar),
                    true);       // round the lobby dial both Studio and SpriteLab are nearer to the RIGHT of JoyBrush
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
        card.post(() -> seat(dialog, dw, card, 3));
    }

    /** Room his whole body needs under the card, dp: his seat, his hover, his thruster's rings. */
    private static final float UNDER_DP = 128f;

    /**
     * His seat is by the card's lower left corner (his corner in the chat too). A card too tall to
     * leave him room there (a long list of projects) is shortened and hung from the top: its list
     * scrolls, and he is never squeezed off the screen.
     */
    private void seat(@NonNull Dialog dialog, @NonNull Window dw, @NonNull View card, int tries) {
        View panel = dialog.findViewById(androidx.appcompat.R.id.parentPanel);
        View box = panel != null ? panel : card;     // the card itself, not the window's clear margins round it
        int[] at = new int[2];
        box.getLocationOnScreen(at);
        float left = at[0] / density, bottom = (at[1] + box.getHeight()) / density;
        float short_ = UNDER_DP - (winH - bottom);     // how much more room he needs under the card
        if (tries > 0 && short_ > 2f && box.getHeight() / density - short_ > 160f) {
            // Measured, not worked out: where a dialog sits depends on the phone's bars and cut-outs.
            // Hang it from the top and take off what is missing; look again once it has been laid out.
            WindowManager.LayoutParams lp = dw.getAttributes();
            if ((lp.gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK) != android.view.Gravity.TOP) {
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                lp.y = 0;
                dw.setAttributes(lp);
            }
            dw.setLayout(card.getWidth(), Math.round(card.getHeight() - short_ * density));
            card.post(() -> card.post(() -> seat(dialog, dw, card, tries - 1)));
            return;
        }
        engine.ask(left + 34f, Math.min(winH - 60f, bottom + 58f));
    }

    /** The answer is in, or there was nothing to ask: he goes into the move. */
    public void go() {
        scrim.setAlpha(0f);
        engine.go();
    }

    /**
     * The question was dismissed: there is no hand-off after all. He flies back into the button he came
     * out of, and {@code then} runs when he is in (about half a second): close the screen there.
     */
    public void cancel(@NonNull Runnable then) {
        scrim.setAlpha(0f);
        engine.retreat();
        scrim.postDelayed(then, (long) (HandoffMoves.RETREAT_S * 1000f) + 80L);
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
