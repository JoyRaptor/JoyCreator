package cc.joycreator.handoff;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

/**
 * The layer a hand-off show is drawn on, over a whole window (FX-1).
 *
 * <p>A SurfaceView, not a TextureView: its picture reaches the screen without the main thread, so
 * Joybot keeps moving while the main thread is busy opening the next room. See-through wherever the
 * show draws nothing, and above everything else in its window.</p>
 *
 * <p>While the show covers the room, touches stop here (nobody may press what they cannot see, and
 * the room under a show must not change); a tap skips to the end. After the slap the real room is
 * what is on screen, and touches go straight through to it.</p>
 */
@SuppressLint("ViewConstructor")
final class HandoffSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private final HandoffEngine engine;
    private boolean through;

    HandoffSurfaceView(@NonNull Context c, @NonNull HandoffEngine engine) {
        super(c);
        this.engine = engine;
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The real room is showing: let every touch through to it. */
    void letTouchesThrough() { through = true; }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder h) {
        engine.attach(h.getSurface());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder h) {
        engine.detach(h.getSurface());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (through) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) engine.skip();
        return true;
    }
}
