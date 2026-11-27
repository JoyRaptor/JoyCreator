package cc.joycreator.avatar.ui;

import android.graphics.Canvas;
import android.graphics.RectF;

import cc.joycreator.avatar.file.CharacterDoc;

/**
 * What the stage needs from whatever draws the character: the seam where the solver (X2) and the GL drawing (X3) plug in.
 *
 * <p>The room hands it the signals of each frame by the take's own lane names (turnX, turnY, roll, mouth, vis, browL,
 * browR, blinkL, blinkR, lidUp, lidLow, lidTilt, wind, {@code g:<button id>} ...), and it draws the character in character
 * pixels (the stage scales and places it). {@link RestPose} is the stand-in that exists until the solver lands: it swaps
 * whole drawings and bends nothing. The solver's renderer replaces it through {@link StageView#setRendererFactory}.</p>
 */
public interface PoseRenderer {

    /** The signals for this frame (null or empty: the character at rest). */
    void setSignals(java.util.Map<String, Object> signals);

    /** The same character after an edit of its tree (the pictures are the same, so decoded ones are kept). */
    void retarget(CharacterDoc doc);

    /** True when {@code doc} has the same pictures as the one drawn, so {@link #retarget} is enough. */
    boolean samePictures(CharacterDoc doc);

    /** Draws the whole character, in character pixels. */
    void draw(Canvas c);

    /** One part drawn on its own, optionally tinted and see-through (the ghost drawing while matching landmarks). */
    void drawSubtree(Canvas c, CharacterDoc.Node n, int tint, int alpha);

    /** Where a part is on the picture right now, or null when it shows nothing. */
    RectF bounds(CharacterDoc.Node n);

    /** The part and every piece of it that is drawn right now (a set shows one drawing), outermost first. */
    java.util.List<CharacterDoc.Node> shown(CharacterDoc.Node n);

    /** The drawing under a point (character pixels), topmost first, ignoring see-through pixels; null when there is none. */
    CharacterDoc.Node hit(float x, float y);

    /** "Try this drawing": look at one drawing of a set without changing the file; {@code kidId} null puts it back. */
    void tryDrawing(String setId, String kidId);

    void release();
}
