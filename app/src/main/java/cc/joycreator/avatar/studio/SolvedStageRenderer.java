package cc.joycreator.avatar.studio;

import android.graphics.Canvas;
import android.graphics.RectF;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.ui.PoseRenderer;
import cc.joycreator.avatar.ui.PoseRenderers;

import java.util.List;
import java.util.Map;

/**
 * The little stage in Studio's performing pop-up, drawn by the real solver (the same {@link SolverPoser} Studio's
 * preview and export use), so the character moves there as it will in the video and a finger can find its hands
 * ({@link #tips}). Everything else the stage asks (what is under a finger, where a part is) the room's stand-in answers.
 */
public final class SolvedStageRenderer implements PoseRenderer {
    private final PoseRenderer rest;
    private final SolverPoser poser;
    private final long start = System.nanoTime();
    private Map<String, Object> signals;

    public SolvedStageRenderer(CharacterDoc doc, CharacterPictures.Beside beside) {
        rest = PoseRenderers.rest(doc);
        poser = new SolverPoser(doc, new CharacterPictures(doc, beside));
    }

    /** Where each hand was drawn on the last frame ("L", "R"), character pixels. */
    public Map<String, double[]> tips() {
        return poser.tips();
    }

    /** Stage coordinates to the character's own. */
    public double[] toInner(double[] p) {
        return poser.toInner(p);
    }

    @Override
    public void setSignals(Map<String, Object> s) {
        signals = s;
        rest.setSignals(s);
    }

    @Override
    public void draw(Canvas c) {
        poser.draw(c, signals, (System.nanoTime() - start) / 1e9);
    }

    @Override public void retarget(CharacterDoc doc) { rest.retarget(doc); }

    @Override public boolean samePictures(CharacterDoc doc) { return rest.samePictures(doc); }

    @Override public void drawSubtree(Canvas c, CharacterDoc.Node n, int tint, int alpha) { rest.drawSubtree(c, n, tint, alpha); }

    @Override public RectF bounds(CharacterDoc.Node n) { return rest.bounds(n); }

    @Override public List<CharacterDoc.Node> shown(CharacterDoc.Node n) { return rest.shown(n); }

    @Override public double[] restBounds(CharacterDoc.Node n) { return rest.restBounds(n); }

    @Override public cc.joycreator.avatar.solve.Pics pics() { return rest.pics(); }

    @Override public CharacterDoc.Node hit(float x, float y) { return rest.hit(x, y); }

    @Override public void tryDrawing(String setId, String kidId) { rest.tryDrawing(setId, kidId); }

    @Override
    public void release() {
        poser.release();
        rest.release();
    }
}
