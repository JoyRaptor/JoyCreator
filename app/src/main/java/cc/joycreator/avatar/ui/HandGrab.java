package cc.joycreator.avatar.ui;

import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A finger on a hand, on a Perform stage (the pilot's liveGrab / liveMove / liveEnd for hands): a finger that lands near
 * where a hand is drawn takes that hand, and the hand follows the finger (keeping where on the hand it was taken) until the
 * finger lifts; the hand then stays where it was put. A finger anywhere else goes to the stage's other grab (the body for
 * walking), so a hand always wins over the body. Used by the room's Perform stage and by Studio's performing pop-up.
 */
public final class HandGrab implements StageView.BodyDrag {

    /** What a hand grab drives (the room's and Studio's {@code Inputs}). */
    public interface Hands {
        /** The hand ("L" or "R") is held at (x, y), character pixels. */
        void hold(String side, double x, double y);

        void letGo(String side);
    }

    /** How near to a hand a finger must land, in screen pixels (the pilot's 30, a little more for a fingertip). */
    public static final float REACH_PX = 36;

    private final StageView stage;
    private final Supplier<Map<String, double[]>> tips;
    private final UnaryOperator<double[]> toInner;
    private final Hands hands;
    private final StageView.BodyDrag body;
    private final float density;
    private String side;
    private double offX, offY;
    private boolean bodyHeld;

    /**
     * @param tips    where each hand is drawn now ("L", "R"), stage coordinates, from the last frame solved
     * @param toInner stage coordinates to the character's own (where the body walked to taken off); null = the same
     * @param body    the stage's other grab, or null
     */
    public HandGrab(StageView stage, Supplier<Map<String, double[]>> tips, UnaryOperator<double[]> toInner, Hands hands,
                    StageView.BodyDrag body) {
        this.stage = stage;
        this.tips = tips;
        this.toInner = toInner;
        this.hands = hands;
        this.body = body;
        this.density = stage.getResources().getDisplayMetrics().density;
    }

    /** The hand ("L" or "R") drawn nearest to (x, y), stage coordinates, within {@code radius}; null when none is. */
    public static String nearest(Map<String, double[]> tips, double x, double y, double radius) {
        if (tips == null) return null;
        String best = null;
        double bd = radius;
        for (String s : new String[] {"L", "R"}) {
            double[] p = tips.get(s);
            if (p == null || p.length < 2) continue;
            double d = Math.hypot(p[0] - x, p[1] - y);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    private double[] inner(double x, double y) {
        double[] p = {x, y};
        return toInner == null ? p : toInner.apply(p);
    }

    @Override
    public boolean grab(float x, float y) {
        side = null;
        bodyHeld = false;
        float s = stage.viewScale();
        Map<String, double[]> t = tips.get();
        String hit = nearest(t, x, y, REACH_PX * density / (s > 0 ? s : 1));
        if (hit != null) {
            double[] at = inner(t.get(hit)[0], t.get(hit)[1]), finger = inner(x, y);
            side = hit;
            offX = at[0] - finger[0];
            offY = at[1] - finger[1];
            hands.hold(side, at[0], at[1]);
            return true;
        }
        bodyHeld = body != null && body.grab(x, y);
        return bodyHeld;
    }

    @Override
    public void drag(float x, float y) {
        if (side != null) {
            double[] f = inner(x, y);
            hands.hold(side, f[0] + offX, f[1] + offY);
        } else if (bodyHeld) {
            body.drag(x, y);
        }
    }

    @Override
    public void release() {
        if (side != null) {
            hands.letGo(side);
            side = null;
        } else if (bodyHeld) {
            body.release();
        }
        bodyHeld = false;
    }
}
