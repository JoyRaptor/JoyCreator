package cc.joycreator.avatar.studio;

import android.graphics.Canvas;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.ui.PoseRenderer;

import java.util.Map;
import java.util.function.Function;

/**
 * Which {@link CharacterPoser} Studio draws characters with: {@link SolverPoser}, the room's own solver and GPU drawing
 * (X2 + X3, 11-27), falling back to the standing {@link RestPoser} where the GPU cannot draw off screen. A test or a
 * later engine can still {@link #install} another.
 */
public final class CharacterPosers {
    private static volatile CharacterPoser.Factory factory = SolverPoser::new;
    private static volatile boolean real = true;

    private CharacterPosers() {}

    public static CharacterPoser.Factory get() {
        return factory;
    }

    /** The solver's drawing (X2 / X3), as the room's seam takes it. After this, characters move in Studio too. */
    public static void install(Function<CharacterDoc, PoseRenderer> renderers) {
        if (renderers == null) return;
        install((doc, pictures) -> new FromRenderer(renderers.apply(doc)));
    }

    /** A poser of Studio's own shape (time and rewind passed through). */
    public static void install(CharacterPoser.Factory f) {
        if (f == null) return;
        factory = f;
        real = true;
    }

    /** False while only the standing stand-in is installed: Studio says nothing to the person, the notes say so. */
    public static boolean moves() {
        return real;
    }

    /** The room's {@link PoseRenderer} used as a Studio poser: the signals each frame, then the drawing. */
    static final class FromRenderer implements CharacterPoser {
        private final PoseRenderer r;

        FromRenderer(PoseRenderer r) {
            this.r = r;
        }

        @Override
        public void draw(Canvas c, Map<String, Object> signals, double seconds) {
            r.setSignals(signals);
            r.draw(c);
        }

        @Override
        public void rewind() {
            r.setSignals(null);
        }

        @Override
        public void release() {
            r.release();
        }
    }
}
