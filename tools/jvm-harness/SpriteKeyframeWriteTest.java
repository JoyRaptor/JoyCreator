import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

/**
 * The owner's 11-28 report (Note 20, Studio): "I import a sprite, set a keyframe, move it, rotate it, and the rotation
 * box rotates out of the sprite." The transform box writes a keyframed sprite one property at a time, in AffineTransformHost's
 * order: scale, rotation, then X and Y. Each write used to re-anchor the OTHER pose tracks at that moment from the
 * sprite's STATIC values, so writing Y put X back to its static value (the sprite stuck on one axis), and a rotate put the
 * position and size back to their statics (the picture left the box). Text was fixed the same way on 08-12 (96ecf35fe);
 * the sprite's copy of the method never was.
 *
 * Real model class. The box draws what it wrote; every renderer draws animated*() at the same time. They must agree.
 */
public class SpriteKeyframeWriteTest {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String name) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }
    static void near(float got, float want, String name) { check(Math.abs(got - want) < 1e-4f, name + "  (got " + got + ", want " + want + ")"); }

    /** The owner's sprite: static pose X 50%, Y 75%, scale 25%, -15 degrees; keyed across five seconds. */
    static SpriteOverlayItem keyedStar() {
        SpriteOverlayItem s = new SpriteOverlayItem("star", "sheet");
        s.setCenter(0.5f, 0.75f);
        s.setSizeFraction(0.25f);
        s.setRotationDeg(-15f);
        // Keys put on the tracks directly, as a saved project loads them (not through the method under test).
        key(s, KeyframeSet.X, 0L, 0.3f);
        key(s, KeyframeSet.Y, 0L, 0.5f);
        key(s, KeyframeSet.ROTATION, 0L, 0f);
        key(s, KeyframeSet.X, 5000L, 0.7f);
        key(s, KeyframeSet.Y, 5000L, 0.5f);
        key(s, KeyframeSet.ROTATION, 5000L, 135f);
        return s;
    }

    static void key(SpriteOverlayItem s, String prop, long t, float v) {
        s.getKeyframes().getOrCreate(prop).put(t, v, com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
    }

    /** What the box does when a drag ends at (x, y) with this size and angle (AffineTransformHost.writeQuad). */
    static void boxWrites(SpriteOverlayItem s, long t, float x, float y, float size, float deg) {
        s.addPropertyKeyframeAt(KeyframeSet.SCALE, t, size);
        s.addPropertyKeyframeAt(KeyframeSet.ROTATION, t, deg);
        s.addPropertyKeyframeAt(KeyframeSet.X, t, x);
        s.addPropertyKeyframeAt(KeyframeSet.Y, t, y);
    }

    public static void main(String[] args) {
        long t = 2151L;

        // A MOVE at 2.151 s: the picture goes where the box went, both axes.
        SpriteOverlayItem moved = keyedStar();
        near(moved.animatedCenterX(t), 0.3f + 0.4f * t / 5000f, "set-up: the sprite is where its keys put it");
        float rotBefore = moved.animatedRotation(t);
        float sizeBefore = moved.animatedSizeFraction(t);
        boxWrites(moved, t, 0.61f, 0.73f, sizeBefore, rotBefore);
        near(moved.animatedCenterX(t), 0.61f, "move: the sprite is drawn where the box was dragged, across");
        near(moved.animatedCenterY(t), 0.73f, "move: and down");
        near(moved.animatedRotation(t), rotBefore, "move: the angle stays the keyed one");

        // A ROTATE at 2.151 s with the box's centre unchanged: the picture turns where it is.
        SpriteOverlayItem turned = keyedStar();
        float cx = turned.animatedCenterX(t), cy = turned.animatedCenterY(t), sz = turned.animatedSizeFraction(t);
        boxWrites(turned, t, cx, cy, sz, 100f);
        near(turned.animatedRotation(t), 100f, "rotate: the sprite turns to the box's angle");
        near(turned.animatedCenterX(t), cx, "rotate: and stays inside the box (it used to jump to its static X)");
        near(turned.animatedCenterY(t), cy, "rotate: and its static Y");
        near(turned.animatedSizeFraction(t), sz, "rotate: and keeps its size");

        // A single-property write leaves the other tracks' existing keys alone (they are the person's).
        SpriteOverlayItem keyed = keyedStar();
        keyed.addPropertyKeyframeAt(KeyframeSet.ROTATION, 5000L, 90f);
        near(keyed.animatedCenterX(5000L), 0.7f, "a rotation key does not move an existing position key");

        // A rotate at another time does not pull the position there to the static X (no jump). Its seeded X key takes
        // the value the sprite had at that moment. (It is eased like every seeded key, as on text since 96ecf35fe, so
        // the path between keys reshapes slightly: a shared behaviour, not this report.)
        SpriteOverlayItem path = keyedStar();
        float at4Before = path.animatedCenterX(4000L);
        path.addPropertyKeyframeAt(KeyframeSet.ROTATION, 4000L, 20f);
        near(path.animatedCenterX(4000L), at4Before, "a rotate at 4 s keeps the sprite where it was at 4 s (it used to jump to its static X)");

        // The sprite's own drag (an unselected sprite dragged directly): it starts from the pose the sprite has now, keys
        // a keyed sprite as it moves, draws only the pose at this moment, and no longer keys the static pose on release.
        try {
            String v = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayView.java")), "UTF-8");
            check(v.contains("startCenterX = hit.ownCenterX(currentTimeMs)"), "own drag: starts from the pose at this moment");
            check(v.contains("manipulating.addPropertyKeyframeAt(com.fadcam.ui.faditor.keyframe.KeyframeSet.X, currentTimeMs"),
                    "own drag: a keyed sprite is keyed as it moves");
            check(!v.contains("live ? o.getCenterX()") && !v.contains("o.addKeyframeAt(currentTimeMs)"),
                    "own drag: no static 'live' pose, no static pose keyed on release");
        } catch (java.io.IOException e) { check(false, "read SpriteOverlayView: " + e); }

        System.out.println("SpriteKeyframeWrite: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
