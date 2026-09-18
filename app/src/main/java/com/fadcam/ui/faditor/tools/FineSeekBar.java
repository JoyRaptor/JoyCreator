package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.widget.SeekBar;

import androidx.annotation.NonNull;

/**
 * A {@link SeekBar} with a FINE-DRAG ZONE: drag along the bar for the normal sweep, or pull the
 * finger down away from it and keep dragging sideways for {@link #FINE_GAIN}-scale control.
 *
 * <p><b>Why not velocity.</b> JoyRaptor asked for exact values while scrubbing and suggested velocity
 * sensitivity (2026-08-12). Velocity couples precision to how fast you happen to be moving, so the
 * same gesture gives a different result twice in a row and slowing down mid-drag shifts the value
 * under the finger — the control stops being predictable, which is the opposite of what "I need an
 * exact number" asks for. Distance from the bar is a deliberate, held choice instead: the user
 * decides the ratio and it stays put for as long as they hold it there. This is how Resolve and
 * Lightroom do it, and typing the number outright covers the case where even fine is too slow.</p>
 *
 * <p><b>Once fine, fine for the rest of the gesture.</b> Sliding back up does not restore the
 * coarse mapping, because that mapping is ABSOLUTE (the thumb goes where the finger is) while this
 * one is RELATIVE (the thumb moves by a fraction of how far the finger went). Handing control back
 * mid-drag would snap the thumb to the finger, which is precisely the kind of jump the fine mode
 * exists to avoid. Lift and touch again to get the coarse sweep back.</p>
 */
public class FineSeekBar extends SeekBar {

    /** Fine mode moves the value at this fraction of the finger's travel. */
    private static final float FINE_GAIN = 0.2f;

    /** How far below the bar the finger must go before fine mode engages (dp). */
    private static final float FINE_ENTER_DP = 34f;

    private final float density;
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean fine;
    /** Progress and touch x at the moment fine mode engaged — the relative mapping's origin. */
    private float anchorProgress, anchorX;
    private float downY;
    /**
     * True only while this class is driving {@link #setProgress}, so the row's listener can tell a
     * fine drag (a real user edit) from the playhead-tick refresh that also calls setProgress.
     * Both arrive with {@code fromUser == false}, and without this the fine drag would be silently
     * discarded as a programmatic change.
     */
    private boolean driving;

    public FineSeekBar(@NonNull Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        hintPaint.setTextSize(9f * density);
        hintPaint.setColor(Studio.ROOM_AVATAR_DEEP);
    }

    /** @see #driving */
    public boolean isFineDriving() { return driving; }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fine = false;
                downY = e.getY();
                // Claim the gesture up front. Entering fine mode requires a deliberate VERTICAL
                // drag, and these rows live inside a ScrollView — which would otherwise take the
                // gesture as a scroll before the finger ever got far enough down to qualify.
                // AbsSeekBar only disallows interception once its own slop is passed, which is too
                // late for a movement that is vertical on purpose.
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                if (!fine && e.getY() - downY > FINE_ENTER_DP * density) {
                    fine = true;
                    anchorProgress = getProgress();
                    anchorX = e.getX();
                    invalidate();
                }
                if (fine) {
                    // Relative: the thumb travels a fraction of the finger's horizontal distance
                    // from where fine mode began. The bar's own width is the reference, so the
                    // ratio means the same thing on a narrow row as on a wide one.
                    int usable = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
                    float perPx = getMax() / (float) usable;
                    float p = anchorProgress + (e.getX() - anchorX) * perPx * FINE_GAIN;
                    int clamped = Math.max(0, Math.min(getMax(), Math.round(p)));
                    driving = true;
                    setProgress(clamped);
                    driving = false;
                    // The parent must not steal a gesture that has deliberately left the bar.
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (fine) {
                    fine = false;
                    // super still thinks it is mid-drag: it saw the DOWN. Swallowing the UP would
                    // leave it pressed with onStopTrackingTouch never called. Handing it the real
                    // UP is worse — AbsSeekBar tracks the touch on UP, which would jump the value
                    // to wherever the finger ended, undoing the whole point of the fine drag. A
                    // CANCEL cleans up without touching progress, which is exactly what is wanted.
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    invalidate();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!fine) return;
        // Said, not guessed at. A precision mode nobody can tell is on reads as a broken slider —
        // the same discoverability complaint the drag affordance drew.
        String label = "FINE";                                             // TODO(strings)
        canvas.drawText(label, getPaddingLeft(), getHeight() - 1f * density, hintPaint);
    }
}
