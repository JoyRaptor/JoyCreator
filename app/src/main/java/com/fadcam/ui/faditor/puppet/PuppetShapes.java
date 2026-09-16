package com.fadcam.ui.faditor.puppet;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;

/**
 * A PIN'S SILHOUETTE — the one place a type becomes a shape.
 *
 * <p>JoyRaptor, 2026-09-15: <i>"Pin icon needs to be a diagonal pushpin shape with a color. You
 * previously had pins with round and square and other shapes along with color. That's good. We
 * need that."</i>
 *
 * <p>He is right for a reason beyond taste: colour alone fails on a 9dp dot over somebody else's
 * artwork. A violet pin on a violet dinosaur is invisible, and four hues that differ only in hue
 * are four hues that a colour-blind user cannot tell apart at all. The transform tool already
 * says a ROLE with a shape — a square for a right angle that survives, a circle for
 * anything-goes — so pin types say theirs the same way.
 *
 * <ul>
 *   <li><b>Pin</b> — a leaning pushpin, head up-left, spike into the picture. It is an anchor,
 *       and a pushpin is the only one of the four that looks like it holds something down.</li>
 *   <li><b>Stiff</b> — a square. A right angle reads as "this will not bend".</li>
 *   <li><b>Dangle</b> — a teardrop, heavy end down, because it hangs.</li>
 *   <li><b>Free</b> — a circle. The shape the transform tool already assigns to a handle that
 *       may go anywhere.</li>
 * </ul>
 *
 * <p>Every shape is drawn about its centre at a given radius, with a dark outline first so it
 * survives on artwork of its own colour — the same trick the pins already used and the reason
 * they were legible at all.
 */
public final class PuppetShapes {

    private PuppetShapes() {}

    /** The dark ring that keeps a pin visible on artwork its own colour. */
    private static final int OUTLINE = 0xD9050507;

    private static final Path PATH = new Path();

    /**
     * Draw {@code type} centred at {@code (cx, cy)} with radius {@code r}.
     *
     * @param fill   reused; its style and colour are overwritten
     * @param stroke reused; its style, colour and width are overwritten
     * @param d      display density, for the outline width
     */
    public static void draw(@NonNull Canvas c, @NonNull PuppetPin.Type type,
                            float cx, float cy, float r,
                            @NonNull Paint fill, @NonNull Paint stroke, float d) {
        draw(c, type, cx, cy, r, PuppetPalette.of(type), fill, stroke, d);
    }

    /** As above, with the colour chosen by the caller — a locked rig greys every pin. */
    public static void draw(@NonNull Canvas c, @NonNull PuppetPin.Type type,
                            float cx, float cy, float r, int colour,
                            @NonNull Paint fill, @NonNull Paint stroke, float d) {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float outline = Math.max(1.6f * d, r * 0.34f);

        switch (type) {
            case STIFF: {
                PATH.reset();
                float s = r * 0.92f;
                PATH.addRoundRect(cx - s, cy - s, cx + s, cy + s, r * 0.26f, r * 0.26f,
                        Path.Direction.CW);
                stroke.setColor(OUTLINE);
                stroke.setStrokeWidth(outline);
                c.drawPath(PATH, stroke);
                fill.setColor(colour);
                c.drawPath(PATH, fill);
                return;
            }
            case DANGLE: {
                // A teardrop: point up, weight down. Drawn as a circle with a pulled apex so it
                // reads at 9dp, where a true bezier teardrop turns to mush.
                PATH.reset();
                PATH.moveTo(cx, cy - r * 1.35f);
                PATH.cubicTo(cx + r * 0.95f, cy - r * 0.35f, cx + r, cy + r * 0.25f,
                        cx + r * 0.72f, cy + r * 0.72f);
                PATH.cubicTo(cx + r * 0.3f, cy + r * 1.15f, cx - r * 0.3f, cy + r * 1.15f,
                        cx - r * 0.72f, cy + r * 0.72f);
                PATH.cubicTo(cx - r, cy + r * 0.25f, cx - r * 0.95f, cy - r * 0.35f,
                        cx, cy - r * 1.35f);
                PATH.close();
                stroke.setColor(OUTLINE);
                stroke.setStrokeWidth(outline);
                c.drawPath(PATH, stroke);
                fill.setColor(colour);
                c.drawPath(PATH, fill);
                return;
            }
            case PIN: {
                // THE PUSHPIN, leaning. Rotated about its own centre so the spike points down and
                // right into the artwork and the head sits clear of it, up and left.
                int save = c.save();
                c.rotate(38f, cx, cy);
                float headW = r * 1.05f, headTop = cy - r * 1.15f, headBot = cy - r * 0.05f;
                float spikeLen = r * 1.55f;

                stroke.setColor(OUTLINE);
                stroke.setStrokeWidth(outline * 1.1f);
                c.drawLine(cx, headBot, cx, cy + spikeLen, stroke);
                stroke.setColor(colour);
                stroke.setStrokeWidth(Math.max(1.2f * d, r * 0.3f));
                c.drawLine(cx, headBot, cx, cy + spikeLen, stroke);

                PATH.reset();
                PATH.moveTo(cx - headW, headTop);
                PATH.lineTo(cx + headW, headTop);
                PATH.lineTo(cx + headW * 0.62f, headBot);
                PATH.lineTo(cx + headW * 1.18f, headBot + r * 0.3f);
                PATH.lineTo(cx - headW * 1.18f, headBot + r * 0.3f);
                PATH.lineTo(cx - headW * 0.62f, headBot);
                PATH.close();
                stroke.setColor(OUTLINE);
                stroke.setStrokeWidth(outline);
                c.drawPath(PATH, stroke);
                fill.setColor(colour);
                c.drawPath(PATH, fill);
                c.restoreToCount(save);
                return;
            }
            default: {
                stroke.setColor(OUTLINE);
                stroke.setStrokeWidth(outline);
                c.drawCircle(cx, cy, r, stroke);
                fill.setColor(colour);
                c.drawCircle(cx, cy, r, fill);
            }
        }
    }

    /**
     * Draw the HOLLOW form, for a muted pin.
     *
     * <p>A muted pin is still there and still selectable; it simply moves nothing. Hollow says
     * that without inventing a fifth colour, and without the half-opacity that {@code SpriteIcons}
     * rightly forbids — a faded control reads as disabled, and this one is not.
     */
    public static void drawMuted(@NonNull Canvas c, @NonNull PuppetPin.Type type,
                                 float cx, float cy, float r, int colour,
                                 @NonNull Paint fill, @NonNull Paint stroke, float d) {
        draw(c, type, cx, cy, r, colour, fill, stroke, d);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF050507);
        c.drawCircle(cx, cy, Math.max(1f, r * 0.42f), fill);
    }
}
