import cc.joycreator.avatar.perform.OwnJobs;

import java.util.ArrayList;
import java.util.List;

/** The joystick cancelling its own delayed jobs must leave everybody else's alone (the room's autosave, a save or an open). */
public class OwnJobsTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    /** One shared queue, as the room's Handler. */
    static final class Queue implements OwnJobs.Poster {
        final List<Runnable> q = new ArrayList<>();

        public void post(Runnable r, long ms) {
            q.add(r);
        }

        public void remove(Runnable r) {
            q.remove(r);
        }

        void runAll() {
            List<Runnable> l = new ArrayList<>(q);
            q.clear();
            for (Runnable r : l) r.run();
        }
    }

    public static void main(String[] a) {
        Queue q = new Queue();
        OwnJobs stick = new OwnJobs(q);
        final boolean[] saved = {false}, tapped = {false};
        q.post(() -> saved[0] = true, 1500);                // the room's autosave, posted by the room itself
        stick.after(280, () -> tapped[0] = true);           // the stick's wait for a second tap
        stick.cancel(stick);                                // the second tap came: it cancels its own wait
        q.runAll();
        ok(saved[0], "the autosave still runs after the stick cancels its own jobs");
        ok(!tapped[0], "the stick's own job was cancelled");
        stick.after(10, () -> tapped[0] = true);
        q.runAll();
        ok(tapped[0], "a job posted after a cancel runs");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
