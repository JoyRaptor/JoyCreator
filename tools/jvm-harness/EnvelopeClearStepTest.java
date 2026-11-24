import com.fadcam.ui.faditor.model.VolumeKeyframe;
import com.fadcam.ui.faditor.tools.EnvelopeClearStep;

import java.util.ArrayList;
import java.util.List;

/** STU-4: the audio drawer's Clear is one undoable step, and the row re-reads. Run: bash tools/jvm-harness/run-envelopeclear.sh */
public class EnvelopeClearStepTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("FAIL  " + name); }
    }

    /** A plain envelope standing in for the clip. */
    static final class Fake implements EnvelopeClearStep.Envelope {
        List<VolumeKeyframe> keys = new ArrayList<>();
        public boolean has() { return !keys.isEmpty(); }
        public List<VolumeKeyframe> snapshot() {
            List<VolumeKeyframe> c = new ArrayList<>();
            for (VolumeKeyframe k : keys) c.add(new VolumeKeyframe(k.timeMs, k.volume));
            return c;
        }
        public void restore(List<VolumeKeyframe> k) { keys = new ArrayList<>(k); }
        public void clear() { keys = new ArrayList<>(); }
    }

    /** What the editor's recordUndo does: keeps ONE step with redo and undo, in that order. */
    static final class Stack implements EnvelopeClearStep.Recorder {
        int steps;
        String label;
        Runnable redo, undo;
        public void record(String l, Runnable r, Runnable u) { steps++; label = l; redo = r; undo = u; }
    }

    public static void main(String[] a) {
        Fake env = new Fake();
        for (int i = 0; i < 10; i++) env.keys.add(new VolumeKeyframe(i * 1000L, 0.5f));
        Stack stack = new Stack();
        int[] refreshes = {0};
        int[] pointsShown = {-1};
        Runnable after = () -> { refreshes[0]++; pointsShown[0] = env.keys.size(); };

        check("press clears and says so", EnvelopeClearStep.press(env, "Clear envelope", stack, after));
        check("the envelope is empty", env.keys.isEmpty());
        check("one press is one step", stack.steps == 1);
        check("the step carries the label", "Clear envelope".equals(stack.label));
        check("the row re-reads after the clear (0 points, not 10)", pointsShown[0] == 0);

        stack.undo.run();
        check("UNDO brings all 10 points back", env.keys.size() == 10);
        check("the row re-reads after undo (10 points)", pointsShown[0] == 10);
        check("the points are the same ones", env.keys.get(3).timeMs == 3000L && env.keys.get(9).volume == 0.5f);

        stack.redo.run();
        check("REDO clears again", env.keys.isEmpty());
        check("the row re-reads after redo (0 points)", pointsShown[0] == 0);

        stack.undo.run();
        stack.undo.run();
        check("undoing twice does not duplicate points", env.keys.size() == 10);

        Fake empty = new Fake();
        Stack none = new Stack();
        check("nothing to clear: false, no step", !EnvelopeClearStep.press(empty, "Clear envelope", none, after) && none.steps == 0);

        System.out.println("EnvelopeClearStepTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
