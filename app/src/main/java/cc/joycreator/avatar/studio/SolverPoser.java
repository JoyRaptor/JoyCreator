package cc.joycreator.avatar.studio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import cc.joycreator.avatar.draw.OffscreenCharacterGl;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.solve.BitmapPics;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;
import cc.joycreator.avatar.ui.SignalMap;

import org.json.JSONObject;

import java.util.Map;

/**
 * The real {@link CharacterPoser}: the pilot's solver ({@link CharacterSolver}, X2) poses the character from the take's
 * signals, and its GPU drawing ({@code CharacterGl}, X3, through {@link OffscreenCharacterGl}) paints it, the same two
 * the room's stage uses, so a character moves in Studio's preview and export exactly as it does in the room.
 *
 * <p>The solver's springs run on the item's own time: the step is the time since the last frame (capped), and going
 * back in time starts them again ({@link #rewind}), so a replay from the start is the same in preview and export. If
 * this phone's GPU cannot give a drawing surface, the standing stand-in ({@link RestPoser}) draws instead, as the room
 * falls back to its stand-in.</p>
 */
public final class SolverPoser implements CharacterPoser {
    private static final String TAG = "SolverPoser";
    /** The drawing's longest side in pixels at most (Studio's character boxes are seldom larger on screen or in export). */
    static final int MAX_SIDE = 1024;
    /** The longest step the springs take in one go (a seek or a stall does not fling the character). */
    private static final double MAX_DT = 0.1;

    private final CharacterDoc doc;
    private final CharacterPictures pictures;
    private final RestPoser stand;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final float scale;
    private final double breathRate;
    private final boolean breathes;
    private BitmapPics pics;
    private CharacterSolver solver;
    private OffscreenCharacterGl gl;
    private boolean broken;
    private double lastT = Double.NaN;
    private volatile java.util.Map<String, double[]> tips = java.util.Collections.emptyMap();
    private volatile double[] rootM;

    public SolverPoser(CharacterDoc doc, CharacterPictures pictures) {
        this.doc = doc;
        this.pictures = pictures;
        this.stand = new RestPoser(doc, pictures);
        this.scale = Math.min(1f, MAX_SIDE / (float) Math.max(1, Math.max(doc.w, doc.h)));
        JSONObject set = doc.json.optJSONObject("settings");
        breathes = set == null || set.optBoolean("breathe", true);
        breathRate = set == null ? 0.28 : set.optDouble("breathRate", 0.28);
    }

    @Override
    public synchronized void draw(Canvas c, Map<String, Object> signals, double seconds) {
        if (broken) {
            stand.draw(c, signals, seconds);
            return;
        }
        try {
            if (solver == null) {
                pics = BitmapPics.read(doc.json, (id, src) -> pictures.get(id));
                solver = new CharacterSolver(doc.json, pics);
            }
            if (gl == null) {
                gl = new OffscreenCharacterGl(Math.max(1, Math.round(doc.w * scale)), Math.max(1, Math.round(doc.h * scale)));
            }
            double dt = Double.isNaN(lastT) ? 1 / 30.0 : Math.max(0, Math.min(MAX_DT, seconds - lastT));
            lastT = seconds;
            Signals s = SignalMap.toSignals(signals == null ? java.util.Collections.emptyMap() : signals, seconds,
                breathes ? Math.sin(seconds * 2 * Math.PI * breathRate) : 0);
            CharacterSolver.Opt opt = new CharacterSolver.Opt();
            opt.live = true;
            opt.dt = dt > 0 ? dt : 1 / 30.0;
            SolveOut out = solver.eval(s, opt);
            tips = new java.util.HashMap<>(s.tips);
            rootM = out.rootM;
            Bitmap b = gl.draw(out, pics, scale);
            c.drawBitmap(b, new Rect(0, 0, gl.width(), gl.height()), new RectF(0, 0, doc.w, doc.h), paint);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "the GPU drawing could not run here; the standing picture draws instead", e);
            broken = true;
            releaseGl();
            stand.draw(c, signals, seconds);
        }
    }

    /** Where each hand ("L", "R") was drawn on the last frame, character pixels (a finger on a hand takes it there). */
    public java.util.Map<String, double[]> tips() {
        return tips;
    }

    /** Stage coordinates back to the character's own (a turned-round character mirrored back), from the last frame. */
    public double[] toInner(double[] p) {
        double[] m = rootM;
        return m == null ? p : cc.joycreator.avatar.solve.Mat.ap(cc.joycreator.avatar.solve.Mat.inv(m), p[0], p[1]);
    }

    @Override
    public synchronized void rewind() {
        if (solver != null) solver = new CharacterSolver(doc.json, pics);
        lastT = Double.NaN;
    }

    @Override
    public synchronized void release() {
        releaseGl();
        solver = null;
        pics = null;
        pictures.release();
    }

    private void releaseGl() {
        if (gl != null) {
            try {
                gl.release();
            } catch (RuntimeException ignored) {
            }
            gl = null;
        }
    }
}
