import com.fadcam.ui.faditor.model.path.StudioPath;

/**
 * STU-5 primitives step 1: the path every later piece (text on a path, motion paths, masks) stands
 * on. Run: {@code bash tools/jvm-harness/run-studiopath.sh}
 */
public class StudioPathTest {

    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    public static void main(String[] args) {
        StudioPath line = StudioPath.line(0.1f, 0.5f, 0.9f, 0.5f);
        check("a line is as long as it is", near(line.length(), 0.8f, 1e-4f));
        StudioPath.Point mid = line.pointAt(0.5f);
        check("halfway along a line is its middle, heading right", near(mid.x, 0.5f, 1e-4f) && near(mid.y, 0.5f, 1e-4f)
                && near(mid.angleDeg, 0f, 0.01f));
        check("an open path stops at its ends", near(line.pointAt(1.7f).x, 0.9f, 1e-4f) && near(line.pointAt(-1f).x, 0.1f, 1e-4f));

        StudioPath rect = StudioPath.rect(0f, 0f, 0.4f, 0.2f);
        check("a rectangle's length is its perimeter", near(rect.length(), 1.2f, 1e-4f));
        StudioPath.Point corner = rect.pointAt(0.4f / 1.2f);
        check("a third of the way round that rectangle is its top-right corner", near(corner.x, 0.4f, 1e-3f) && near(corner.y, 0f, 1e-3f));
        check("a closed path comes back to its start", near(rect.pointAt(1f).x, 0f, 1e-3f) && near(rect.pointAt(1f).y, 0f, 1e-3f));
        check("a closed path wraps round past its end", near(rect.pointAt(1.25f).x, rect.pointAt(0.25f).x, 1e-4f));

        StudioPath circle = StudioPath.circle(0.5f, 0.5f, 0.25f, 0.25f);
        float want = (float) (2 * Math.PI * 0.25);
        check("a circle's length is 2 pi r within 0.1% (" + circle.length() + " vs " + want + ")",
                near(circle.length(), want, want * 0.001f));
        StudioPath.Point top = circle.pointAt(0f), right = circle.pointAt(0.25f), bottom = circle.pointAt(0.5f);
        check("a circle starts at the top and heads right (clockwise on screen)",
                near(top.x, 0.5f, 1e-3f) && near(top.y, 0.25f, 1e-3f) && near(top.angleDeg, 0f, 2f));
        check("a quarter of the way round is the right-hand side, heading down",
                near(right.x, 0.75f, 2e-3f) && near(right.y, 0.5f, 2e-3f) && near(right.angleDeg, 90f, 2f));
        check("halfway round is the bottom", near(bottom.x, 0.5f, 2e-3f) && near(bottom.y, 0.75f, 2e-3f));
        boolean even = true;
        for (int i = 0; i < 16; i++) {
            StudioPath.Point p = circle.pointAt(i / 16f);
            float r = (float) Math.hypot(p.x - 0.5f, p.y - 0.5f);
            if (!near(r, 0.25f, 0.25f * 0.0005f)) even = false;
        }
        check("every point of the circle is on the circle (within 0.05%: the standard Bezier circle)", even);

        StudioPath arc = StudioPath.arc(0.5f, 0.5f, 0.25f, 180f, 180f);
        check("a half arc is half the circle", near(arc.length(), want / 2f, want * 0.001f));
        check("a half arc from the left over the bottom ends on the right",
                near(arc.pointAt(0f).x, 0.25f, 1e-3f) && near(arc.pointAt(1f).x, 0.75f, 1e-3f));
        StudioPath arcMid = StudioPath.arc(0.5f, 0.5f, 0.25f, 180f, 180f);
        StudioPath.Point am = arcMid.pointAt(0.5f);
        check("its middle is the top (screen y runs down, so 270 degrees is up)", near(am.x, 0.5f, 2e-3f) && near(am.y, 0.25f, 2e-3f));
        StudioPath small = StudioPath.arc(0.5f, 0.5f, 0.25f, 0f, 30f);
        check("a 30-degree arc is one twelfth of the circle", near(small.length(), want / 12f, want * 0.001f));

        // Freehand: a wobbly near-straight stroke becomes a couple of nodes that keep the ends.
        float[] stroke = new float[100 * 2];
        for (int i = 0; i < 100; i++) {
            stroke[i * 2] = 0.1f + i * 0.008f;
            stroke[i * 2 + 1] = 0.5f + ((i % 2 == 0) ? 0.0005f : -0.0005f);
        }
        StudioPath free = StudioPath.fromStroke(stroke, 0.003f);
        check("a wobbly straight stroke is simplified on release to a few nodes (" + free.nodes().size() + ")",
                free.nodes().size() >= 2 && free.nodes().size() <= 4);
        check("it keeps where the pen started and stopped",
                near(free.nodes().get(0).x, 0.1f, 1e-4f) && near(free.nodes().get(free.nodes().size() - 1).x, 0.892f, 1e-3f));
        float[] bend = new float[60 * 2];
        for (int i = 0; i < 60; i++) {
            double a = Math.PI * i / 59.0;
            bend[i * 2] = (float) (0.5 - 0.3 * Math.cos(a));
            bend[i * 2 + 1] = (float) (0.5 - 0.3 * Math.sin(a));
        }
        StudioPath swoop = StudioPath.fromStroke(bend, 0.002f);
        check("a curved stroke keeps enough nodes to stay curved (" + swoop.nodes().size() + ")", swoop.nodes().size() >= 5);
        StudioPath.Point sm = swoop.pointAt(0.5f);
        check("and still passes near the top of the curve the pen drew", near(sm.y, 0.2f, 0.01f));
        check("a stroke of one point is an empty path, never a crash",
                StudioPath.fromStroke(new float[]{0.1f, 0.1f}, 0.01f).nodes().isEmpty()
                        && StudioPath.fromStroke(new float[]{0.1f, 0.1f}, 0.01f).pointAt(0.5f) == null);

        // Saving.
        StudioPath back = StudioPath.fromArray(circle.toArray());
        check("a path survives save and load", back.isClosed() && back.nodes().size() == 4
                && near(back.length(), circle.length(), 1e-6f));
        check("a broken saved array gives what it can", StudioPath.fromArray(new float[]{1f, 0.5f}).nodes().isEmpty()
                && StudioPath.fromArray(null).nodes().isEmpty());
        // Moving a node re-measures.
        StudioPath l2 = StudioPath.line(0f, 0f, 1f, 0f);
        float before = l2.length();
        l2.nodes().get(1).x = 2f;
        l2.changed();
        check("moving a node and saying so re-measures the path", near(before, 1f, 1e-4f) && near(l2.length(), 2f, 1e-4f));

        System.out.println("StudioPathTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
