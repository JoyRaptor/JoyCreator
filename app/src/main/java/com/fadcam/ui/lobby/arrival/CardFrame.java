package com.fadcam.ui.lobby.arrival;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.lobby.JoybotArrival;

/**
 * The chat as a floating card: placed by dragging its title, sized by its corner, remembered.
 *
 * <p>Owner, 15 Nov: "Floating card is a good way to go, and it should be resizable and placeable. And
 * probably a little bit shorter than the whole of the screen. That way you can see where he comes out
 * of ... maybe people want to squish it down really small so that they just always have it up." So
 * once Joybot has arrived the chat's window shrinks to the card, and touches beside it reach the
 * screen underneath: the card can stay up while you work. During the arrival the window covers the
 * whole screen, because he flies in from the button.</p>
 */
final class CardFrame {

    /** Smallest card, dp. */
    static final float MIN_W = 240f, MIN_H = 300f;

    private final Activity a;
    private final FrameLayout root;
    private final View card;
    private final float d;
    private boolean windowed;
    /** The root's place on screen and size while the window covered the screen, px: card bounds are kept as fractions of it. */
    private final Rect ref = new Rect();

    CardFrame(@NonNull Activity a, @NonNull FrameLayout root, @NonNull View card) {
        this.a = a;
        this.root = root;
        this.card = card;
        this.d = a.getResources().getDisplayMetrics().density;
    }

    boolean windowed() { return windowed; }

    @Nullable private Runnable onMoved;

    /** Told after every move or resize (the frost redraws to stay lined up with the screen). */
    void setOnMoved(@Nullable Runnable r) { onMoved = r; }

    /** Only a card moves and sizes; a full sheet ignores the drags. */
    private boolean enabled;

    void setEnabled(boolean on) { enabled = on; }

    /** Where the card goes, in root pixels: where it was left, or the default for this window. */
    @NonNull
    Rect bounds(@Nullable Rect buttonOnScreen) {
        int W = root.getWidth(), H = root.getHeight();
        int[] ro = new int[2];
        root.getLocationOnScreen(ro);
        ref.set(ro[0], ro[1], ro[0] + W, ro[1] + H);
        RectF f = JoybotArrival.cardBounds(a);
        Rect r;
        if (f != null) {
            r = new Rect(Math.round(f.left * W), Math.round(f.top * H), Math.round(f.right * W), Math.round(f.bottom * H));
        } else {
            float buttonBottom = buttonOnScreen != null ? buttonOnScreen.bottom - ro[1] : -1f;
            if (com.fadcam.ui.size.WindowSize.isExpanded(a)) {
                // beside the work, a little shorter than the screen so the button he comes out of stays in sight
                int w = Math.round(Math.min(420f * d, W * .42f));
                int top = Math.round(buttonBottom > 0 ? buttonBottom + 12f * d : 72f * d);
                r = new Rect(W - Math.round(16f * d) - w, top, W - Math.round(16f * d), H - Math.round(16f * d));
            } else {
                int top = Math.round(buttonBottom > 0 ? buttonBottom + 8f * d : 56f * d);
                int m = Math.round(8f * d);
                r = new Rect(m, top, W - m, H - m);
            }
        }
        return clamp(r, W, H);
    }

    @NonNull
    private Rect clamp(@NonNull Rect r, int W, int H) {
        int minW = Math.min(W, Math.round(MIN_W * d)), minH = Math.min(H, Math.round(MIN_H * d));
        int w = Math.max(minW, Math.min(W, r.width())), h = Math.max(minH, Math.min(H, r.height()));
        int l = Math.max(0, Math.min(W - w, r.left)), t = Math.max(0, Math.min(H - h, r.top));
        return new Rect(l, t, l + w, t + h);
    }

