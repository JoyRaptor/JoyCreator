package cc.joycreator.avatar.face;

import com.fadcam.ui.faditor.avatar.face.FaceCalibration;

/**
 * Check directions (the pilot's {@code fp.directions}; the bench's Directions): five short moves, each read by itself, that settle
 * which way is which on this phone (turn right, look down, tilt right, lean right, close your right eye). A move answered keeps
 * its direction; one not seen within eight seconds keeps the old one. Pure: the room shows the prompt and calls {@link #step}.
 */
public final class DirectionPrompts {
    /** What the face engine does for it ({@code FaceCapture.checkDirection} and {@code lastDirectionCheck}). */
    public interface Face {
        void check(String what);

        FaceCalibration.DirectionCheck last();
    }

    /** The five moves, in order (the engine's keys). */
    public static final String[] STEPS = {FaceCalibration.DirectionCheck.YAW, FaceCalibration.DirectionCheck.PITCH,
            FaceCalibration.DirectionCheck.ROLL, FaceCalibration.DirectionCheck.LEAN, FaceCalibration.DirectionCheck.EYE};
    /** How long a move may take before the next one shows (the pilot's 8 s). */
    public static final long WAIT_NANOS = 8_000_000_000L;

    private final Face face;
    private int at = -1, answered;
    private long since;

    public DirectionPrompts(Face face) {
        this.face = face;
    }

    public void start(long nowNanos) {
        at = 0;
        answered = 0;
        ask(nowNanos);
    }

    private void ask(long nowNanos) {
        since = nowNanos;
        face.check(STEPS[at]);
    }

    /** Running: a move is being asked for. */
    public boolean running() {
        return at >= 0;
    }

    /** The move asked for now (0..4), or -1 when not running. */
    public int prompt() {
        return at;
    }

    public int answered() {
        return answered;
    }

    /** Ends it where it is (Stop): what was answered stays. */
    public void stop() {
        at = -1;
    }

    /** Once a frame: the move answered (or its time up) moves on to the next. True when it has just finished. */
    public boolean step(long nowNanos) {
        if (at < 0) return false;
        FaceCalibration.DirectionCheck c = face.last();
        boolean ok = c != null && c.what().equals(STEPS[at]) && c.answer() != 0;
        if (!ok && nowNanos - since < WAIT_NANOS) return false;
        if (ok) answered++;
        if (++at >= STEPS.length) {
            at = -1;
            return true;
        }
        ask(nowNanos);
        return false;
    }
}
