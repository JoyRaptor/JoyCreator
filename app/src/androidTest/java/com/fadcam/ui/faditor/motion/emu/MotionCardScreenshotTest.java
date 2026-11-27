package com.fadcam.ui.faditor.motion.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.R;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.ShutterKeys;
import com.fadcam.ui.faditor.motion.card.MotionBlurCard;
import com.fadcam.ui.faditor.motion.card.ShutterStripView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real Motion Blur card (STU-7 spec §K), drawn at the Note 9's 548dp drawer width on the editor's
 * theme, driven the way a finger would: drag the strip into a long trail, Streak, More, Copies, a key.
 * Screenshots in files/motion-emu/card/. A fake host records what the card asks the editor to keep.
 */
@RunWith(AndroidJUnit4.class)
public final class MotionCardScreenshotTest {

    private static final class FakeHost implements MotionBlurCard.Host {
        MotionBlurCard.State state = new MotionBlurCard.State(MotionShutter.Controls.NATURAL, ShutterKeys.EMPTY);
        final List<MotionBlurCard.State> commits = new ArrayList<>();
        long playhead = 1000;
        @NonNull @Override public MotionBlurCard.State state() { return state; }
        @Override public void preview(@NonNull MotionBlurCard.State draft) { }
        @Override public void commit(@NonNull MotionBlurCard.State before, @NonNull MotionBlurCard.State after) {
            state = after;
            commits.add(after);
        }
        @Override public long playheadMs() { return playhead; }
        @Override public void seekTo(long ms) { playhead = ms; }
        @Override public void remove() { }
        @Override public void showExactFrame() { }
        @Override public void compare(boolean sharp) { }
        @Override public int cannotBlurReason() { return 0; }
    }

    @Test public void cardWalkthrough() throws Exception {
        Context app = MotionEmu.ins().getTargetContext();
        Context ctx = new ContextThemeWrapper(app, R.style.Theme_FadCam_FaditorEditor);
        FakeHost host = new FakeHost();
        float d = app.getResources().getDisplayMetrics().density;
        int width = Math.round(548 * d);
        AtomicReference<FrameLayout> drawer = new AtomicReference<>();
        MotionEmu.ins().runOnMainSync(() -> {
            FrameLayout f = new FrameLayout(ctx);
            f.setBackgroundColor(0xFF0B0C10);
            int pad = Math.round(10 * d);
            f.setPadding(pad, pad, pad, pad);
            f.addView(MotionBlurCard.build(ctx, "emu-card", host));
            drawer.set(f);
        });
        File dir = MotionEmu.dir("card");

        shot(drawer.get(), width, new File(dir, "1-natural.png"));
        TextView value = findText(drawer.get(), "180° · centred");
        assertNotNull("the shutter value reads 180° · centred", value);

        // Drag the opening end of the strip to the left edge and keep pulling: a long trail.
        ShutterStripView strip = find(drawer.get(), ShutterStripView.class);
        assertNotNull("the card has a shutter strip", strip);
        MotionEmu.ins().runOnMainSync(() -> {
            float[] at = new float[]{strip.getWidth() * 0.5f - 0.25f * (strip.getWidth() - 16 * d) / 2f};
            long t = SystemClock.uptimeMillis();
            float y = 17 * d;
            strip.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, at[0], y, 0));
            for (int i = 0; i < 40; i++) {
                strip.dispatchTouchEvent(MotionEvent.obtain(t, t + 10L * (i + 1), MotionEvent.ACTION_MOVE,
                        Math.max(0f, at[0] - i * 40 * d), y, 0));
            }
            strip.dispatchTouchEvent(MotionEvent.obtain(t, t + 500, MotionEvent.ACTION_UP, 0f, y, 0));
        });
        MotionShutter.Controls pulled = host.state.base();
        MotionEmu.log("after the pull: " + pulled);
        assertTrue("pulling past the last frame makes a long trail: " + pulled, pulled.isLongTrail());
        assertEquals("ending at this frame", MotionShutter.Timing.TRAILING, pulled.timing());
        shot(drawer.get(), width, new File(dir, "2-pulled-long.png"));

        click(findText(drawer.get(), "Streak"), "Streak chip");
        MotionShutter.Controls streak = host.state.base();
        assertEquals("Streak: eight frames back", 8 * 360, streak.angle());
        assertEquals("Streak fades out", 60, streak.fadePercent());
        assertNotNull("the value reads 8 frames back", findText(drawer.get(), "8 frames back"));
        shot(drawer.get(), width, new File(dir, "3-streak.png"));

        click(findTextContaining(drawer.get(), "More"), "More tab");
        assertNotNull("More shows Fade", findText(drawer.get(), "Fade"));
        shot(drawer.get(), width, new File(dir, "4-more-open.png"));
        click(findText(drawer.get(), "Copies"), "Copies chip");
        assertEquals("Look is Copies", MotionShutter.Look.COPIES, host.state.base().look());
        click(findText(drawer.get(), "+"), "more copies");
        assertEquals("five copies", 5, host.state.base().copies());
        shot(drawer.get(), width, new File(dir, "5-copies.png"));

        click(findText(drawer.get(), "Natural"), "Natural chip");
        assertEquals("Natural is a camera shutter again", 180, host.state.base().angle());
        assertEquals("a preset keeps the Look", MotionShutter.Look.COPIES, host.state.base().look());

        click(findText(drawer.get(), "◇"), "Shutter key diamond");
        assertTrue("the diamond keys the shutter at the playhead", host.state.keys().hasShutterKeyAt(1000));
        shot(drawer.get(), width, new File(dir, "6-keyed.png"));
        MotionEmu.log("card walkthrough: " + host.commits.size() + " commits, last " + host.state.base());
    }

    private static void shot(ViewGroup root, int width, File f) throws Exception {
        AtomicReference<Bitmap> out = new AtomicReference<>();
        MotionEmu.ins().runOnMainSync(() -> {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            Bitmap b = Bitmap.createBitmap(root.getMeasuredWidth(), root.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(b));
            out.set(b);
        });
        MotionEmu.save(out.get(), f);
    }

    private static void click(View v, String what) {
        assertNotNull("found the " + what, v);
        MotionEmu.ins().runOnMainSync(v::performClick);
    }

    private static TextView findText(View v, String text) {
        if (v instanceof TextView t && text.contentEquals(t.getText())) return t;
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findText(g.getChildAt(i), text);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static TextView findTextContaining(View v, String text) {
        if (v instanceof TextView t && t.getText().toString().contains(text)) return t;
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findTextContaining(g.getChildAt(i), text);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static <T extends View> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                T hit = find(g.getChildAt(i), type);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