    /** Put the card at {@code r} (root pixels) inside the full-screen window. */
    void place(@NonNull Rect r) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r.width(), r.height(), Gravity.TOP | Gravity.START);
        lp.leftMargin = r.left;
        lp.topMargin = r.top;
        card.setLayoutParams(lp);
    }

    /** After the arrival: the window becomes the card, and touches beside it reach the screen below. */
    void enterWindow(boolean frost) {
        if (windowed || !card.isLaidOut()) return;
        Window w = a.getWindow();
        if (w == null) return;
        int[] at = new int[2];
        card.getLocationOnScreen(at);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = at[0];
        lp.y = at[1];
        lp.width = card.getWidth();
        lp.height = card.getHeight();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        // A rounded window background shapes the frost blur to the card's corners.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x01000000);
        bg.setCornerRadius(24f * d);
        w.setBackgroundDrawable(bg);
        card.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        w.setAttributes(lp);
        windowed = true;
        if (Build.VERSION.SDK_INT >= 31) ChatArrival.blur(a, frost ? Math.round(14f * d) : 0);
    }

    /** Back to a full-screen window with the card inside it (the screen turned or resized). */
    void leaveWindow() {
        if (!windowed) return;
        Window w = a.getWindow();
        if (w == null) return;
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.x = 0;
        lp.y = 0;
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        w.setAttributes(lp);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        windowed = false;
        if (Build.VERSION.SDK_INT >= 31) ChatArrival.blur(a, 0);
    }

    // ── moving and sizing ───────────────────────────────────────────────────

    /** Drag {@code handle} (the title) to move the card; drag {@code grip} (its corner) to size it. */
    @SuppressLint("ClickableViewAccessibility")
    void attach(@NonNull View handle, @NonNull View grip) {
        final int slop = ViewConfiguration.get(a).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (!enabled) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!dragging && Math.hypot(dx, dy) < slop) return true;
                        dragging = true;
                        moveBy(Math.round(dx), Math.round(dy), false);
                        sx = e.getRawX();
                        sy = e.getRawY();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) save();
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
        grip.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int edges;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (!enabled) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        edges = v instanceof CardGrip ? ((CardGrip) v).edgesAt(e.getX(), e.getY()) : (2 | 4);
                        if (edges == 0) return false;          // not an edge: the chat underneath takes it
                        sx = e.getRawX();
                        sy = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (edges == 0) return false;
                        resize(edges, Math.round(e.getRawX() - sx), Math.round(e.getRawY() - sy));
                        sx = e.getRawX();
                        sy = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        save();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Move the card by {@code dx, dy} pixels. */
    private void moveBy(int dx, int dy, boolean unused) {
        Rect c = current();
        c.offset(dx, dy);
        apply(clamp(c, ref.width(), ref.height()));
    }

    /** Move the grabbed edges ({@link CardGrip#edgesAt}) by {@code dx, dy} pixels, never below the smallest card. */
    private void resize(int edges, int dx, int dy) {
        Rect c = current();
        int W = ref.width(), H = ref.height(), minW = Math.round(MIN_W * d), minH = Math.round(MIN_H * d);
        if ((edges & 1) != 0) c.left = Math.max(0, Math.min(c.right - minW, c.left + dx));
        if ((edges & 2) != 0) c.right = Math.min(W, Math.max(c.left + minW, c.right + dx));
        if ((edges & 4) != 0) c.bottom = Math.min(H, Math.max(c.top + minH, c.bottom + dy));
        apply(c);
    }

    private void apply(@NonNull Rect c) {
        if (windowed) {
            Window w = a.getWindow();
            if (w == null) return;
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.x = ref.left + c.left;
            lp.y = ref.top + c.top;
            lp.width = c.width();
            lp.height = c.height();
            w.setAttributes(lp);
        } else {
            place(c);
        }
        if (onMoved != null) onMoved.run();
    }

    /** The card now, in the reference (full-screen root) pixels. */
    @NonNull
    private Rect current() {
        if (windowed) {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            int l = lp.x - ref.left, t = lp.y - ref.top;
            int w = lp.width > 0 ? lp.width : card.getWidth(), h = lp.height > 0 ? lp.height : card.getHeight();
            return new Rect(l, t, l + w, t + h);
        }
        return new Rect(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
    }

    private void save() {
        if (ref.width() <= 0 || ref.height() <= 0) return;
        Rect r = current();
        JoybotArrival.setCardBounds(a, new RectF(r.left / (float) ref.width(), r.top / (float) ref.height(),
                r.right / (float) ref.width(), r.bottom / (float) ref.height()));
    }
}
