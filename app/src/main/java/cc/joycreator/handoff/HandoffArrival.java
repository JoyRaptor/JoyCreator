package cc.joycreator.handoff;

import android.app.Activity;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.timeline.EditorTimelineView;

/**
 * The second half of a hand-off (FX-1), in the room the work arrives in.
 *
 * <p>The room opens underneath the show, which carries on in a layer over it. When the room is laid
 * out it brings the new item's row into view, is pictured, and says where the item really is; the
 * show then finishes on that picture and slaps the item down in exactly that place. At the slap the
 * picture is gone and the REAL room is on screen, with the item selected by the room's own
 * selection (no fake), ready to work on, while Joybot salutes and flies home to his button.</p>
 */
@MainThread
public final class HandoffArrival implements HandoffEngine.Listener {

    public static final String EXTRA = "handoff_show", EXTRA_ITEM = "handoff_item";
    /** Never hold the room's first picture back longer than this for the show's layer to be ready. */
    private static final long HOLD_MS = 450L;
    /** The item's row must sit still this long before the room is pictured (its reveal slides briefly). */
    private static final long STILL_MS = 120L;
    private static final long REPORT_BY_MS = 2500L;

    private final Activity room;
    private final HandoffEngine engine;
    @Nullable private final String itemId;
    private final ViewGroup decor;
    private final HandoffSurfaceView layer;
    private boolean drawn, reporting, reported, gone;
    @Nullable private EditorTimelineView timeline;
    @Nullable private View joybot;
    private long holdUntil, reportBy, stillSince;
    @Nullable private RectF lastRect;

    private HandoffArrival(Activity room, HandoffEngine engine, @Nullable String itemId) {
        this.room = room;
        this.engine = engine;
        this.itemId = itemId;
        this.decor = (ViewGroup) room.getWindow().getDecorView();
        this.layer = new HandoffSurfaceView(room, engine);
    }

    /**
     * Call in the room's onCreate, after its content view is set. Null: nothing is arriving (no show
     * in progress, or this screen is being rebuilt), and the room opens as it always has.
     */
    @Nullable
    public static HandoffArrival attach(@NonNull Activity room, @Nullable Bundle saved) {
        boolean wanted = room.getIntent() != null && room.getIntent().getBooleanExtra(EXTRA, false);
        if (!wanted) return null;
        String item = room.getIntent().getStringExtra(EXTRA_ITEM);
        room.getIntent().removeExtra(EXTRA);          // a rebuild of this screen must not ask again
        HandoffEngine e = HandoffEngine.live();
        if (saved != null || e == null) return null;
        try {
            HandoffArrival a = new HandoffArrival(room, e, item);
            a.decor.addView(a.layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            e.setListener(a);
            // The room's first picture waits until the show's layer has a frame in it: without this the
            // bare room would flash for a frame or two before the show covered it.
            a.holdUntil = SystemClock.uptimeMillis() + HOLD_MS;
            a.decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (a.drawn || a.gone || SystemClock.uptimeMillis() > a.holdUntil) {
                        ViewTreeObserver o = a.decor.getViewTreeObserver();
                        if (o.isAlive()) o.removeOnPreDrawListener(this);
                        return true;
                    }
                    a.decor.postInvalidateOnAnimation();
                    return false;
                }
            });
            return a;
        } catch (RuntimeException ex) {
            e.abort();
            return null;
        }
    }

    /**
     * The room is loaded and showing (its onResume). {@code timeline}: where the item's tape is;
     * {@code joybotButton}: where Joybot lives in this room, his way home.
     */
    public void roomReady(@NonNull EditorTimelineView timeline, @Nullable View joybotButton) {
        if (reporting || gone) return;
        reporting = true;
        this.timeline = timeline;
        this.joybot = joybotButton;
        reportBy = SystemClock.uptimeMillis() + REPORT_BY_MS;
        if (itemId != null) timeline.revealLayerRowForItem(itemId);
        timeline.postDelayed(this::watchItem, 50L);
    }

    /** Wait for the room's first real picture and for the item's row to stop sliding, then picture the room. */
    private void watchItem() {
        EditorTimelineView t = timeline;
        if (gone || reported || t == null) return;
        long now = SystemClock.uptimeMillis();
        RectF r = itemId != null ? t.layerItemRectInView(itemId) : null;
        boolean still = r != null && lastRect != null && Math.abs(r.top - lastRect.top) < .5f && Math.abs(r.left - lastRect.left) < .5f;
        if (!still) stillSince = now;
        lastRect = r;
        boolean laidOut = t.getWidth() > 0 && t.isLaidOut();
        boolean late = now > reportBy;
        if (laidOut && ((still && now - stillSince >= STILL_MS) || late) && (drawn || now > holdUntil)) {
            report(r);
            return;
        }
        t.postDelayed(this::watchItem, 34L);
    }

    private void report(@Nullable RectF itemInView) {
        reported = true;
        final EditorTimelineView t = timeline;
        final float d = room.getResources().getDisplayMetrics().density;
        RectF item = null;
        if (itemInView != null && t != null) {
            int[] at = new int[2];
            t.getLocationInWindow(at);
            item = new RectF((at[0] + itemInView.left) / d, (at[1] + itemInView.top) / d,
                    (at[0] + itemInView.right) / d, (at[1] + itemInView.bottom) / d);
        }
        float homeX = decor.getWidth() / d - 24f, homeY = 40f;
        View j = joybot;
        if (j != null && j.isShown() && j.getWidth() > 0) {
            int[] at = new int[2];
            j.getLocationInWindow(at);
            homeX = (at[0] + j.getWidth() * .5f) / d;
            homeY = (at[1] + j.getHeight() * .5f) / d;
        }
        final RectF where = item;
        final float hx = homeX, hy = homeY;
        Handoff.picture(room, picture -> {
            if (gone) { if (picture != null) picture.recycle(); return; }
            if (picture == null) { engine.abort(); return; }
            engine.roomReady(picture, where, hx, hy);
        });
    }

    /** The room is closing (its onDestroy): the show ends with it. */
    public void drop() {
        if (gone) return;
        gone = true;
        engine.setListener(null);
        engine.abort();
    }

    // ── from the show ───────────────────────────────────────────────────────

    @Override
    public void onFirstFrame(@NonNull Surface surface) {
        drawn = true;
    }

    @Override
    public void onSlap() {
        // the room's own selection, the moment the item is down
        layer.letTouchesThrough();
        EditorTimelineView t = timeline;
        if (t != null && itemId != null && !gone) {
            try { t.selectLayerItemById(itemId); } catch (RuntimeException ignored) { /* the show never breaks the room */ }
        }
    }

    @Override
    public void onDone(boolean drew) {
        gone = true;
        drawn = true;
        try { decor.removeView(layer); } catch (RuntimeException ignored) { /* the window is going */ }
    }
}
